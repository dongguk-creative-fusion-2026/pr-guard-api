#!/usr/bin/env bash
# 실행 검증 러너. PR Guard API 가 쿠버네티스 Job(또는 docker)으로 띄운다.
#
#   REPO_URL      https://github.com/owner/name.git
#   SHA           돌릴 커밋 (base 또는 head)
#   PR_NUMBER     포크 PR 의 커밋은 원본 레포에서 refs/pull/N/head 로만 받을 수 있다
#   CALLBACK_URL  https://api/.../api/test-runs/{id}
#   RUN_TOKEN     이 실행 전용 토큰
#   TRACE_PACKAGES  (선택) 호출을 기록할 패키지. 없으면 src/main/java 아래 공통 패키지를 쓴다. off 면 기록하지 않는다
#
# 진행은 {CALLBACK_URL}/progress 로, 결과(JUnit XML · 빌드 로그 끝부분 · 호출 기록 · 관측 기록)는 {CALLBACK_URL}/report 로 보낸다.
# clone 뒤 {CALLBACK_URL}/extra-files 에서 PR Guard 가 만든 증거 · 관측 테스트를 받아 레포 테스트와 함께 돌린다.
# 이어서 {CALLBACK_URL}/selection 에서 돌릴 테스트 클래스(영향 테스트 선택)를 받는다. 없으면 전체를 돌린다.
# head 는 테스트 뒤 {CALLBACK_URL}/mutation 의 계획대로 PIT 변이 테스트를 돌리고 mutations.xml 을 함께 보낸다.
# 지원: Gradle wrapper(gradlew), Maven wrapper(mvnw)
set -uo pipefail
: "${REPO_URL:?}" "${SHA:?}" "${CALLBACK_URL:?}" "${RUN_TOKEN:?}"

WORK="$HOME/work"
LOG="$WORK/build.log"
mkdir -p "$WORK"

# 의존성 캐시 (/cache, PR Guard 가 프로젝트 칸을 붙인다). base 는 읽기 · 쓰기, head(PR 코드)는 읽기만:
# PR 코드가 캐시에 손대 다음 실행을 오염시키지 못하게 한다
CACHE_MODE=none
if [ "${CACHE:-}" = 1 ] && [ -d /cache ]; then
  if [ "${SIDE:-}" = BASE ] && [ -w /cache ]; then
    CACHE_MODE=rw
    export GRADLE_USER_HOME=/cache/gradle
    export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=/cache/m2"
    mkdir -p /cache/gradle /cache/m2
  else
    CACHE_MODE=ro
    # Gradle: 읽기 전용 의존성 캐시 (modules-2 가 있는 caches 디렉터리). 없는 것만 새로 받는다
    [ -d /cache/gradle/caches/modules-2 ] && export GRADLE_RO_DEP_CACHE=/cache/gradle/caches
    # 래퍼 배포판 · Maven 저장소는 읽기 전용으로 쓸 수 없어 복사한다
    if [ -d /cache/gradle/wrapper/dists ]; then
      mkdir -p "$HOME/.gradle/wrapper" && cp -a /cache/gradle/wrapper/dists "$HOME/.gradle/wrapper/"
    fi
    if [ -d /cache/m2 ]; then
      mkdir -p "$HOME/.m2" && cp -a /cache/m2 "$HOME/.m2/repository"
    fi
  fi
fi
echo "의존성 캐시: $CACHE_MODE" >>"$LOG"
cache_note() {
  case "$CACHE_MODE" in
    rw) echo " (의존성 캐시 읽기 · 쓰기)" ;;
    ro) echo " (의존성 캐시 읽기 전용)" ;;
  esac
}

# Gradle 툴체인이 이미지 안의 JDK(21 기본 · 17)를 찾게 한다. 레포가 languageVersion 17 을 요구해도 내려받지 않고 쓴다
JDKS=""
for jdk in /opt/java/17 /opt/java/openjdk; do
  [ -x "$jdk/bin/java" ] && JDKS="${JDKS:+$JDKS,}$jdk"
done
if [ -n "$JDKS" ]; then
  GH="${GRADLE_USER_HOME:-$HOME/.gradle}"
  mkdir -p "$GH"
  # 캐시에 남는 파일이라 같은 줄을 거듭 붙이지 않는다
  grep -qxF "org.gradle.java.installations.paths=$JDKS" "$GH/gradle.properties" 2>/dev/null \
    || echo "org.gradle.java.installations.paths=$JDKS" >> "$GH/gradle.properties"
fi

progress() {
  # 진행 알림이 실패해도 테스트는 계속한다
  printf '{"phase":"%s","message":"%s"}' "$1" "$2" |
    curl --silent --show-error --max-time 10 -X POST -H "X-Run-Token: $RUN_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- "$CALLBACK_URL/progress" >/dev/null || true
}

report() {
  # report <exitCode> [error] — 보고서 파일들과 로그 끝부분을 한 번에 보낸다
  local code="$1" error="${2:-}"
  local args=(-F "exitCode=$code")
  [ -n "$error" ] && args+=(-F "error=$error")
  [ -n "${EVIDENCE_DROPPED:-}" ] && args+=(-F "evidenceDropped=$EVIDENCE_DROPPED")
  [ -f "$LOG" ] && tail -c 20000 "$LOG" > "$WORK/log-tail.txt" && args+=(-F "log=@$WORK/log-tail.txt;type=text/plain")
  # 실패한 테스트를 다시 돌렸으면 첫 결과는 first/ 에 있다
  local results="$WORK/src"
  [ -d "$WORK/first" ] && results="$WORK/first"
  while IFS= read -r -d '' f; do
    args+=(-F "report=@$f;type=application/xml")
  done < <(find "$results" \( -path '*/build/test-results/*' -o -path '*/target/surefire-reports/*' \) -name 'TEST-*.xml' -print0 2>/dev/null)
  if [ -d "$WORK/rerun" ]; then
    while IFS= read -r -d '' f; do
      args+=(-F "rerun=@$f;type=application/xml")
    done < <(find "$WORK/rerun" -name 'TEST-*.xml' -print0 2>/dev/null)
  fi
  for f in "$WORK"/trace/trace-*.json; do
    [ -s "$f" ] && args+=(-F "trace=@$f;type=application/json")
  done
  [ -s "$WORK/probe/probe.jsonl" ] && args+=(-F "probe=@$WORK/probe/probe.jsonl;type=application/json")
  for f in "$WORK"/pit/*/mutations.xml; do
    [ -s "$f" ] && args+=(-F "mutations=@$f;type=application/xml")
  done
  for attempt in 1 2 3; do
    curl --silent --show-error --fail --max-time 60 -X POST -H "X-Run-Token: $RUN_TOKEN" "${args[@]}" \
      "$CALLBACK_URL/report" && return 0
    sleep $((attempt * 2))
  done
  echo "결과 전송 실패" >&2
  return 1
}

progress cloning "소스 받는 중 (${SHA:0:7})"
cd "$WORK"
git init -q src && cd src
git remote add origin "$REPO_URL"
# GitHub 은 커밋 해시로 직접 받을 수 있다. 포크 PR 커밋이면 PR ref 로 다시 시도한다
if ! git fetch -q --depth 1 origin "$SHA" >>"$LOG" 2>&1; then
  if [ -n "${PR_NUMBER:-}" ] && git fetch -q origin "pull/$PR_NUMBER/head" >>"$LOG" 2>&1; then :; else
    report 128 "커밋을 받지 못함: ${SHA:0:7}"
    exit 0
  fi
fi
git -c advice.detachedHead=false checkout -q "$SHA" >>"$LOG" 2>&1 || { report 128 "커밋을 꺼내지 못함: ${SHA:0:7}"; exit 0; }

# PR Guard 가 만든 증거 테스트. 202 = 아직 만드는 중, 204 = 없음, 200 = "경로<TAB>base64" 줄들
EXTRA=()
progress evidence "증거 테스트 받는 중"
for attempt in $(seq 1 60); do
  status=$(curl --silent --max-time 10 -o "$WORK/extra.txt" -w '%{http_code}' -H "X-Run-Token: $RUN_TOKEN" \
    "$CALLBACK_URL/extra-files" || echo 000)
  if [ "$status" = 200 ]; then
    while IFS=$'\t' read -r path b64; do
      [ -z "$path" ] && continue
      # 레포 밖이나 숨은 경로에는 쓰지 않는다
      case "$path" in /*|*..*) continue ;; esac
      mkdir -p "$(dirname "$path")"
      printf '%s' "$b64" | base64 -d > "$path"
      EXTRA+=("$path")
      echo "증거 테스트 추가: $path" >>"$LOG"
    done < "$WORK/extra.txt"
    break
  elif [ "$status" = 202 ]; then
    sleep 3
  else
    break
  fi
done

# Gradle 초기화 스크립트: PRGUARD_SELECTED_TESTS 파일의 클래스만 돌린다
write_select_gradle() {
  cat > "$WORK/select.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        def names = new File(System.getenv('PRGUARD_SELECTED_TESTS')).readLines().collect { it.trim() }.findAll { it }
        filter {
            failOnNoMatchingTests = false
            if (names.isEmpty()) {
                includeTestsMatching 'prguard.selection.NoTests'
            } else {
                names.each { includeTestsMatching it; includeTestsMatching(it + '$*') }
            }
        }
    }
}
GRADLE
}

# 돌릴 테스트 (영향 테스트 선택). 202 = 고르는 중, 204 = 전체, 200 = 한 줄에 하나씩 테스트 클래스 (비어 있으면 레포 테스트 없음)
SELECTION=all
SELECTED=()
progress selecting "돌릴 테스트 받는 중"
for attempt in $(seq 1 60); do
  status=$(curl --silent --max-time 10 -o "$WORK/selection.txt" -w '%{http_code}' -H "X-Run-Token: $RUN_TOKEN" \
    "$CALLBACK_URL/selection" || echo 000)
  if [ "$status" = 200 ]; then
    SELECTION=filter
    while IFS= read -r cls || [ -n "$cls" ]; do
      # 빌드 도구 인자로 쓰므로 클래스 이름 모양만 받는다
      case "$cls" in ''|*[!A-Za-z0-9_.]*) continue ;; esac
      SELECTED+=("$cls")
    done < "$WORK/selection.txt"
    break
  elif [ "$status" = 202 ]; then
    sleep 3
  else
    break
  fi
done
if [ "$SELECTION" = filter ]; then
  # 증거 · 관측 테스트는 고른 것과 상관없이 늘 돈다
  for path in "${EXTRA[@]}"; do
    case "$path" in
      *src/test/java/*.java)
        cls="${path#*src/test/java/}"
        cls="${cls%.java}"
        SELECTED+=("${cls//\//.}")
        ;;
    esac
  done
  printf '%s\n' "${SELECTED[@]}" > "$WORK/selected-tests.txt"
  export PRGUARD_SELECTED_TESTS="$WORK/selected-tests.txt"
  # Gradle: 모든 모듈의 Test 작업에 필터. 고른 클래스가 없는 모듈은 실패하지 않고 넘어간다
  write_select_gradle
  echo "영향 테스트 선택: 테스트 클래스 ${#SELECTED[@]}개만 실행" >>"$LOG"
  printf '  %s\n' "${SELECTED[@]}" >>"$LOG"
else
  echo "영향 테스트 선택 없음: 전체 테스트 실행" >>"$LOG"
fi

# 호출 기록: JAVA_TOOL_OPTIONS 는 빌드 도구가 띄우는 테스트 JVM 까지 물려받는다
if [ "${TRACE_PACKAGES:-}" = off ]; then
  TRACE_PACKAGES=
elif [ -z "${TRACE_PACKAGES:-}" ]; then
  # src/main/java 아래 패키지를 앞 세 단계까지 줄여 모은다 (예: com/example/board/service, demo → com.example.board,demo)
  # 다른 항목 아래에 들어가는 것은 뺀다. 기본 패키지(디렉터리 없음)는 기록하지 않는다
  TRACE_PACKAGES=$(find . -path '*/src/main/java/*/*.java' -not -path '*/build/*' -not -path '*/target/*' 2>/dev/null |
    sed -E 's#^.*/src/main/java/##; s#/[^/]*$##' |
    awk -F/ '{n = NF < 3 ? NF : 3; p = $1; for (i = 2; i <= n; i++) p = p "/" $i; print p}' | sort -u |
    awk '{a[NR] = $0} END {for (i = 1; i <= NR; i++) {keep = 1; for (j = 1; j <= NR; j++) if (i != j && index(a[i], a[j] "/") == 1) keep = 0; if (keep) print a[i]}}' |
    head -20 | tr '/' '.' | paste -sd, -)
fi
if [ -n "${TRACE_PACKAGES:-}" ] && [ -f /opt/prguard/trace-agent.jar ]; then
  export TRACE_PACKAGES TRACE_DIR="$WORK/trace"
  mkdir -p "$TRACE_DIR"
  export JAVA_TOOL_OPTIONS="-javaagent:/opt/prguard/trace-agent.jar ${JAVA_TOOL_OPTIONS:-}"
  echo "호출 기록 패키지: $TRACE_PACKAGES" >>"$LOG"
fi

# 관측 테스트(동작 diff)가 결과를 쓰는 곳. 테스트 JVM 이 물려받는다
export PRGUARD_PROBE_DIR="$WORK/probe"

has_reports() {
  [ -n "$(find . \( -path '*/build/test-results/*' -o -path '*/target/surefire-reports/*' \) -name 'TEST-*.xml' -print -quit 2>/dev/null)" ]
}

run_build() {
  if [ -f ./gradlew ]; then
    progress building "Gradle 빌드 · 테스트$(cache_note)"
    chmod +x ./gradlew
    # --continue: 실패한 테스트가 있어도 나머지 모듈 테스트를 계속 돌린다
    local select=()
    [ "$SELECTION" = filter ] && select=(-I "$WORK/select.gradle")
    ./gradlew test --no-daemon --continue --console=plain "${select[@]}" >>"$LOG" 2>&1
  else
    progress building "Maven 빌드 · 테스트$(cache_note)"
    chmod +x ./mvnw
    local select=()
    if [ "$SELECTION" = filter ]; then
      local pattern=prguard.selection.NoTests
      if [ "${#SELECTED[@]}" -gt 0 ]; then
        # 클래스 뒤 * 는 중첩 클래스(@Nested)까지 포함한다
        pattern=$(printf '%s*,' "${SELECTED[@]}")
        pattern="${pattern%,}"
      fi
      select=("-Dtest=$pattern" -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false)
    fi
    ./mvnw -B -ntp test -fae -Dmaven.test.failure.ignore=false "${select[@]}" >>"$LOG" 2>&1
  fi
}

# 변경분 변이 테스트 (head 만). PR Guard 가 고른 클래스에 PIT 로 작은 버그를 심어 고른 테스트가 잡는지 본다.
# /mutation: 202 = 고르는 중, 204 = 없음, 200 = "timeout|class|test TAB 값" 줄들
PIT_DIR="${PIT_DIR:-/opt/prguard/pit}"
mutate() {
  [ -d "$PIT_DIR" ] || return 0
  local status=000
  for attempt in $(seq 1 20); do
    status=$(curl --silent --max-time 10 -o "$WORK/mutation.txt" -w '%{http_code}' -H "X-Run-Token: $RUN_TOKEN" \
      "$CALLBACK_URL/mutation" || echo 000)
    [ "$status" = 202 ] || break
    sleep 3
  done
  [ "$status" = 200 ] || return 0
  local limit=360 classes=() tests=()
  while IFS=$'\t' read -r kind value || [ -n "$kind" ]; do
    case "$value" in ''|*[!A-Za-z0-9_.]*) continue ;; esac
    case "$kind" in
      timeout) limit="$value" ;;
      class) classes+=("$value" "$value\$*") ;;
      test) tests+=("$value" "$value\$*") ;;
    esac
  done < "$WORK/mutation.txt"
  [ "${#classes[@]}" -gt 0 ] && [ "${#tests[@]}" -gt 0 ] || return 0
  progress mutating "변이 심는 중 (클래스 $((${#classes[@]} / 2))개)"

  # 테스트 클래스패스: 모듈마다 "모듈 경로 줄 + 클래스패스 줄"
  rm -rf "$WORK/cp" && mkdir -p "$WORK/cp"
  if [ -f ./gradlew ]; then
    cat > "$WORK/cp.gradle" <<'GRADLE'
allprojects {
    plugins.withId('java') {
        def dir = System.getenv('PRGUARD_CP_DIR')
        def name = (project.path == ':' ? 'root' : project.path.substring(1).replace(':', '_'))
        def cp = project.sourceSets.test.runtimeClasspath
        def projectDir = project.projectDir
        tasks.register('prguardClasspath') {
            dependsOn 'testClasses'
            doLast {
                def out = new File(dir, name + '.cp')
                out.parentFile.mkdirs()
                out.text = projectDir.absolutePath + '\n' + cp.files.findAll { it.exists() }.join(File.pathSeparator)
            }
        }
    }
}
GRADLE
    PRGUARD_CP_DIR="$WORK/cp" env -u JAVA_TOOL_OPTIONS ./gradlew prguardClasspath -I "$WORK/cp.gradle" --no-daemon -q \
      --console=plain >>"$LOG" 2>&1 || { echo "변이 테스트: 클래스패스를 못 구함" >>"$LOG"; return 0; }
  elif [ -f ./mvnw ] && ! grep -q '<modules>' pom.xml; then
    env -u JAVA_TOOL_OPTIONS ./mvnw -q -B -ntp dependency:build-classpath -Dmdep.outputFile="$WORK/cp/root.mvn" \
      -Dmdep.includeScope=test >>"$LOG" 2>&1 || { echo "변이 테스트: 클래스패스를 못 구함" >>"$LOG"; return 0; }
    printf '%s\n%s:%s:%s' "$PWD" "$PWD/target/test-classes" "$PWD/target/classes" "$(cat "$WORK/cp/root.mvn")" > "$WORK/cp/root.cp"
  else
    echo "변이 테스트: 지원하지 않는 빌드 구성 (Maven 다중 모듈)" >>"$LOG"
    return 0
  fi

  local targets tests_csv
  targets=$(IFS=,; echo "${classes[*]}")
  tests_csv=$(IFS=,; echo "${tests[*]}")
  for cpfile in "$WORK"/cp/*.cp; do
    [ -f "$cpfile" ] || continue
    local dir cp name has=0
    dir=$(sed -n 1p "$cpfile")
    cp=$(sed -n 2p "$cpfile")
    name=$(basename "$cpfile" .cp)
    # 고른 클래스의 소스가 이 모듈에 있을 때만
    for ((i = 0; i < ${#classes[@]}; i += 2)); do
      [ -f "$dir/src/main/java/${classes[$i]//.//}.java" ] && has=1
    done
    [ "$has" = 1 ] || continue
    printf '%s' "$cp" | tr ':' '\n' > "$WORK/pit-cp-$name.txt"
    printf -- '-cp\n%s/*:%s\n' "$PIT_DIR" "$cp" > "$WORK/pit-java-$name.args"
    echo "변이 테스트: $name 모듈 · 클래스 $targets · 테스트 $tests_csv" >>"$LOG"
    ( cd "$dir" && env -u JAVA_TOOL_OPTIONS timeout "$limit" java @"$WORK/pit-java-$name.args" \
        org.pitest.mutationtest.commandline.MutationCoverageReport \
        --reportDir "$WORK/pit/$name" --targetClasses "$targets" --targetTests "$tests_csv" \
        --sourceDirs "$dir/src/main/java" --outputFormats XML --timestampedReports=false --threads 2 \
        --fullMutationMatrix=true --skipFailingTests=true --timeoutConst 8000 \
        --classPathFile "$WORK/pit-cp-$name.txt" ) >>"$LOG" 2>&1 \
      || echo "변이 테스트: PIT 실패 또는 시간 초과 ($name)" >>"$LOG"
  done
}

# 실패한 테스트 다시 돌리기 (flaky 판별). 실패한 테스트 클래스만 한 번 더 돌리고, 첫 결과는 first/ 에 보존한다.
# 다시 돌린 결과는 보고서에 rerun 으로 따로 싣는다. PR Guard 의 증거 · 관측 테스트는 head 에서 일부러 실패하므로 빼다
RESULT_FIND=( \( -path '*/build/test-results/*' -o -path '*/target/surefire-reports/*' \) -name 'TEST-*.xml' )
rerun_failed() {
  local failed=() cls f
  while IFS= read -r -d '' f; do
    grep -q '<failure\|<error' "$f" || continue
    cls=$(basename "$f" .xml)
    cls="${cls#TEST-}"
    cls="${cls%%\$*}"
    case "$cls" in ''|*[!A-Za-z0-9_.]*|*PrGuardEvidence*|*PrGuardProbe*) continue ;; esac
    failed+=("$cls")
  done < <(find . "${RESULT_FIND[@]}" -print0 2>/dev/null)
  [ "${#failed[@]}" -gt 0 ] || return 0
  mapfile -t failed < <(printf '%s\n' "${failed[@]}" | sort -u | head -20)
  rm -rf "$WORK/first" "$WORK/rerun" && mkdir -p "$WORK/first" "$WORK/rerun"
  find . "${RESULT_FIND[@]}" -exec cp --parents {} "$WORK/first/" \;
  progress rerunning "실패한 테스트 ${#failed[@]}개 다시 실행 (flaky 판별)"
  printf '%s\n' "${failed[@]}" > "$WORK/rerun-tests.txt"
  echo "flaky 판별: 실패한 테스트 클래스 ${#failed[@]}개 다시 실행" >>"$LOG"
  if [ -f ./gradlew ]; then
    write_select_gradle
    PRGUARD_SELECTED_TESTS="$WORK/rerun-tests.txt" env -u JAVA_TOOL_OPTIONS timeout 180 \
      ./gradlew test --no-daemon --continue --console=plain -I "$WORK/select.gradle" >>"$LOG" 2>&1
  else
    local pattern
    pattern=$(printf '%s*,' "${failed[@]}")
    env -u JAVA_TOOL_OPTIONS timeout 180 ./mvnw -B -ntp test -fae "-Dtest=${pattern%,}" \
      -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false >>"$LOG" 2>&1
  fi
  find . "${RESULT_FIND[@]}" -exec cp --parents {} "$WORK/rerun/" \;
}

if [ ! -f ./gradlew ] && [ ! -f ./mvnw ]; then
  report 127 "빌드 도구를 찾지 못함 (gradlew · mvnw 없음)"
  exit 0
fi
run_build
code=$?
# 증거 테스트가 컴파일되지 않으면 레포 테스트까지 못 돈다. 증거 테스트를 빼고 다시 돌린다
if [ "${#EXTRA[@]}" -gt 0 ] && ! has_reports; then
  echo "증거 테스트를 빼고 다시 실행" >>"$LOG"
  rm -f "${EXTRA[@]}"
  rm -rf "$WORK/trace"/* "$WORK/probe"
  EVIDENCE_DROPPED=compile
  run_build
  code=$?
fi

# 테스트가 돌았을 때만 (빌드 실패면 다시 돌리거나 변이를 심을 수 없다)
if has_reports; then
  rerun_failed
  mutate
fi

progress reporting "결과 보내는 중"
report "$code"
exit 0
