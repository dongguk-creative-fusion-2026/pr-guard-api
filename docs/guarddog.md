# GuardDog 의존성 검사

`GuardDogAnalyzer`는 기존 `Analyzer`로 등록되어 C(보안) 단계에서 실행된다. 별도 레포나 DB 변경 없이
`GET /api/reviews/{id}`의 `findings`, `context.guarddog`, `context.notes`에 결과가 반환된다.
기존 판정 값은 유지하며, 패키지별 전용 화면 연결은 다음 웹 작업에서 진행한다.
API 서버가 Java 프로젝트여도 검사 대상 PR은 npm/PyPI 프로젝트일 수 있다.

2단계 검증 절차와 현재 확인된 결과는 [실행 검증 기록](guarddog-verification.md)을 참고한다.
3단계의 상태·패키지별 API 계약과 판정 정책은 [검사 상태와 판정](guarddog-status.md)을 참고한다.

## 실행

기본값은 `GUARDDOG_ENABLED=false`. GuardDog **3.2.0**의 `scan --output-format json` 출력 계약을 기준으로 한다.
Linux/macOS의 API 실행 환경에 `pip install guarddog==3.2.0`으로 CLI를 설치하고 다음을 설정한다.

```dotenv
GUARDDOG_ENABLED=true
GUARDDOG_EXECUTABLE=/absolute/path/to/guarddog
GUARDDOG_TIMEOUT=PT1M
GUARDDOG_TOTAL_TIMEOUT=PT3M
GUARDDOG_MAX_PACKAGES=20
GUARDDOG_MAX_OUTPUT_BYTES=1048576
GUARDDOG_INCLUDE_DEV_DEPENDENCIES=true
```

Windows는 GuardDog의 공식 지원 방식인 Docker를 사용한다. API와 CLI를 함께 넣는 선택 이미지:

```sh
docker build -f Dockerfile.guarddog -t pr-guard-api:guarddog .
docker run --rm --env-file .env -e GUARDDOG_ENABLED=true -p 8080:8080 pr-guard-api:guarddog
```

기존 PostgreSQL/GitHub 설정도 필요하다. DB 주소는 컨테이너에서 접근 가능한 주소로 설정한다.
Docker 이미지는 인기 패키지 목록 캐시를 `/var/cache/prguard/guarddog`에 준비하고 API 사용자에게
그 캐시의 쓰기 권한만 준다. CLI를 시스템 공용 경로에 따로 설치해 실행하는 경우에는
`GUARDDOG_TOP_PACKAGES_CACHE_LOCATION`을 쓰기 가능한 캐시 디렉터리로 설정하고 GuardDog의
`analyzer/metadata/resources` 데이터를 복사해 준비한다. 해당 변수만 검사 프로세스에 전달하며,
설치된 프로그램 코드의 소유권이나 실행 권한은 높이지 않는다.
기본 `Dockerfile`이나 배포 설정은 바뀌지 않는다. 선택 이미지의 빌드와 실제 npm/PyPI 검사는
로컬 Docker에서 확인했다. 운영 환경에 적용할 때는 해당 환경에서 다시 검증한다.
GuardDog v3 기본 보호 기능을 끄는 옵션은 전달하지 않으며,
지원하지 않는 호스트에서는 실패를 기록한다.

## 검사 범위

- 변경된 `package.json`: `dependencies`, `optionalDependencies`, 기본으로 `devDependencies`.
  정확한 버전은 그대로 검사한다. 범위 버전은 같은 디렉터리의 npm v2/v3 `package-lock.json`에서
  루트 선언이 일치할 때 직접 의존성의 고정 버전을 사용한다. lockfile만 바뀐 PR도 비교한다.
- 변경된 `requirements.txt`: `name==version`, extras와 환경 마커를 포함한 고정 버전.
  환경 마커는 평가하지 않고 해당 버전도 검사한다. 이름의 대소문자·`-_.` 차이를 정규화한다.
- base(merge-base)와 head의 이름·버전을 비교해 추가/변경된 항목만 검사한다.
  파일 이름 변경 시 이전 경로를 사용한다. 같은 리뷰의 같은 패키지는 한 번만 호출한다.
- Java 인덱스 유무에 의존하지 않는다. PR 코드나 의존성을 설치하거나 실행하지 않고
  `guarddog npm|pypi scan <name> --version <version> --output-format json`을 직접 실행한다.

이번 단계에서 Maven/Gradle, 전이 의존성, npm v1 lockfile, yarn/pnpm, pyproject/poetry,
requirements include/constraints/hash 옵션, Git/URL/로컬 의존성은 검사하지 않는다.
지원 manifest 안의 새로 추가·변경된 해석 불가 항목은 `SKIPPED / UNSUPPORTED_SPEC`으로 남기고
검사 미완료로 판정한다. 변경 전에도 동일하게 있던 해석 불가 항목은 새 검사 대상으로 세지 않는다.
지원하지 않는 파일 형식은 검사 대상에 포함되지 않는다.
잠금 파일 없이 버전을 확정할 수 없으면 최신 버전으로 대신 검사하지 않는다.

## 결과와 실패

위험이 있으면 패키지당 `GUARDDOG_DEPENDENCY_RISK` finding을 반환한다. v3 `risks`의 `high`는
`MAJOR`, 그 외는 `MINOR`로 변환한다. `file`은 manifest 경로, `line`은 null이며 근거에는
규칙과 설명을 넣는다. 휴리스틱 탐지이므로 악성 확정이나 알려진 CVE 전체 검사를 의미하지 않는다.
기존 판정 정책에 따라 MAJOR는 머지 판정에 영향을 준다.

실행 실패, 타임아웃, 출력 제한, 잘못된 JSON, 개별 검사 오류(`errors`), v3 `risks` 누락,
한도 초과는 구조화된 상태·원인 코드와 notes에 남기며 다른 검사기는 계속 실행된다.
**GuardDog가 켜져 있고 결과가 PARTIAL/FAILED이면 MERGEABLE을 반환하지 않고 NEEDS_CHANGES로 판정한다.**
실제 BLOCKER가 있으면 기존 NOT_RECOMMENDED가 우선한다. 검사 오류를 가짜 악성 패키지 finding으로
추가하지 않는다. 미존재 패키지에 대한 GuardDog 다운로드 오류는 DOWNLOAD_FAILED로 표시하며,
네트워크 장애와 패키지 미존재를 따로 확정하는 규칙은 아직 제공하지 않는다.

검사 프로세스에는 API/DB/GitHub/cloud 환경 변수를 넘기지 않는다. CLI는 API 사용자 권한으로
실행하므로 운영 시 그 사용자의 홈 디렉터리에도 비밀 인증 파일을 두지 않는다.
검사 파일은 checkout 내부의 일반 파일만 읽고 크기를 제한한다. 패키지당 제한 외에
전체 검사 시간(기본 3분)과 패키지 수(기본 20개)를 제한한다.

## 참고

- [DataDog GuardDog](https://github.com/DataDog/guarddog) — Apache-2.0, 프로젝트 소스를 복사하지 않고 CLI 호출.
- [v3 JSON reporter](https://github.com/DataDog/guarddog/blob/v3/guarddog/reporters/json.py)
- [v3 risk output](https://github.com/DataDog/guarddog/blob/v3/guarddog/analyzer/analyzer.py)

추가 레포는 구현에 필요하지 않다. 실제 PR 검증에 팀의 `pr-guard-sandbox`를 나중에 사용할 수 있다.
