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
# 지원: Gradle wrapper(gradlew), Maven wrapper(mvnw)
set -uo pipefail
: "${REPO_URL:?}" "${SHA:?}" "${CALLBACK_URL:?}" "${RUN_TOKEN:?}"

WORK="$HOME/work"
LOG="$WORK/build.log"
mkdir -p "$WORK"

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
  while IFS= read -r -d '' f; do
    args+=(-F "report=@$f;type=application/xml")
  done < <(find "$WORK/src" \( -path '*/build/test-results/*' -o -path '*/target/surefire-reports/*' \) -name 'TEST-*.xml' -print0 2>/dev/null)
  for f in "$WORK"/trace/trace-*.json; do
    [ -s "$f" ] && args+=(-F "trace=@$f;type=application/json")
  done
  [ -s "$WORK/probe/probe.jsonl" ] && args+=(-F "probe=@$WORK/probe/probe.jsonl;type=application/json")
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
    progress building "Gradle 빌드 · 테스트"
    chmod +x ./gradlew
    # --continue: 실패한 테스트가 있어도 나머지 모듈 테스트를 계속 돌린다
    ./gradlew test --no-daemon --continue --console=plain >>"$LOG" 2>&1
  else
    progress building "Maven 빌드 · 테스트"
    chmod +x ./mvnw
    ./mvnw -B -ntp test -fae -Dmaven.test.failure.ignore=false >>"$LOG" 2>&1
  fi
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

progress reporting "결과 보내는 중"
report "$code"
exit 0
