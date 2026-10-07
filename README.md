# pr-guard-api

PR Guard 백엔드. 등록된 public GitHub 레포의 열린 PR 을 주기적으로 조회하고, 새 커밋이 보이면 **diff · 레포 전체 · git 이력 · PR 설명**을 함께 분석해 PR 에 리뷰를 남긴다.

- 프론트엔드: [pr-guard-web](https://github.com/dongguk-creative-fusion-2026/pr-guard-web)
- 테스트·평가용 레포: [pr-guard-sandbox](https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox)

## 흐름

```
POST /api/projects {url}
  └ URL 파싱 → GET /repos/{o}/{r} 로 public 확인 → projects 저장 → repo_graphs 에 PENDING

GraphWorker (10초마다) — PENDING 그래프 하나를 집어서
  └ GitHub Actions 워크플로(.github/workflows/repo-graph.yml) 실행 → RUNNING
     └ 워크플로: 기본 브랜치 clone → GitNexus 인덱싱 (gitnexus/export.mjs) → 파일 단위 그래프를
        POST /api/projects/{id}/graph/result 로 보냄 (API 는 runId 로 우리 워크플로 실행인지 GitHub 에 확인)
  (GitNexus 가 메모리를 1GB 가까이 써서 Railway 컨테이너 대신 Actions 에서 돌린다. GRAPH_RUNNER=local 이면 이 서버에서 직접)

PollScheduler (POLL_INTERVAL 마다) / POST /api/projects/{id}/poll
  └ GET /pulls?state=open (ETag, 변경 없으면 304)
     └ head SHA 가 처음 보이면 reviews 에 PENDING 생성

ReviewWorker (10초마다) — PENDING 하나를 집어서
  ├ 수집: PR 본문 · 커밋 메시지 · 변경 파일과 patch
  ├ AnalysisPipeline
  │   ├ RepoWorkspace   레포 clone → base(merge-base) / head worktree
  │   ├ JavaIndexer     양쪽 소스 인덱스 (타입 · 메서드 · 어노테이션 · 호출 관계) → 바뀐 메서드
  │   ├ GitHistoryLoader 최근 커밋 이력 · 동시 변경 통계 · 바뀐 라인의 blame
  │   ├ Analyzer 들     결정적 검사 (base 에도 있던 문제는 제외)
  │   ├ Reviewer        LLM 리뷰 (JSON 스키마 응답, 구조 정보 함께 전달)
  │   └ VerdictPolicy   판정 계산 (BLOCKER ≥1 → 머지 비권장, MAJOR ≥1 → 수정 후 머지)
  ├ 저장: findings, 판정, 분석 재료 요약(context)
  └ PR: 요약 코멘트 1개(계속 수정) + 라인 코멘트(같은 지적은 한 번만)
```

단계가 실패해도 가능한 데까지 진행한다. 예를 들어 clone 이 실패하면 diff 만으로 리뷰하고, 이유는 요약 코멘트의 "분석 정보"에 남는다.

## 패키지

| 패키지 | 역할 |
|---|---|
| `github` | GitHub REST 클라이언트, 레포 URL 파싱 |
| `project` / `pull` | 프로젝트 등록, PR 상태 저장 |
| `workspace` | 레포 clone, PR 별 base/head worktree, 그래프용 브랜치 worktree, git 실행 |
| `execution` | 실행 검증: base · head 에서 테스트를 실제로 돌려 회귀 찾기 (쿠버네티스 Job · 로컬 docker), 러너 콜백. `deploy/k8s/README.md` |
| `graph` | 레포 전체 파일 의존성 그래프: 대기열 처리·워크플로 실행(`GraphWorker`), 결과 받기(`GraphController`), 로컬 실행(`GraphBuilder`) |
| `index` | Java 레포 인덱스 (`JavaIndexer`, `RepoIndex`), base↔head 메서드 비교 (`MethodDiff`) |
| `history` | git log 통계 · 동시 변경(co-change) · blame |
| `diff` | GitHub patch 해석 (hunk, 추가·삭제 라인, 라인 코멘트 가능 라인) |
| `analysis` | 지적 사항 모델(`Finding`), 검사기(`Analyzer`, `SnapshotAnalyzer`), 파이프라인, 판정 |
| `analysis.rules` | 검사 규칙. 지금은 레포 인덱스 기반 참조 검사(`ReferenceCheckAnalyzer`) |
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

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/projects` | 프로젝트 목록 |
| POST | `/api/projects` | `{ "url": "https://github.com/o/r" }` 등록 |
| GET | `/api/projects/{id}` | 프로젝트 |
| DELETE | `/api/projects/{id}` | 삭제 (PR·리뷰 기록 포함) |
| GET | `/api/projects/{id}/pulls` | PR 목록 + 최근 리뷰 상태 |
| POST | `/api/projects/{id}/poll` | 지금 폴링 |
| POST | `/api/projects/{id}/pulls/{number}/reviews` | 현재 커밋 다시 리뷰 (run 증가) |
| GET | `/api/projects/{id}/reviews?limit=20` | 리뷰 기록 (판정, 지적 수) |
| GET | `/api/reviews/{id}` | 리뷰 + 지적 사항 + 분석 재료 요약(`context`) |
| GET | `/api/projects/{id}/graph` | 레포 의존성 그래프 (상태, 기준 커밋, `graph`: 파일 노드·의존 간선·커뮤니티) |
| POST | `/api/projects/{id}/graph` | 기본 브랜치 최신 커밋으로 그래프 다시 만들기 (202) |
| GET | `/api/projects/{id}/repo` | GitHub 레포 정보 (설명, 언어 구성, 크기 …) — 등록 화면 |
| PATCH | `/api/projects/{id}/settings` | `{ commentEnabled, majorThreshold }` 프로젝트별 PR 코멘트 on/off, 판정 기준 (null = 기본값) |
| POST | `/api/projects/{id}/onboarded` | 등록 화면(온보딩)을 끝냄 |
| POST | `/api/projects/{id}/graph/progress` | 그래프 워크플로가 진행 단계를 알린다 (`{runId, stage, message, data}`) |
| POST | `/api/projects/{id}/graph/result` | 그래프 워크플로가 결과를 보낸다 (`{runId, commitSha, graph}` 또는 `{runId, error}`) |

오류 코드: `INVALID_REPO_URL`(400), `REPO_NOT_PUBLIC`(400), `REPO_NOT_FOUND`(404), `PROJECT_NOT_FOUND`(404), `PULL_NOT_FOUND`(404), `REVIEW_NOT_FOUND`(404), `GRAPH_NOT_FOUND`(404), `GRAPH_RUN_INVALID`(403), `INVALID_SETTINGS`(400), `PROJECT_EXISTS`(409), `GRAPH_NOT_RUNNING`(409), `PULL_CLOSED`(409), `GITHUB_ERROR`(502)

## 평가

`eval/` 에 평가용 PR 세트(sandbox #2~#9)의 정답표와 채점 스크립트가 있다.

```bash
# 기준선: diff 만 LLM 에 넣는 리뷰 (분석 파이프라인 도입 전 결과를 저장해 둠)
node eval/score.mjs --baseline eval/baseline.json

# PR Guard: 배포된 API 의 최신 리뷰 (특정 차수만 보려면 --run N)
node eval/score.mjs --api https://pr-guard-api-production.up.railway.app --project 1
```

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | localhost / 5432 / prguard / prguard / prguard | Railway Postgres 를 붙이면 자동 주입 |
| `GITHUB_TOKEN` | (없음) | 코멘트를 다는 봇 계정 PAT. 없으면 읽기만 한다 (rate limit 60/h) |
| `GITHUB_COMMENT_ENABLED` | `true` | `false` 면 토큰이 있어도 코멘트를 달지 않는다 (로컬 개발용) |
| `OPENAI_API_KEY` | (없음) | 없으면 LLM 리뷰 없이 정적 분석만 |
| `OPENAI_MODEL` | `gpt-5-mini` | Responses API 모델 |
| `POLL_INTERVAL` | `PT5M` | 폴링 주기 (ISO-8601) |
| `WORKSPACE_DIR` | `{tmp}/prguard` | clone·worktree 위치 |
| `WORKSPACE_MAX_REPO_KB` | `300000` | 이보다 큰 레포는 clone 하지 않고 diff 만 분석 |
| `EXEC_RUNNER` | `none` | 실행 검증. `kubernetes` · `docker` 면 켜진다 (설정은 `deploy/k8s/README.md`) |
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
