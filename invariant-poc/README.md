# Hypothesis 불변식 검증 — GitHub Actions

배포된 PR Guard API가 GitHub Actions를 실행하고 결과를 수집한다. 사용자 PC와 API 서버에는 컨테이너 엔진, Kubernetes, Python 또는 JDK 테스트 도구가 필요하지 않다. 기존 Java API 런타임과 PostgreSQL은 계속 사용한다.

## 실행 흐름

1. PR 화면에서 문장을 입력해 규칙 후보를 만든다. 기존 OpenAI API 설정을 재사용한다.
2. 사용자가 조건을 확인하고 실행 버튼을 누르면 서버가 현재 PR의 head와 merge-base SHA를 고정한다.
3. 서버가 `.github/workflows/invariant-check.yml`을 `workflow_dispatch`로 요청한다.
4. GitHub의 표준 `ubuntu-latest` 러너가 Python·Hypothesis·Java 21을 준비하고 테스트를 실행한다.
5. 결과 JSON만 artifact에 1일 보관한다. API가 30초 간격으로 GitHub에서 완료 상태와 결과를 가져와 DB에 저장한다.
6. PR 화면에서 대기·실행 상태, Actions 링크, 반례와 결과를 확인한다.

콜백용 서버 주소나 서버 API 토큰을 Actions에 전달하지 않는다. 결과 수집 시 워크플로 파일·브랜치·실행 UUID·실행 차수·커밋·결과 형식과 크기를 확인한다. 사용자가 확인한 결과도 악의적인 PR 코드에 대한 보안 증명으로 간주하지 않는다.

## 배포 설정

먼저 **워크플로와 runner 파일을 기본 브랜치에 반영**해야 한다. GitHub는 기본 브랜치에 등록된 `workflow_dispatch` 워크플로만 수동 실행할 수 있다. 이 단계 전에 기능을 활성화하면 실행 요청이 실패한다.

API의 환경 변수:

| 변수 | 값·용도 |
|---|---|
| `INVARIANT_EXEC_ENABLED` | 기본 `false`. 워크플로 등록·토큰 권한 확인 후 `true` |
| `INVARIANT_WORKFLOW_REPO` | 기본 `dongguk-creative-fusion-2026/pr-guard-api` |
| `INVARIANT_WORKFLOW_REF` | 기본 `main`. 신뢰하는 워크플로 브랜치 이름 |
| `GITHUB_TOKEN` | 기존 토큰 재사용. 워크플로 저장소의 Actions 읽기/쓰기와 분석 대상 PR·코드 읽기 권한 필요 |
| `OPENAI_API_KEY` / `OPENAI_MODEL` | 기존 규칙 해석 설정 |

기존 배포 API 이미지에 실행 도구를 추가할 필요가 없다. DB 마이그레이션 V18이 Actions 식별 정보와 동시 실행 제한 인덱스를 추가한다. 실행 중인 서버를 재시작해도 DB에서 Actions 상태를 이어서 수집한다.

## 비용과 제한

- 표준 Ubuntu 러너를 사용한다. 공개 저장소의 표준 호스팅 러너 컴퓨팅은 GitHub 정책상 무료이며 실제 청구와 한도는 조직 설정을 따른다.
- 저비용 시작을 위해 전체 프로젝트에서 대기·실행 작업을 합쳐 1개만 허용한다. DB 인덱스로 API 여러 인스턴스 간 중복 실행도 제한한다.
- 작업당 최대 15분. API는 대기 포함 30분을 넘으면 실패 처리하고 확인된 Actions 실행은 취소 요청한다.
- 64KB 이하 결과만 채택하고 artifact는 1일만 보관한다. 빌드 로그·대형 파일·실행 이미지·영구 빌드 캐시를 업로드하지 않는다.
- PR 코드는 일회용 GitHub 호스팅 환경에서 실행한다. 저장소 checkout 자격 증명을 작업 디렉터리에 남기지 않고, OpenAI 키·배포 서버 토큰은 전달하지 않는다.
- 실제 OpenAI 호출 비용은 별도다.

## 현재 검증 범위

실행 어댑터는 `dongguk-creative-fusion-2026/pr-guard-sandbox`의 **게시글 수정 전후 작성자 ID 보존** 규칙을 지원한다. LLM이 추가 조건·예외 없이 해당 규칙으로 해석한 경우에만 알려진 템플릿을 선택한다. 다른 업무 규칙은 검토 후보로 표시하며 실행하지 않는다. 생성된 임의 코드나 표현식을 평가하지 않는다.

대상 PR의 실제 Spring `PostService.update`, JPA와 H2 PostgreSQL 호환 모드 DB를 사용한다. 입력마다 새 트랜잭션과 rollback으로 상태를 초기화한다. 테스트 대상 DB가 실제 PostgreSQL인 것은 아니며 HTTP 인증도 검증하지 않는다.

Hypothesis는 1~20자 영숫자 제목·내용을 최대 100개 탐색 예산으로 생성한다. head에서 반례를 찾으면 축소된 입력을 base/head에 두 번씩 재생한다. 반례를 찾지 못하면 base 재생은 생략한다. 결과는 신규 위반·기존 위반·불안정·반례 미발견·실행 실패로 구분한다. 반례 미발견은 안전성 증명이 아니고 결과는 머지 판정에 반영하지 않는다.

## API

- `POST /api/projects/{id}/pulls/{number}/invariant-settings/compile`: `{rules}`를 `{engine,candidates}`로 해석한다. 키가 없으면 지원 문장에 대한 기본 템플릿 해석임을 표시한다.
- `POST /api/projects/{id}/pulls/{number}/invariant-runs`: 확인한 후보를 받아 실행 ID와 `QUEUED` 상태를 반환한다.
- `GET /api/projects/{id}/pulls/{number}/invariant-runs/{runId}`: 상태·커밋·Actions 링크·결과를 반환한다.

결과 파일이 없거나 만료·취소·업로드 실패인 경우 실패로 처리한다. GitHub의 일시적 오류는 마감 시간까지 재시도한다. 재실행 차수가 바뀐 기존 Actions 실행을 재사용하지 않으며, 새 API 요청으로 검증을 다시 시작해야 한다.

## 개발 검증 — 선택 사항

배포 기능 사용자는 아래 명령을 실행할 필요가 없다. 빠른 테스트는 Python과 Hypothesis만 있으면 된다.

```sh
python -m pip install -r invariant-poc/requirements.txt
python -m unittest discover -s invariant-poc -v
```

실제 러너 회귀 테스트는 JDK 21과 Python·Hypothesis를 사용한다. 컨테이너 엔진 없이 임시 복제본에서 실행하며 원본 샌드박스를 바꾸지 않는다.

```sh
python invariant-poc/runner/integration_test.py --sandbox ../pr-guard-sandbox
```

`runner/api_integration_test.py`는 모의 LLM·GitHub Actions HTTP와 실제 API·PostgreSQL을 사용하는 개발용 테스트다. 전용 API를 18080 포트에서 실행하고 OpenAI URL을 `http://localhost:18181/v1`, 모델을 `integration-model`, 키를 테스트용 임의 값, GitHub API URL을 `http://localhost:18181/github`, `INVARIANT_POLL_INTERVAL=PT2S`로 설정한다. 실제 호스팅 Actions 실행을 대신 증명하는 테스트는 아니다.
