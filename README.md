# pr-guard-api

PR Guard 백엔드. 등록된 public GitHub 레포의 열린 PR 을 주기적으로 조회하고, 새 커밋이 보이면 리뷰를 만들어 PR 에 요약 코멘트로 남긴다.

- 프론트엔드: [pr-guard-web](https://github.com/dongguk-creative-fusion-2026/pr-guard-web)
- 테스트용 레포: [pr-guard-sandbox](https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox)

## 흐름

```
POST /api/projects {url}
  └ URL 파싱 → GET /repos/{o}/{r} 로 public 확인 → projects 저장

PollScheduler (POLL_INTERVAL 마다) / POST /api/projects/{id}/poll
  └ GET /pulls?state=open (ETag, 변경 없으면 304)
     └ PR upsert, 목록에서 사라진 PR 은 closed
     └ head SHA 가 처음 보이면 reviews 에 PENDING 생성 (같은 PR 의 이전 PENDING 은 SUPERSEDED)

ReviewWorker (10초마다)
  └ PENDING 하나를 RUNNING 으로 집음 (FOR UPDATE SKIP LOCKED)
     └ PR 이 닫혔거나 새 커밋이 있으면 SUPERSEDED
     └ GET /pulls/{n}/files → Reviewer.review() → SummaryRenderer → CommentPublisher
        (PR 당 코멘트 하나, 이후엔 같은 코멘트를 수정)
```

## 패키지

| 패키지 | 역할 |
|---|---|
| `github` | GitHub REST 클라이언트, 레포 URL 파싱 |
| `project` | 프로젝트 등록·조회·삭제 |
| `pull` | PR 상태 저장·조회 |
| `review` | `Reviewer` 인터페이스와 구현(OpenAI, stats-only), 리뷰 작업 저장 |
| `report` | PR 요약 코멘트 본문 생성·게시 |
| `pipeline` | 폴링과 리뷰 워커 (위 모듈을 엮는 곳) |
| `common` | 오류 응답 `{timestamp, code, message}` |

리뷰 방식은 `Reviewer` 구현을 바꿔 끼우고, 리뷰 템플릿은 `src/main/resources/prompts/review-system.md` 에서 고친다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/projects` | 프로젝트 목록 |
| POST | `/api/projects` | `{ "url": "https://github.com/o/r" }` 등록 |
| GET | `/api/projects/{id}` | 프로젝트 |
| DELETE | `/api/projects/{id}` | 삭제 (PR·리뷰 기록 포함) |
| GET | `/api/projects/{id}/pulls` | PR 목록 + 최근 리뷰 상태 |
| GET | `/api/projects/{id}/reviews?limit=20` | 리뷰 기록 |
| POST | `/api/projects/{id}/poll` | 지금 폴링 |
| GET | `/api/reviews/{id}` | 리뷰 하나 |

오류 코드: `INVALID_REPO_URL`(400), `REPO_NOT_PUBLIC`(400), `REPO_NOT_FOUND`(404), `PROJECT_NOT_FOUND`(404), `PROJECT_EXISTS`(409), `GITHUB_ERROR`(502)

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | localhost / 5432 / prguard / prguard / prguard | Railway Postgres 를 붙이면 자동 주입 |
| `GITHUB_TOKEN` | (없음) | 코멘트를 다는 봇 계정 PAT. 없으면 읽기만 하고 코멘트는 안 단다 (rate limit 60/h) |
| `OPENAI_API_KEY` | (없음) | 없으면 변경 통계만 남기는 stats-only 리뷰어 |
| `OPENAI_MODEL` | `gpt-5-mini` | Responses API 모델 |
| `POLL_INTERVAL` | `PT5M` | 폴링 주기 (ISO-8601) |
| `PORT` | 8080 | Railway 가 넣어 준다 |

## 로컬 실행

```bash
docker compose up -d
./gradlew bootRun
```

스키마는 기동 시 Flyway 가 만든다 (`src/main/resources/db/migration`).

## Railway 배포

1. Railway 에서 New Project → Deploy from GitHub repo → `pr-guard-api` (루트의 `Dockerfile`, `railway.json` 사용)
2. 같은 프로젝트에 Postgres 추가 → 서비스 Variables 에 `PGHOST=${{Postgres.PGHOST}}` 식으로 `PG*` 5개 참조
3. `GITHUB_TOKEN`, `OPENAI_API_KEY` 설정
4. Settings → Networking → Generate Domain. 이 주소를 pr-guard-web 의 `API_BASE_URL` 로 쓴다
