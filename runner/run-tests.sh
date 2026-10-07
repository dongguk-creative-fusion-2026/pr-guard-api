#!/usr/bin/env bash
# 실행 검증 러너. PR Guard API 가 쿠버네티스 Job(또는 docker)으로 띄운다.
#
#   REPO_URL      https://github.com/owner/name.git
#   SHA           돌릴 커밋 (base 또는 head)
#   PR_NUMBER     포크 PR 의 커밋은 원본 레포에서 refs/pull/N/head 로만 받을 수 있다
#   CALLBACK_URL  https://api/.../api/test-runs/{id}
#   RUN_TOKEN     이 실행 전용 토큰
#
# 진행은 {CALLBACK_URL}/progress 로, 결과(JUnit XML · 빌드 로그 끝부분)는 {CALLBACK_URL}/report 로 보낸다.
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
  [ -f "$LOG" ] && tail -c 20000 "$LOG" > "$WORK/log-tail.txt" && args+=(-F "log=@$WORK/log-tail.txt;type=text/plain")
  while IFS= read -r -d '' f; do
    args+=(-F "report=@$f;type=application/xml")
  done < <(find "$WORK/src" \( -path '*/build/test-results/*' -o -path '*/target/surefire-reports/*' \) -name 'TEST-*.xml' -print0 2>/dev/null)
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

if [ -f ./gradlew ]; then
  progress building "Gradle 빌드 · 테스트"
  chmod +x ./gradlew
  # --continue: 실패한 테스트가 있어도 나머지 모듈 테스트를 계속 돌린다
  ./gradlew test --no-daemon --continue --console=plain >>"$LOG" 2>&1
  code=$?
elif [ -f ./mvnw ]; then
  progress building "Maven 빌드 · 테스트"
  chmod +x ./mvnw
  ./mvnw -B -ntp test -fae -Dmaven.test.failure.ignore=false >>"$LOG" 2>&1
  code=$?
else
  report 127 "빌드 도구를 찾지 못함 (gradlew · mvnw 없음)"
  exit 0
fi

progress reporting "결과 보내는 중"
report "$code"
exit 0
