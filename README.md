# pr-guard-api

PR Guard 백엔드. AI 네이티브 개발 환경에서 **속도는 그대로 두고 코드 품질을 지키는 PR 검증 계층**이다.

등록된 public GitHub 레포의 PR 을 웹훅으로 바로 받고(주기적인 조회가 놓친 것을 메운다), 새 커밋이 보이면 **diff · 레포 전체 · git 이력 · PR 설명**을 함께 분석하고, 켜져 있으면 **영향 있는 테스트만 골라 base · head 에서 실제로 돌려** 근거를 만든 뒤 판정해 PR 에 리뷰를 남긴다. LLM 의 말만 믿지 않는다: 근거는 정적 분석 · 이력 통계 · 실행 결과로 만들고, AI 지적은 그 층들에 비춰 확인하고(인덱스와 어긋나면 뺀다), 판정은 규칙이 계산한다.

- 프론트엔드: [pr-guard-web](https://github.com/dongguk-creative-fusion-2026/pr-guard-web)
- 테스트·평가용 레포: [pr-guard-sandbox](https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox)

- 의존성 보안: [GuardDog 연동·실행·검사 범위](docs/guarddog.md) (기본 꺼짐, npm/PyPI 신규·변경 직접 의존성)

## 흐름

```
POST /api/projects {url}
  └ URL 파싱 → GET /repos/{o}/{r} 로 public 확인 → projects 저장 → repo_graphs 에 PENDING

GraphWorker (10초마다) — PENDING 그래프 하나를 집어서
  └ GitHub Actions 워크플로(.github/workflows/repo-graph.yml) 실행 → RUNNING
     └ 워크플로: 기본 브랜치 clone → GitNexus 인덱싱 (gitnexus/export.mjs) → 파일 단위 그래프와 리뷰용 호출 그래프(impact)를
        POST /api/projects/{id}/graph/result 로 보냄 (API 는 runId 로 우리 워크플로 실행인지 GitHub 에 확인)
  (GitNexus 가 메모리를 1GB 가까이 써서 Railway 컨테이너 대신 Actions 에서 돌린다. GRAPH_RUNNER=local 이면 이 서버에서 직접)

PollScheduler (POLL_INTERVAL 마다) / POST /api/projects/{id}/poll
  └ GET /pulls?state=open (ETag, 변경 없으면 304)
     └ head SHA 가 처음 보이면 reviews 에 PENDING 생성

POST /api/github/webhook (GITHUB_WEBHOOK_SECRET 이 있으면)
  └ X-Hub-Signature-256 (HMAC-SHA256) 확인 → pull_request 이벤트를 폴링과 같은 방식으로 반영
     ├ opened · reopened · synchronize … : head SHA 가 처음 보이면 PENDING 생성 → ReviewWorker 를 바로 깨움
     └ closed : PR 을 닫힘으로
  (폴링은 그대로 돌아 놓친 이벤트를 메운다)

ReviewWorker (10초마다) — PENDING 하나를 집어서
  ├ 수집: PR 본문 · 커밋 메시지 · 변경 파일과 patch
  ├ AnalysisPipeline
  │   ├ RepoWorkspace   레포 clone → base(merge-base) / head worktree
  │   ├ JavaIndexer     양쪽 소스 인덱스 (타입 · 메서드 · 어노테이션 · 호출 관계) → 바뀐 메서드
  │   ├ GraphImpact     변경 영향: 미리 만든 GitNexus 호출 그래프(graph.impact)에서 바뀐 줄을 감싸는 함수를 찾고,
  │   │                 그 함수를 부르는 함수를 3단계까지 따라간다. 언어를 가리지 않고 clone 이 실패해도 patch 만으로 돈다.
  │   │                 1단계 Java 호출은 JavaIndexer 가 같은 호출을 찾았는지 표시하고, 결과는 LLM 구조 정보 · 단계 IMPACT ·
  │   │                 분석 재료(graphImpact) · PR 요약의 "분석 정보"에 들어간다 (판정 규칙은 그대로)
  │   ├ GitHistoryLoader 최근 커밋 이력 · 동시 변경 통계 · 바뀐 라인의 blame
  │   ├ Analyzer 들     결정적 검사, 분류(A~D)별로 나란히 실행 (아래 "결정적 검사")
  │   ├ Reviewer        LLM 리뷰 (JSON 스키마 응답, 구조 정보 함께 전달). 위 검사와 동시에 돈다
  │   │                 지적마다 kind(MISSING_SYMBOL · MISSING_CHECK · SECURITY …)와 근거 이름을 받고,
  │   │                 kind 가 SECURITY 면 분류를 C(보안)로 둔다
  │   ├ TestRunService  실행 검증 (EXEC_RUNNER 가 켜져 있으면, 소스 준비 직후 나란히 시작)
  │   │   ├ 쿠버네티스 Job 으로 base · head 러너(runner/) 기동 → clone · 빌드 · 테스트 → JUnit 결과 콜백
  │   │   ├ TestSelector     영향 테스트 선택: 바뀐 메서드를 지나가는 테스트 클래스만 base · head 에서 돌린다 (아래 "영향 테스트 선택")
  │   │   ├ EvidenceService  본문만 바뀐 메서드마다 "base 통과 · head 실패" 증거 테스트 생성 → 러너가 받아 함께 실행
  │   │   ├ HttpProbePlanner 바뀐 컨트롤러 GET 엔드포인트를 base · head 에서 같은 요청으로 호출하는 관측 테스트 (아래 "HTTP 동작 diff")
  │   │   ├ trace-agent      테스트 중 실제 호출 기록 (호출 횟수 · 간선 · 테스트별로 지나간 함수)
  │   │   └ 비교: 회귀(BLOCKER) · 새 테스트 실패 · 증거 테스트로 증명된 동작 변화(MAJOR) · 테스트가 안 지나간 변경(MINOR)
  │   ├ FindingGrounder 근거 확인: 지적마다 코드 인덱스 · git 이력 · 실행 · AI 층에 비춰 본다
  │   │   ├ 인덱스: 결정적 검사가 같은 곳을 지적했나, AI 가 "없다"고 한 이름이 head 에 있나,
  │   │   │         "빠졌다"고 한 검사가 연결된 코드(바뀐 메서드 · 호출부 · 그 타입을 받는 메서드)에 있나 (있으면 위치만 보여 주고 반박하지 않음)
  │   │   ├ 이력 · 실행 · AI: 빠진 동시 변경 파일 · 지운 줄의 원래 커밋, 실행으로 증명한 동작 변화, AI 도 같은 곳을 지적했나
  │   │   └ 상태: CONFIRMED (AI 아닌 층도 확인) · AI_ONLY (AI 만 주장) · REFUTED ("없다"고 한 이름이 head 에 있음 → 판정 · 라인 코멘트에서 뺌)
  │   └ VerdictPolicy   판정 계산 (BLOCKER ≥1 → 머지 비권장, MAJOR ≥1 → 수정 후 머지, REFUTED 는 세지 않음)
  ├ 저장: findings, 판정, 분석 재료 요약(context)
  └ PR: 요약 코멘트 1개(계속 수정) + 라인 코멘트(같은 지적은 한 번만)
```

단계마다 진행 · 결과를 `review_events` 에 남기고 `GET /api/reviews/{id}/events` 로 실시간(SSE) 보낸다. 화면의 파이프라인 · 리뷰 장면이 이 기록을 쓴다. 수집 단계에는 바뀐 파일 경로(최대 50개)를, 검사 · LLM · 근거 확인 단계에는 지적마다 분류 · kind · 증거(300자까지)를 싣는다.

단계가 실패해도 가능한 데까지 진행한다. 예를 들어 clone 이 실패하면 diff 만으로 리뷰하고, 이유는 요약 코멘트의 "분석 정보"에 남는다.

## 영향 테스트 선택

실행 검증은 PR 마다 base · head 에서 테스트를 돌린다. 전체를 돌리면 느리고 비싸서, **이번 PR 이 바꾼 코드를 지나가는 테스트만** 고른다 (Test Impact Analysis).

1. **기준 기록**: 같은 프로젝트에서 전체 테스트를 돌린 가장 최근 실행의 호출 기록(trace-agent: 테스트 → 지나간 메서드). 이번 base 와 같은 커밋의 기록을 먼저 쓴다.
2. **바뀐 메서드 → 테스트**: 기록상 그 메서드를 지나간 테스트 클래스 + 호출 그래프(인덱스)에서 그 메서드를 부르는 테스트 클래스.
3. **늘 돌리는 것**: 이번 PR 이 바꾼 테스트, 기준 기록에 없는 테스트(기록 뒤에 생김), 증거 · 관측 테스트.
4. **전체를 돌리는 경우** (영향 범위를 확정할 수 없음): 기준 기록 없음 · 오래됨(`EXEC_SELECTION_MAX_AGE`), 빌드 · 설정 · 리소스 변경, 메서드 밖 변경(필드 · 상수 · 클래스 선언), 테스트 보조 코드 변경, 테스트 밖(애플리케이션 시작 · 다른 스레드)에서만 실행되는 메서드 변경, 인터페이스 기본 메서드 변경, Java 가 아닌 테스트 소스. 문서 · 이미지 · `static/` 변경은 무시한다.

걸러지는 것은 "기준 기록에서 바뀐 메서드를 한 번도 지나가지 않았고 호출 그래프로도 닿지 않는 테스트" 뿐이다. 러너는 고른 클래스만 Gradle(`-I` 초기화 스크립트로 모든 모듈의 Test 작업에 필터) · Maven(`-Dtest`)으로 돌리고, base · head 에 같은 목록을 쓴다.

- 단계 `EXEC_SELECT` 와 실행 기록의 `selection` 에 테스트 클래스마다 돌렸는지 · 이유(기록 · 호출 그래프 · 바뀐 테스트 · 기록에 없음 · 영향 없음)와 기준 기록에서 걸린 시간을 남긴다. 화면의 "테스트 선택"이 그대로 그린다.
- **그림자 모드**(`EXEC_TEST_SELECTION=shadow`): 전체를 돌리되 "골랐다면"을 기록하고, 걸렀을 테스트에서 회귀 · 새 실패가 나오면 `EXEC_DIFF` 의 `selection.missed` 에 남긴다. 선택 규칙이 놓친 것이 없는지 실제 PR 로 확인할 때 쓴다.
- 일부만 돈 실행의 호출 기록은 기준으로도, 화면의 런타임 오버레이로도 쓰지 않는다 (전체 실행 기록만).

## HTTP 동작 diff

사용자가 체감하는 변화를 보여 준다. 예: `GET /owners?lastName=Davis` 가 base 에서는 `302 → /owners/1`, head 에서는 `200 화면 owners/ownersList · owners=2개`.

1. **대상**: 이번 PR 이 본문을 바꾸거나 새로 만든 `@Controller` · `@RestController` 메서드 중 GET 매핑(`@GetMapping`, 메서드 없는 · GET `@RequestMapping`)인 것 (최대 3개). 클래스의 `@RequestMapping` 경로를 앞에 붙인다. 상태를 바꿀 수 있는 POST · PUT · DELETE 는 보내지 않는다.
2. **요청**: head 의 기존 테스트에서 MockMvc `get("/경로?쿼리", 경로 변수…)` · `.param("이름", "값")` 의 문자열 상수를 모아 매핑 경로와 맞는 것을 쓴다 (핸들러마다 최대 6개). 없으면 경로 변수를 `1` 로 채운 기본 요청 하나.
3. **관측 테스트**: `@SpringBootApplication` 패키지에 `PrGuardProbeHttp{N}Test` 를 만들어 `@SpringBootTest` + MockMvc 로 같은 요청을 base · head 에서 보낸다. LLM 없이 만들고, Spring Boot 테스트 스타터(`spring-boot-starter-test`, Boot 4 의 `spring-boot-starter-webmvc-test` 등)가 있는 레포에서만 만든다.
4. **응답 요약**: 상태 코드, 리다이렉트 위치, 화면 이름, 모델 값(컬렉션 · 페이지는 개수, 최대 5개), 처리 중 예외, JSON 본문(120자까지).

요약이 다른 요청은 다른 관측 테스트와 같이 `EXEC_BEHAVIOR_DIFF`(MAJOR)로 핸들러 메서드에 붙고, PR 화면의 "동작 diff" 카드에 요청마다 base · head 응답이 나온다.


## 테스트 신뢰도

"테스트가 통과했다"가 아니라 "테스트가 버그를 실제로 잡느냐"를 본다.

- **정적** (`test-trust` 검사기, 아래 표): 이번 PR 이 테스트를 약하게 만들었나 · 새 테스트가 아무것도 검증하지 않나.
- **변경분 변이 테스트** (`EXEC_MUTATION`, head 실행 안에서): PR 이 본문을 바꾸거나 새로 만든 메서드가 있는 클래스(최대 5개)에
  PIT 로 작은 버그(조건 반전 · 경계 변경 · null 반환 · 호출 삭제 …)를 심고, 영향 테스트 선택이 고른 테스트로 돌린다.
  러너 이미지에 PIT 명령줄 실행기가 있고, 빌드 도구에서는 테스트 클래스패스만 받아 온다 (Gradle 은 `-I` 초기화 스크립트, Maven 은 단일 모듈만).
  결과는 바뀐 메서드 줄 범위로 걸러 변이마다 잡음 · 놓침 · 안 닿음과 잡은 테스트(메서드 단위)를 남긴다.
  - `EXEC_MUTANT_SURVIVED`: 지나가는 테스트가 있는데 변이를 하나도 못 잡음(2개 이상) → MAJOR "가짜 안전", 일부만 놓침 → MINOR. 증거는 놓친 변이와 그 줄을 지나간 테스트
  - head 테스트 중 실패하는 것은 PIT 가 빼고(`skipFailingTests`), 시간(`EXEC_MUTATION_TIMEOUT`)을 넘기면 결과 없이 건너뛴다
- **flaky 판별** (base · head 실행 안에서): 실패한 테스트 클래스(최대 20개, 증거 · 관측 테스트 제외)만 한 번 더 돌린다.
  처음엔 실패했는데 다시 돌리니 통과한 테스트는 flaky 로 보고 회귀(BLOCKER) · 새 실패(MAJOR)에서 빼고 `EXEC_FLAKY_TEST`(MINOR)로 따로 알린다.
  첫 결과는 그대로 보내고(`report`), 다시 돈 결과는 `rerun` 으로 따로 보낸다. `EXEC_DIFF` 의 `flaky` 에 base · head 목록

## 결정적 검사

AI 없이 규칙으로 잡는 검사. 근거 확인에서 인덱스 층이 확인한 지적(CONFIRMED)이 된다. A(의도 대비)는 아직 결정적 검사가 없어 LLM 리뷰가 맡는다.

| 분류 | 검사기 | 지적 | 심각도 |
|---|---|---|---|
| B · 영향 | `reference-check` | `UNRESOLVED_METHOD` 레포 안 타입에 없는 메서드 호출, `ARITY_MISMATCH` 인자 수가 어떤 선언과도 맞지 않음. base 에도 있던 것은 제외 (`SnapshotAnalyzer`) | BLOCKER |
| B · 영향 | `ast-behavior` | AST diff(GumTree): `AST_THROW_TO_NULL` 예외 → null 반환, `AST_NULL_CHECK_REMOVED`, `AST_EXCEPTION_SWALLOWED` 빈 catch, `AST_EXCEPTION_REMOVED` | MAJOR (예외 경로 삭제는 MINOR) |
| C · 보안 | `auth-removal` | `AST_AUTH_REMOVED` 메서드의 권한 어노테이션(@PreAuthorize · @Secured …)이 사라짐 | BLOCKER |
| C · 보안 | `semgrep` | `SEMGREP` (제목 `Semgrep · 보안: …`) [Semgrep](https://semgrep.dev) 결과 중 보안 규칙(SQL Injection · XSS · 위험한 API · 시크릿 …). 실행은 아래 [Semgrep](#semgrep) 참고 | ERROR → MAJOR, WARNING → MINOR |
| C · 보안 | `hardcoded-secret` | `HARDCODED_SECRET` 이번 PR 이 추가한 줄의 비밀값. 알려진 키 형식(AWS · GitHub · OpenAI · Slack · Google · 개인키)은 BLOCKER, 이름이 SECRET · TOKEN · PASSWORD · API_KEY … 인 상수 · 설정에 값을 직접 넣으면 MAJOR. `${...}` 주입 · 헤더 이름 · 테스트 코드 제외, 증거에는 값 앞 4자만 | BLOCKER / MAJOR |
| D · 위험도 | `architecture-delta` | base · head 인덱스의 파일 간 의존을 비교해 이번 PR 이 만든 의존만 본다. `ARCH_LAYER_BYPASS` 웹이 서비스를 건너뛰고 저장소를 바로 부름(프로젝트가 평소 서비스를 거칠 때만), `ARCH_REVERSE_DEPENDENCY` 아래 계층이 위 계층을 부름, `ARCH_NEW_MODULE_DEPENDENCY` 의존하지 않던 두 모듈 사이 새 의존. 증거는 "출발 파일 → 도착 파일" | MAJOR |
| D · 위험도 | `semgrep-quality` | `SEMGREP` 보안이 아닌 Semgrep 결과. 제목에 용도가 붙는다: `버그 패턴`(correctness) · `모범 사례`(best-practice · 성능 · 유지보수성) · `팀 규칙`(레포 규칙 파일) · `마이그레이션`(deprecated · 제거된 API, 수정 제안 포함) | ERROR → MAJOR, WARNING → MINOR (모범 사례는 MINOR 까지) |
| D · 위험도 | `test-trust` | 테스트 신뢰도(정적): 이번 PR 이 바꾼 테스트만 본다. `TEST_REMOVED` 검증하던 테스트를 지웠는데 그 테스트가 부르던 코드는 남음(실패 경로 검증이면 강조), `TEST_WEAKENED` 실패 경로 검증(assertThrows · never() …)이 줄어듦, `TEST_DISABLED` @Disabled · @Ignore 추가 → MAJOR. `TEST_NO_ASSERTION` 검증 없는 테스트, `TEST_TRIVIAL_ASSERTION` 항상 참인 검증뿐, `TEST_SWALLOWED_EXCEPTION` 예외를 삼키는 빈 catch, 검증 수 감소, 코드와 함께 지운 테스트 → MINOR. 같은 클래스의 검증 보조 메서드 호출 · JUnit 4 `@Test(expected)` 도 검증으로 센다 | MAJOR / MINOR |

실행 검증이 켜져 있으면 `EXEC_*` 지적(회귀 · 새 테스트 실패 · 증거 테스트로 증명된 동작 변화 · 테스트가 안 지나간 변경)이 더해진다.
`SEMGREP` · `SONAR_ISSUE` 는 외부 정적 분석기라 같은 곳을 짚은 AI 지적을 근거 확인에서 CONFIRMED 로 올려 준다.

## Semgrep

`SemgrepScanner` 가 리뷰마다 한 번, 바뀐 파일을 base · head 에서 함께 돌려 head 에만 있고 이번 PR 이 추가한 줄에 걸친 결과만 남긴다
(base · head 는 라인 번호가 아니라 걸린 코드로 맞춘다). 그 결과를 `semgrep`(C) 과 `semgrep-quality`(D) 가 용도별로 나눠 가져간다.
테스트 코드 제외, 실행 파일이 없으면 건너뛰고 분석 정보에 남긴다.

| 용도 | 규칙 출처 | 예 |
|---|---|---|
| 보안 | 레지스트리 `p/default` (1,000여 개 중 대부분 보안) | SQL Injection, XSS, 하드코딩된 키, 안전하지 않은 암호화 |
| 버그 패턴 | 레지스트리 `p/r2c-best-practices` 의 correctness | `==` 로 문자열 비교, 항상 참인 조건, 닫지 않은 자원 |
| 모범 사례 | `p/r2c-best-practices` 의 best-practice · 성능 · 유지보수성 | 판정을 막지 않도록 MINOR 까지만 |
| 팀 규칙 | 레포의 `.semgrep.yml` · `.semgrep.yaml` · `.semgrep/**/*.yml` | "컨트롤러에서 Repository 직접 호출 금지", "System.out 대신 Logger" |
| 마이그레이션 | PR Guard 내장 [`semgrep/prguard-rules.yml`](src/main/resources/semgrep/prguard-rules.yml) + 레지스트리 compatibility | `new Integer(5)` → `Integer.valueOf(5)`, `Class.newInstance()`, `new URL(..)`, `javax.*` → `jakarta.*`, `WebSecurityConfigurerAdapter`, `new Buffer(..)` |

- 규칙에 `fix` 가 있으면 지적에 **수정 제안**(바꿀 코드)이 붙는다. 코드를 직접 고치지는 않는다.
- 팀 규칙은 **base 쪽** 파일을 읽는다. PR 이 자기 규칙을 고쳐 검사를 피하지 못하게 하려는 것으로, 새 규칙은 기본 브랜치에 들어간 뒤부터 적용된다.
  규칙 파일이 잘못되면 그 파일만 빼고 다시 검사하고 이유를 분석 정보에 남긴다.
- 팀 규칙 예 (레포 루트 `.semgrep.yml`):

```yaml
rules:
  - id: no-repository-in-controller
    languages: [java]
    severity: WARNING
    message: 컨트롤러는 Repository 를 직접 부르지 않고 Service 를 거친다
    patterns:
      - pattern-inside: |
          @RestController
          class $C { ... }
      - pattern: $REPO.$M(...)
      - metavariable-regex:
          metavariable: $REPO
          regex: (?i)^\w*repository$
```

## SonarCloud

중복률 · 복잡도 · 커버리지 · 코드 냄새 · Quality Gate 를 리뷰에 싣는다 (`EXEC_SONAR`). 레포 주인이 SonarCloud 에 따로 등록하지 않는다:
PR Guard 가 우리 조직(`SONAR_ORGANIZATION`) 아래에 레포마다 public 프로젝트(`prguard_{owner}_{repo}`)를 만들고, PR 코멘트도 PR Guard 가 직접 단다.
누가 스캔하는지는 리뷰마다 정한다 (단계 기록의 `via`).

| 스캔 | 언제 | 커버리지 |
|---|---|---|
| head 러너 (`RUNNER`) | 실행 검증(`EXEC_RUNNER`)이 이 리뷰에서 돌 때. 빌드 · 테스트 뒤에 컴파일 결과와 함께 스캔 | 있음 (JaCoCo) |
| GitHub Actions (`ACTIONS`) | 실행 검증이 없을 때. `.github/workflows/sonar.yml` 이 PR 코드를 빌드 · 실행하지 않고 소스만 스캔 | 없음 |

1. 리뷰 시작: 프로젝트가 없으면 만들고(`api/projects/create`) 스캔을 맡긴다
   - 러너: head 러너가 받아 갈 설정을 메모리에 둔다
   - Actions: 그래프 워크플로와 같은 레포 · ref · 토큰(`GRAPH_DISPATCH_TOKEN`)으로 `sonar.yml` 을 실행한다 (run-name `sonar {headSha}`)
2. 스캔
   - 러너: 빌드 전에 `/api/test-runs/{id}/sonar` 로 설정을 받으면 JaCoCo 에이전트를 붙여 테스트 커버리지를 모은다.
     테스트 · 변이 테스트 뒤 `/sonar/token` 으로 토큰을 **한 번만** 받아 `sonar-scanner` 로 PR 분석(`sonar.pullrequest.*`)을 올리고, 처리 작업 id 를 `report` 에 싣는다
   - Actions: PR 커밋과 base 브랜치를 받아 `/api/sonar/token` 에서 토큰을 **한 번만** 받고(저장소 비밀값을 쓰지 않음), 스캔 뒤 `/api/sonar/result` 로 처리 작업 id 를 보낸다.
     API 는 runId 로 우리 레포의 실행 중인 `sonar.yml` 이고 run-name 이 그 커밋인지 GitHub 에 확인한 뒤에만 받는다
3. 다른 분석이 끝나면: 스캔 결과(Actions 는 `SONAR_SCAN_TIMEOUT` 까지)와 처리(`api/ce/task`)를 기다렸다가 이슈 · 지표 · Quality Gate 를 받는다
   - `SONAR_ISSUE`: 이번 PR 이 추가한 줄에 걸린 이슈만. 취약점(C) · 버그(B)는 BLOCKER/CRITICAL → MAJOR, MAJOR → MINOR, 코드 냄새(D)는 MINOR 까지. Sonar 혼자서 머지를 막지는 않는다
   - `SONAR_GATE`: Quality Gate 실패. 심각도는 `SONAR_GATE_SEVERITY` (기본 Sonar way 는 새 코드 커버리지 80% 를 요구해 자주 실패하므로 기본 MINOR)
   - 단계 기록에 새 코드 커버리지 · 중복률 · 복잡도 등 지표와 SonarCloud 화면 링크

커버리지는 그 실행에서 돈 테스트 기준이다 (영향 테스트 선택이 켜져 있으면 고른 테스트만).
토큰은 PR 코드(빌드 · 테스트)가 다 돈 뒤에 받지만 같은 컨테이너라 완전히 격리되지는 않는다. 분석 전용 조직과 토큰을 따로 쓰고, 토큰을 누가 먼저 가져가면 그 실행은 스캔하지 않는다.

## 열린 PR 간 수정 영역 겹침 분석

[PR Conflict Detector](https://github.com/github-community-projects/pr-conflict-detector)의 MIT 핵심 엔진을
번들해 열린 PR의 동일 파일·수정 영역 겹침과 연결된 PR 묶음을 분석한다.
Python 3.10+ 표준 라이브러리만 필요하며 Docker 이미지에는 Python 3를 포함한다.
출처·고정 커밋·라이선스는 `src/main/resources/prconflict/UPSTREAM.md`와 `LICENSE`에 보존했다.

- **리뷰 연동**: D(위험도) 검사기 `pr-conflict-detector`가 각 리뷰 때 열린 PR을 조회한다.
  겹침은 `CROSS_PR_OVERLAP` MINOR로 저장되고 기존 리뷰 화면·요약 코멘트에 상대 PR 번호/링크를 표시한다.
  이것만으로 머지를 막지 않는다. 표시된 근거는 코드 실행이 아니라 수정 영역의 겹침이다.
- **API**: `GET /api/projects/{id}/pr-conflicts`는 최신 열린 PR을 다시 조회해
  `status`, `checkedAt`, `heads`, `conflicts`, `clusters`, `notes`를 반환한다.
  기존 `/api/**` 인증을 그대로 사용한다. `conflicts[].verified`는 항상 `false`다.
- **좌표**: GitHub compare API로 실제 merge-base를 구한다. 대상 브랜치와 merge-base가 모두 같은
  PR끼리만 비교한다. adapter는 diff 문맥을 제외한 조상 쪽 수정 줄/삽입 지점을 사용하며,
  이름 변경은 기존 파일명으로 연결한다. 같은 작성자의 PR도 비교한다.
- **범위/실패**: PR 상한, 파일 상한(기존 GitHub 클라이언트 최대 300개), 누락된 patch,
  서로 다른 merge-base, 수집 중 바뀐 PR은 `PARTIAL`과 안내문으로 표시한다.
  `COMPLETE`는 수집된 diff 범위를 비교했다는 의미이며 merge 가능/동작 안전을 보장하지 않는다.
  전체 조회/엔진 실패는 API 503으로 반환하고 리뷰에서는 분석 정보에 실패 이유를 남긴다.
  꺼진 기능은 `DISABLED`다.

**이 기능은 텍스트/의미적 충돌의 확정 판정기가 아니다.** upstream의 `verify_conflict()`는 PR 두 개를
함께 병합하지 않으므로 사용하지 않는다. 가상 병합, 함수 호출 관계를 통한 의미적 교차 영향,
결합 빌드/테스트는 별도 기능이다. 삭제·추가·rename의 일부 경계 상황은 겹침 경고가 넓게 잡힐 수 있다.

리뷰에 저장된 결과는 `checkedAt`/head SHA 기준 스냅샷이다. 다른 PR이 변경되어도 과거 리뷰를
소급 수정하지 않는다. 최신 상태는 위 API 또는 리뷰 다시 실행으로 확인한다. 이 PR은 별도
프론트엔드 충돌 전용 화면을 추가하지 않고 기존 지적 화면을 재사용한다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `PR_CONFLICT_ENABLED` | `true` | 리뷰/API의 교차 PR 겹침 분석 활성화 |
| `PR_CONFLICT_PYTHON` | `python3` | Python 3.10+ 실행 파일. Windows 로컬은 `python` 또는 절대 경로 |
| `PR_CONFLICT_TIMEOUT` | `PT30S` | 번들 Python 엔진 실행 제한 시간 |
| `PR_CONFLICT_MAX_PULLS` | `30` | 최신 수정 순 분석 PR 수 상한(최대 100, GitHub 목록 첫 페이지) |

각 분석은 목록 조회 외에 PR마다 비교 기준·변경 파일·최신 head를 읽는 API 호출이 필요하다.
API는 캐시하지 않으므로 polling 용도로 과도하게 호출하지 않는다.
대상 레포의 Python·빌드 스크립트는 실행하지 않는다. 외부 알림 서비스로 전송하지 않으며
엔진은 토큰을 상속하지 않는 별도 프로세스에서 실행한다.

검증: `./gradlew test bootJar`. 테스트에는 실제 번들 Python 엔진 실행이 포함되며,
`PR_CONFLICT_TEST_PYTHON`으로 테스트용 Python 실행 파일을 지정할 수 있다.

## 패키지

| 패키지 | 역할 |
|---|---|
| `github` | GitHub REST 클라이언트, 레포 URL 파싱 |
| `events` | 리뷰 단계 기록(`Stage` · `ReviewEvent`), 저장과 SSE 중계(`ReviewEventBus`, `ReviewEventController`) |
| `ast` | base · head 메서드의 AST diff (GumTree, `AstDiffer`) → 편집 · 위험 신호 |
| `agents` | AI 에이전트 규칙(AGENTS.md): 레포의 규칙 파일 확인, 초안 · PR 생성, 리뷰 지적을 실수 유형 12가지로 묶은 피드백(`LessonService`, `MistakeType`) |
| `project` / `pull` | 프로젝트 등록, PR 상태 저장 |
| `workspace` | 레포 clone, PR 별 base/head worktree, 그래프용 브랜치 worktree, git 실행 |
| `execution` | 실행 검증: base · head 에서 테스트를 실제로 돌려 회귀 찾기 (쿠버네티스 Job · 로컬 docker), 러너 콜백, 증거 테스트 판정(`EvidenceVerdict`), 호출 기록 병합(`Trace`). `deploy/k8s/README.md` |
| `evidence` | 증거 테스트 생성: 바뀐 메서드 고르기, OpenAI(JSON 스키마) 또는 픽스처 생성기. 프롬프트는 `resources/prompts/evidence-system.md` |
| `graph` | 레포 전체 파일 의존성 그래프: 대기열 처리·워크플로 실행(`GraphWorker`), 결과 받기(`GraphController`), 로컬 실행(`GraphBuilder`) |
| `index` | Java 레포 인덱스 (`JavaIndexer`, `RepoIndex`), base↔head 메서드 비교 (`MethodDiff`) |
| `history` | git log 통계 · 동시 변경(co-change) · blame |
| `diff` | GitHub patch 해석 (hunk, 추가·삭제 라인, 라인 코멘트 가능 라인) |
| `analysis` | 지적 사항 모델(`Finding`), 검사기(`Analyzer`, `SnapshotAnalyzer`), 파이프라인, 근거 확인(`FindingGrounder`, `Grounding`), 판정 |
| `analysis.rules` | 결정적 검사 규칙 (위 "결정적 검사" 표) |
| `sonar` | SonarCloud: 프로젝트 만들기 · head 러너용 설정과 일회용 토큰 · 처리 대기 · 이슈 · 지표 · Quality Gate → 지적 (`SonarService`, `SonarClient`) |
| `review` | LLM 리뷰어(`Reviewer`: OpenAI / stats-only), 리뷰 작업 저장 |
| `report` | 요약·라인 코멘트 본문과 게시 |
| `pipeline` | 폴링과 리뷰 워커 |

## 검사 추가하는 법 (A~D)

`Analyzer` 를 구현한 스프링 빈을 `analysis.rules` 에 두면 파이프라인이 자동으로 실행한다. 재료는 모두 `AnalysisContext` 에 있다.

| 재료 | 메서드 |
|---|---|
| PR 제목·본문·커밋 메시지 | `ctx.pull()` |
| 변경 파일 · patch(추가/삭제 라인) | `ctx.files()`, `file.patch()` |
| hunk 를 감싸는 메서드 | `ctx.methodsTouchedBy(file)` |
| base/head 레포 인덱스 | `ctx.baseIndex()`, `ctx.headIndex()` (`callersOf`, `callsByName`, `methodAt`, `hierarchy` …) |
| 바뀐 메서드 (추가·삭제·시그니처·본문·어노테이션) | `ctx.changedMethods()` |
| git 이력 · 동시 변경 | `ctx.history().coChanges(file, minSupport, minConfidence, limit)` |
| 바뀐 라인의 blame | `ctx.blame()` |

레포에 원래 있던 문제를 빼고 이번 PR 이 만든 것만 보고하려면 `SnapshotAnalyzer` 를 상속한다 (base 와 head 를 똑같이 검사해서 head 에만 있는 지적을 남긴다). 같은 문제는 양쪽에서 같은 `anchor` 를 만들어야 한다.

## API

`API_TOKEN` 이 있으면 `/api/**` 는 `X-Api-Token` 헤더가 같은 값이어야 받는다 (없거나 틀리면 401 `API_TOKEN_INVALID`). 화면(pr-guard-web 서버)만 이 토큰을 가진다. 자기 방식으로 확인하는 콜백은 뺀다: 웹훅(HMAC 서명), 러너(`X-Run-Token`), 그래프 워크플로의 progress · result(runId 확인). `/actuator/health` 도 열려 있다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/projects` | 프로젝트 목록 |
| POST | `/api/projects` | `{ "url": "https://github.com/o/r" }` 등록 |
| GET | `/api/projects/{id}` | 프로젝트 |
| DELETE | `/api/projects/{id}` | 삭제 (PR·리뷰 기록 포함) |
| GET | `/api/projects/{id}/pulls` | PR 목록 + 최근 리뷰 상태 |
| POST | `/api/projects/{id}/poll` | 지금 폴링 |
| POST | `/api/github/webhook` | GitHub 웹훅 (`pull_request`, `ping`). `X-Hub-Signature-256` 이 맞아야 받는다. 202 `{outcome, projectId, prNumber, reason}` |
| POST | `/api/projects/{id}/pulls/{number}/reviews` | 현재 커밋 다시 리뷰 (run 증가) |
| GET | `/api/projects/{id}/reviews?limit=20` | 리뷰 기록 (판정, 지적 수) |
| GET | `/api/reviews/{id}` | 리뷰 + 지적 사항(층별 근거 `grounding` 포함) + 분석 재료 요약(`context`) |
| GET | `/api/reviews/{id}/events` | 리뷰 단계 기록. `Accept: text/event-stream` 이면 저장된 기록 뒤에 진행 중 단계를 이어 보낸다(SSE), JSON 이면 한 번에 |
| GET | `/api/projects/{id}/graph` | 레포 의존성 그래프 (상태, 기준 커밋, `graph`: 파일 노드·의존 간선·커뮤니티) |
| POST | `/api/projects/{id}/graph` | 기본 브랜치 최신 커밋으로 그래프 다시 만들기 (202) |
| GET | `/api/projects/{id}/repo` | GitHub 레포 정보 (설명, 언어 구성, 크기 …) — 등록 화면 |
| PATCH | `/api/projects/{id}/settings` | `{ commentEnabled, majorThreshold }` 프로젝트별 PR 코멘트 on/off, 판정 기준 (null = 기본값) |
| POST | `/api/projects/{id}/onboarded` | 등록 화면(온보딩)을 끝냄 |
| POST | `/api/projects/{id}/graph/progress` | 그래프 워크플로가 진행 단계를 알린다 (`{runId, stage, message, data}`) |
| POST | `/api/projects/{id}/graph/result` | 그래프 워크플로가 결과를 보낸다 (`{runId, commitSha, graph}` 또는 `{runId, error}`) |
| GET | `/api/projects/{id}/agents-md` | 레포의 AI 에이전트 규칙 파일 상태와 분석 기반 초안 |
| GET | `/api/projects/{id}/agents-md/lessons` | 리뷰에서 반복된 실수 유형과 제안 규칙 |
| POST | `/api/projects/{id}/agents-md/pull` | `{ content }` 규칙 초안을 대상 레포에 PR 로 올림 |
| GET | `/api/reviews/{id}/test-runs` | 리뷰의 실행 검증 기록 (base · head) |
| GET | `/api/reviews/{id}/runtime` | 리뷰의 런타임 호출 기록 `{base, head}` |
| GET | `/api/projects/{id}/runtime` | 프로젝트의 가장 최근 head 호출 기록 (코드 그래프 · 시티 오버레이). 없으면 204 |
| GET | `/api/test-runs/{id}/extra-files` | 러너 전용 (`X-Run-Token`). 증거 테스트: 200 파일 · 202 만드는 중 · 204 없음 |
| GET | `/api/test-runs/{id}/mutation` | 러너 전용 (`X-Run-Token`, head 만). 변이 계획: 200 `timeout · class · test` 줄 · 202 고르는 중 · 204 없음 |
| GET | `/api/test-runs/{id}/sonar` | 러너 전용 (`X-Run-Token`, head 만). SonarCloud 분석 설정: 200 `키 TAB 값` 줄 · 204 하지 않음 |
| GET | `/api/test-runs/{id}/sonar/token` | 러너 전용 (`X-Run-Token`, head 만). 스캔 직전 한 번만 주는 SonarCloud 토큰. 이미 가져갔으면 204 |
| POST | `/api/sonar/token` · `/api/sonar/result` | SonarCloud 스캔 워크플로 전용 (`{runId, headSha}` 를 GitHub 에 확인). 토큰 한 번 · 처리 작업 id 또는 실패 이유 |
| GET | `/api/test-runs/{id}/selection` | 러너 전용 (`X-Run-Token`). 돌릴 테스트: 200 한 줄에 하나씩 테스트 클래스 · 202 고르는 중 · 204 전체 |
| POST | `/api/test-runs/{id}/progress` · `/report` | 러너 전용 (`X-Run-Token`). 진행 단계, JUnit 보고서 · 빌드 로그 · 호출 기록 |

오류 코드: `INVALID_REPO_URL`(400), `REPO_NOT_PUBLIC`(400), `REPO_NOT_FOUND`(404), `PROJECT_NOT_FOUND`(404), `PULL_NOT_FOUND`(404), `REVIEW_NOT_FOUND`(404), `GRAPH_NOT_FOUND`(404), `GRAPH_RUN_INVALID`(403), `INVALID_SETTINGS`(400), `PROJECT_EXISTS`(409), `GRAPH_NOT_RUNNING`(409), `PULL_CLOSED`(409), `GITHUB_ERROR`(502), `WEBHOOK_DISABLED`(404), `WEBHOOK_SIGNATURE_INVALID`(401), `WEBHOOK_INVALID`(400), `API_TOKEN_INVALID`(401)

## 평가

`eval/` 에 평가용 PR 세트(sandbox #2~#24)의 정답표와 채점 스크립트가 있다.

- 문제 PR 16개 (정답 지적 20개) · 정상 PR 7개. 문제는 AI 가 쓴 코드에서 자주 보이는 실수 유형(요청하지 않은 변경 · 빠진 구현 · 조용한 동작 변경 · 깨지는 호출부 · 레포 맥락 무시 · 보안 회귀 · 환각 의존성 · 스키마 불일치 · 테스트 약화)으로 나눴다.
- #10~#24 의 문제 PR 은 모두 컴파일되고 기존 테스트를 통과한다. 테스트 통과만으로는 머지 여부를 판단할 수 없는 PR 들이다.
- 채점: 탐지율, 판정 적절, 정답표 밖 BLOCKER/MAJOR(오탐 후보), 병목 지표(정상 PR 자동 통과 · 문제 PR 누락 · 리뷰 소요 시간), 유형별 탐지, 계층별 기여(정적 · 실행 · AI 중 어디서 잡았나).

```bash
# 기준선: diff 만 LLM 에 넣는 리뷰. baseline.json 에 없는 PR 만 리뷰해서 덧붙인다
OPENAI_API_KEY=... node eval/baseline.mjs
node eval/score.mjs --baseline eval/baseline.json

# PR Guard: 배포된 API 의 최신 리뷰 (특정 차수만 보려면 --run N)
API_TOKEN=... node eval/score.mjs --api https://pr-guard-api-production.up.railway.app --project 1
```

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | localhost / 5432 / prguard / prguard / prguard | Railway Postgres 를 붙이면 자동 주입 |
| `API_TOKEN` | (없음) | 있으면 `/api/**` 에 `X-Api-Token` 이 이 값이어야 한다. pr-guard-web 의 `API_TOKEN` 과 같은 값 |
| `GITHUB_TOKEN` | (없음) | 코멘트를 다는 봇 계정 PAT. 없으면 읽기만 한다 (rate limit 60/h) |
| `GITHUB_COMMENT_ENABLED` | `true` | `false` 면 토큰이 있어도 코멘트를 달지 않는다 (로컬 개발용) |
| `OPENAI_API_KEY` | (없음) | 없으면 LLM 리뷰 없이 정적 분석만 |
| `OPENAI_MODEL` | `gpt-4o-mini` | Responses API 모델 (리뷰 · 증거 테스트) |
| `POLL_INTERVAL` | `PT5M` | 폴링 주기 (ISO-8601) |
| `GITHUB_WEBHOOK_SECRET` | (없음) | 있으면 `POST /api/github/webhook` 을 받는다. 레포(또는 조직) Settings → Webhooks 에 Payload URL `{API}/api/github/webhook`, Content type `application/json`, 같은 Secret, 이벤트 "Pull requests" 로 등록 |
| `REVIEW_WORKER_DELAY` | `PT10S` | 대기 중인 리뷰를 집는 간격 |
| `REVIEW_MAX_PATCH_CHARS` | `60000` | 리뷰어에 넘기는 patch 총 길이 상한 (문자 수) |
| `WEB_URL` | `https://pr-guard-web.vercel.app` | PR 코멘트의 "분석 과정 보기" 링크 대상 |
| `WORKSPACE_DIR` | `{tmp}/prguard` | clone·worktree 위치 |
| `WORKSPACE_MAX_REPO_KB` | `300000` | 이보다 큰 레포는 clone 하지 않고 diff 만 분석 |
| `EXEC_RUNNER` | `none` | 실행 검증. `kubernetes` · `docker` 면 켜진다 (설정은 `deploy/k8s/README.md`) |
| `EXEC_CALLBACK_URL` | `http://localhost:8080` | 러너가 진행 · 결과를 보낼 이 API 주소 (러너 쪽에서 닿는 주소) |
| `EXEC_IMAGE` | `ghcr.io/dongguk-creative-fusion-2026/pr-guard-test-runner:latest` | 러너 이미지 (`runner/`) |
| `EXEC_NAMESPACE` · `EXEC_RUNTIME_CLASS` | `prguard-runs` · (없음) | 쿠버네티스 Job 네임스페이스, 격리 런타임 (예: gvisor) |
| `EXEC_CPU` · `EXEC_MEMORY` · `EXEC_TIMEOUT` | `2` · `3Gi` · `PT15M` | 러너 하나의 자원과 제한 시간 |
| `EXEC_CACHE` · `EXEC_CACHE_PVC` | `false` · `prguard-runner-cache` | 러너 의존성 캐시(Gradle · Maven). 프로젝트마다 다른 칸, base 는 읽기 · 쓰기, head(PR 코드)는 읽기 전용. 쿠버네티스는 PVC(`deploy/k8s/runner-cache.yaml`), docker 러너는 이름 붙은 볼륨 |
| `EVIDENCE_ENABLED` | `true` | 실행 검증이 켜져 있을 때 증거 테스트를 만들지 (`OPENAI_API_KEY` 필요) |
| `EXEC_TEST_SELECTION` | `on` | 영향 테스트 선택. `on`: 영향 있는 테스트만, `shadow`: 전체를 돌리며 놓친 실패 확인, `off`: 늘 전체 |
| `EXEC_MUTATION` · `EXEC_MUTATION_TIMEOUT` | `true` · `PT6M` | 변경분 변이 테스트를 할지, 러너에서 PIT 에 주는 시간 |
| `EXEC_SELECTION_MAX_AGE` | `P14D` | 기준(전체 실행 기록)이 이보다 오래되면 전체를 돌린다 |
| `EVIDENCE_FIXTURE_DIR` | (없음) | 있으면 LLM 대신 `{dir}/{owner}/{repo}/{PR 번호}/` 아래 미리 써 둔 테스트를 쓴다 (로컬 · 시연) |
| `SEMGREP_ENABLED` · `SEMGREP_BIN` | `true` · `semgrep` | Semgrep 검사. API 이미지에 들어 있고, 로컬에서는 `pip install semgrep` 해야 돈다 (없으면 건너뜀) |
| `SEMGREP_CONFIGS` | `p/default,p/r2c-best-practices` | Semgrep 레지스트리 규칙. 레지스트리 이름 또는 규칙 파일 경로, 쉼표로 여러 개 (예: `p/java,p/owasp-top-ten`) |
| `SEMGREP_BUNDLED` · `SEMGREP_REPO_RULES` | `true` · `true` | PR Guard 마이그레이션 규칙 · 레포 팀 규칙(`.semgrep.yml` · `.semgrep/`)도 함께 쓴다 |
| `SEMGREP_TIMEOUT` | `PT3M` | Semgrep 한 번 실행의 제한 시간 |
| `SONAR_ENABLED` | `false` | SonarCloud 분석. 실행 검증이 있으면 head 러너가, 없으면 GitHub Actions 가 스캔 |
| `SONAR_SCAN_TIMEOUT` | `PT15M` | Actions 스캔이 결과를 보내기를 기다리는 시간 |
| `SONAR_ORGANIZATION` · `SONAR_TOKEN` | (없음) | 프로젝트를 만들 조직 키, 그 조직에 프로젝트를 만들고 분석을 올릴 수 있는 토큰 |
| `SONAR_URL` · `SONAR_TIMEOUT` | `https://sonarcloud.io` · `PT5M` | SonarCloud 주소, 올린 분석의 처리를 기다리는 시간 |
| `SONAR_GATE_SEVERITY` | `MINOR` | Quality Gate 실패를 남길 심각도 (`MAJOR` 면 "수정 후 머지") |
| `GRAPH_RUNNER` | `actions` | 그래프를 만드는 곳. `actions`: GitHub Actions, `local`: 이 서버 (아래 `GRAPH_NODE` 등 사용) |
| `GRAPH_DISPATCH_TOKEN` | (`GITHUB_TOKEN`) | pr-guard-api 레포에 Actions 쓰기 권한이 있는 토큰. 워크플로 실행에 쓴다 |
| `GRAPH_NODE` | `node` | local: node (22.18+ 또는 24.11+) |
| `GRAPH_SCRIPT` | `gitnexus/export.mjs` | local: 그래프 생성 스크립트 |
| `GRAPH_TIMEOUT` | `PT15M` | local: 그래프 하나의 제한 시간 |
| `GRAPH_HEAP_MB` | `2048` | local: GitNexus 분석 프로세스 힙 |
| `PORT` | 8080 | Railway 가 넣어 준다 |

## 로컬 실행

```bash
docker compose up -d
GITHUB_COMMENT_ENABLED=false ./gradlew bootRun
```

git 이 PATH 에 있어야 한다. 로컬에서는 결과를 받을 공개 주소가 없으니 그래프를 보려면 `GRAPH_RUNNER=local` 로 띄운다 (`cd gitnexus && npm ci`, node 필요). 그래프 워크플로는 레포 Variables 의 `PR_GUARD_API_URL` 로 결과를 보낸다. 의존성 그래프는 [GitNexus](https://github.com/abhigyanpatwari/GitNexus) (PolyForm Noncommercial 라이선스, 비상업 용도로만 사용) 로 만든다. 스키마는 기동 시 Flyway 가 만든다 (`src/main/resources/db/migration`).

## Railway 배포

1. Railway 에서 New Project → Deploy from GitHub repo → `pr-guard-api` (루트의 `Dockerfile`, `railway.json` 사용)
2. 같은 프로젝트에 Postgres 추가 → 서비스 Variables 에 `PGHOST=${{Postgres.PGHOST}}` 식으로 `PG*` 5개 참조
3. `GITHUB_TOKEN`, `OPENAI_API_KEY` 설정
4. Settings → Networking → Generate Domain. 이 주소를 pr-guard-web 의 `API_BASE_URL` 로 쓴다
