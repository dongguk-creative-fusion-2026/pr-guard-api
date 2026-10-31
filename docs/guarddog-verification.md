# GuardDog 2단계: 실행 검증

## 2단계 완료 당시 기록 (2026-10-31)

2단계 로컬 실행 검증을 완료했다. Windows 재시작 뒤 발견된 Docker 실행 오류를 복구했고,
이미지 빌드, 실제 GuardDog 레지스트리 검사, 실제 PostgreSQL을 사용하는 리뷰 API 연결 검증이 통과했다.
원격 GitHub PR이나 코멘트는 생성하지 않았다.
이후 3단계에서 상태와 판정 정책을 보완했다. 현재 API 계약은 [검사 상태와 판정](guarddog-status.md)을
참고하며, 아래 테스트 수는 2단계 완료 시점의 기록이다.

| 항목 | 결과 | 검증 범위 |
|---|---|---|
| 기존 단위 테스트 | 125개 통과 | Windows 및 Linux 컨테이너, GuardDog 관련 18개 포함 |
| 실제 자식 프로세스 테스트 | 4개 통과 | JSON/stderr 분리, 잘못된 JSON, 출력 제한, 시간 초과·프로세스 종료 |
| PostgreSQL을 사용하는 로컬 리뷰 테스트 | 4개 통과 | 제어된 응답 3개와 실제 PyPI 검사 1개; Git 소스 → 분석 → worker → DB 저장 → API 조회 |
| 실제 CLI 통합 테스트 | 3개 통과 | npm/PyPI 지정 버전 및 미존재 패키지 검사 |
| 통합 테스트 컴파일 | 통과 | 레지스트리 검사와 리뷰 테스트 포함 |
| Docker Desktop 설치 | 복구 완료 | 4.97.0, 관리자용 표준 경로 |
| WSL 설치 | 완료 | 3.0.3, 기본 버전 2 |
| Docker 엔진 기동 | 통과 | Linux 엔진 정상 응답 |
| API/검증 Docker 이미지 빌드 | 통과 | `pr-guard-api:guarddog`, `pr-guard-api:guarddog-verification` |
| 실제 npm/PyPI CLI 검사 | 통과 | GuardDog 3.2.0, 요청한 고정 버전 확인, 검사 오류 없음 |

통합 테스트는 총 11개이며 실패·오류·건너뜀이 모두 0개다. Docker Engine 29.9.2/Linux에서
실행했고, 마지막 `verification-summary.json`의 상태는 `PASSED`다.

실제 응답에서 npm `is-number@7.0.0`은 issues 0 / risks 0 / errors 0,
PyPI `six==1.17.0`은 issues 2 / risks 0 / errors 0이었다. v3의 개별 신호(issues)를
그대로 악성 판정으로 바꾸지 않고 상관 분석 결과인 risks를 사용한다. 무작위 미존재 패키지는
다운로드 오류가 반환됐다. 실제 PyPI 리뷰는 `DONE / MERGEABLE`, findings 0건으로 저장·조회됐다.

로컬 DB 테스트에는 프로젝트 임시 폴더의 PostgreSQL 16.15.0을 사용했다. Windows 서비스에 등록하지
않았고, 임시 포트의 127.0.0.1에만 연결했으며 테스트 종료 후 프로세스를 중지했다.
각 테스트는 무작위 전용 schema에 모든 Flyway 마이그레이션을 적용하고 그 schema만 정리한다.

## Windows 설치 복구

재시작 후 사용자별 Docker 실행 시 `getting backend binary path: cannot find registry key` 오류가
발생했다. 실행 파일은 있었지만 필요한 등록 정보를 읽을 수 없었고, 바탕화면 바로가기는 Codex 앱
캐시 안의 설치 파일을 가리켰다. 같은 버전 공식 설치 프로그램의 덮어쓰기 시도는 exit code 3으로 중단됐다.

설정과 바로가기를 `.gradle/tooling/docker-recovery-20261031-121409/`에 백업했다. 확인된 Docker
가상 디스크는 없었다. 공식 제거 도구의 `uninstall --keep-data` 후 서명이 확인된 공식 설치 프로그램으로
`C:\Program Files\Docker\Docker`에 다시 설치했다. 재설치 뒤 새 바로가기, launcher/backend 실행,
Linux 엔진 응답을 확인했다. 레지스트리 값을 직접 생성하거나 데이터 초기화를 실행하지 않았다.

실행 중이던 셸의 PATH가 이전 설치 경로를 유지해 credential helper를 찾지 못하는 문제도 확인했다.
검증 스크립트는 선택한 Docker 실행 파일의 디렉터리를 해당 프로세스의 PATH에만 추가하고 종료 시
복원한다. 인증 설정과 시스템 PATH를 임의로 수정하지 않는다.

## 실제 실행에서 발견해 수정한 문제

API 이미지의 비관리자 사용자로 `guarddog --version`을 실행했을 때도 인기 패키지 캐시 갱신이
발생하며, 기본 설치 디렉터리에 쓰려다가 `PermissionError`가 발생했다. Docker 이미지에서
캐시 데이터를 `/var/cache/prguard/guarddog`에 별도로 복사하고 그 디렉터리만 app 사용자 소유로
설정했다. `GUARDDOG_TOP_PACKAGES_CACHE_LOCATION`을 CLI에 전달하도록 허용 목록을 보완했다.
API를 root로 실행하거나 GuardDog v3 보호 기능을 해제하는 방식은 사용하지 않았다.

## 검증한 동작

- 제어된 `high` 위험 결과: `GUARDDOG_DEPENDENCY_RISK / SECURITY / MAJOR` 저장,
  `NEEDS_CHANGES` 판정, `/api/reviews/{id}`에 탐지 근거와 결과 반환.
- 잘못된 출력과 시간 초과: 리뷰는 `DONE`으로 마무리되고 검사 실패 이유가 DB의 context.notes와
  API 응답에 남는다.
- 실제 OS 자식 프로세스의 표준 출력과 오류 출력을 분리하고, 큰 출력과 시간 초과를 처리한다.

위험·오류 응답은 **무해한 Java 테스트 프로세스**로 재현했다. 실제 GuardDog가 악성 패키지를
탐지했다는 증거로 보아서는 안 된다. GitHub 응답과 checkout 전송은 테스트 자료로 대체했으며,
실제 Git 커밋/worktree, 분석 파이프라인, worker, PostgreSQL repository, API controller/JSON 직렬화는
실제 코드를 사용했다. API 검증은 MockMvc 요청으로 수행했다. 외부 PR·코멘트를 만들지 않았다.

2단계 당시에는 검사 실패만 있고 다른 지적이 없으면 `MERGEABLE`이 될 수 있음을 확인했다.
3단계에서 이 동작을 변경해 PARTIAL/FAILED는 최소 NEEDS_CHANGES로 판정한다.

## 다시 전체 검증하기

1. Docker Desktop을 실행하고 Linux 엔진이 실행되는지 확인한다.
2. 최초 약관 동의가 표시되면 확인한다. 가상화 오류가 발생하면 WSL 상태와 Windows 재시작 필요 여부를 확인한다.
3. 레포 루트에서 다음을 실행한다.

```powershell
./scripts/verify-guarddog.ps1
```

스크립트는 다음을 수행한다.

- 30초 제한으로 Linux Docker 엔진을 확인한다. 엔진이 없으면 `BLOCKED`를 기록하고 멈춘다.
- `Dockerfile.guarddog`의 API 이미지와 별도 `verification` 이미지를 빌드한다.
- 최종 API 이미지의 비관리자 사용자로 GuardDog 버전과 `is-number@7.0.0` 검사를 실행한다.
- 고유한 Docker 네트워크와 일회용 PostgreSQL을 생성하고 전용 통합 테스트를 실행한다.
- 정상 종료·실패 모두 이번 실행이 만든 컨테이너·네트워크와 테스트 DB 볼륨만 정리한다.
  빌드한 로컬 이미지는 다음 검증을 위해 보관한다.

기본 테스트에는 통합 테스트가 포함되지 않는다. 기존 코드 확인은 `./gradlew.bat test`,
전체 통합 검증은 위 스크립트로 명시적으로 실행한다. Mockito는 Java 21에서 동적 attach 권한에
의존하지 않도록 Gradle 테스트 JVM의 시작 시점에 agent로 설정했다.

## 실제 CLI 검증 대상

- npm `is-number@7.0.0`: JSON 계약, 개별 검사 오류 유무, 요청한 버전이 실제 검사됐는지 확인.
- PyPI `six==1.17.0`: 동일 확인 및 ReviewWorker → PostgreSQL → API까지 연결.
- 무작위 미존재 PyPI 패키지: 다운로드/검사 오류가 정상 검사 결과처럼 처리되지 않는지 확인.

기존 공개 패키지의 탐지 결과가 반드시 0개라고 가정하지 않는다. 실제 규칙 결과는 보고서에 보관하고
오류 없는 응답인지 확인한다. GuardDog v3 보호 기능을 끄는 옵션은 사용하지 않는다.

## 결과 파일

검증 결과는 Git 추적에서 제외된 `build/reports/guarddog/`에 저장된다.

- `verification-summary.json`: `PASSED` / `FAILED` / `BLOCKED`, 실패 단계와 원인.
- `api-image-build.log`, `verification-image-build.log`, `integration-tests.log`: 빌드·통합 테스트 로그.
- `npm-is-number-7.0.0.json`, `pypi-six-1.17.0.json`, `pypi-missing-package.json`: 실제 CLI 응답.
- `review-controlled-risk.json`, `review-malformed-output.json`, `review-timeout.json`: 로컬 리뷰 API 응답.
- `review-live-six.json`: 실제 PyPI 검사와 연결한 리뷰 API 응답.
- `junit/TEST-*.xml`: 컨테이너에서 실행한 통합 테스트의 원본 결과.
- `unit-test-summary.json`: 검증 이미지 빌드 중 완료된 단위 테스트 결과의 집계.

실행하지 않은 항목의 결과 파일은 생성되지 않는다. 최종 완료 판단에는 `verification-summary.json`의
현재 실행 결과를 기준으로 삼고, 이전 실행에서 남은 파일만으로 통과를 판단하지 않는다.
