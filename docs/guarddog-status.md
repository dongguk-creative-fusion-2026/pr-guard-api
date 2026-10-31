# GuardDog 3단계: 검사 상태와 판정

## API 계약

`GET /api/reviews/{id}`의 기존 findings 형식은 유지하고 `context.guarddog`에 구조화된 검사 상태를
추가했다. 같은 객체는 CHECK_C/VERDICT 이벤트의 data.guarddog에도 저장된다. context는 기존
JSONB 컬럼을 사용하므로 DB 마이그레이션이 필요하지 않다.

| status | 의미 | 최종 판정에 미치는 영향 |
|---|---|---|
| DISABLED | 설정에서 검사 꺼짐 | 기존 finding 기준 |
| NO_CHANGES | 지원 범위 내 새로 검사할 직접 의존성 없음 | 기존 finding 기준 |
| COMPLETED | 수집한 신규·변경 패키지 모두 검사 완료 | 실제 위험 finding 기준 |
| PARTIAL | 검사 결과가 있지만 일부 실패·생략·파일 오류가 남음 | 최소 NEEDS_CHANGES |
| FAILED | 검사를 완료한 결과가 없거나 소스를 읽지 못함 | 최소 NEEDS_CHANGES |

`scope=NEW_DIRECT_NPM_PYPI`는 이번 PR에서 추가되거나 버전이 바뀐 npm/PyPI **직접 의존성**만 뜻한다.
NO_CHANGES를 전체 레포·모든 패키지 생태계의 안전 확인으로 해석하지 않는다.

packages는 이름·버전 단위로 묶는다. 같은 패키지가 여러 manifest에 추가되면 한 번 검사하고
files에 모든 해당 경로를 넣는다. 기존 findings에는 파일별 지적을 유지한다.

| package.status | 의미 |
|---|---|
| COMPLETED | 해당 이름·버전의 검사 응답이 정상이며 형식·검사 오류 없음 |
| PARTIAL | 유효한 결과와 개별 규칙 오류/일부 잘못된 근거가 함께 있음 |
| FAILED | 프로세스·다운로드·JSON 오류 등으로 검증하지 못함 |
| SKIPPED | 버전 미확정·지원하지 않는 선언·한도·중단 등으로 실행하지 못함 |

패키지 항목은 ecosystem, name, version, files, status, risks, problems를 제공한다. 알 수 없는
이름·버전은 null이다. risks에는 규칙 ID, GuardDog 심각도(low/medium/high), 설명이 있다.
problems는 원인 코드와 사용자용 설명을 제공한다. 원문 URL, URL 자격 증명, 전체 manifest나
원시 subprocess 오류 출력은 API에 넣지 않는다.

counts에는 수집된 패키지 수(total), 완료(completed), 부분 결과(partial), 실패(failed),
건너뜀(skipped), 위험 근거 수(risks)가 들어간다. 파일 비교/소스 오류는 최상위 problems에
남기므로 total=0이어도 FAILED일 수 있다. 모두 SKIPPED인 경우도 FAILED이며 failed=0일 수 있다.
requiresAttention은 PARTIAL/FAILED에만 true다. durationMs는 검사에 사용한 시간이다.

## 판정 정책

1. 실제 BLOCKER가 있으면 NOT_RECOMMENDED를 유지한다.
2. 프로젝트의 MAJOR 기준을 만족하면 NEEDS_CHANGES를 유지한다.
3. GuardDog가 PARTIAL/FAILED이면 지적이 0개이거나 MAJOR 기준이 높아도 NEEDS_CHANGES를 반환한다.
4. 그 외에는 기존 정책을 따른다. GuardDog가 꺼져 있으면 검사 성공으로 표시하지 않는다.

검사 실패를 악성 패키지로 단정하거나 BLOCKER/MAJOR finding을 만들어 채우지 않는다.
이 경우 NEEDS_CHANGES는 **실패 원인 확인·재검사 필요**를 뜻한다. 리뷰 작업은 DONE으로
완료되며 다른 분석 결과도 제공한다. 요약 코멘트 상단과 VERDICT 이벤트의 reason에 미완료 이유를
설명한다. GitHub 브랜치 보호 설정을 자동으로 변경하지 않는다.

## 원인 코드

| code | 의미 |
|---|---|
| SOURCE_UNAVAILABLE | 의존성 변경은 있으나 checkout 소스가 없음 |
| MANIFEST_ERROR | 기준/변경 후 manifest를 읽거나 비교할 수 없음 |
| UNSUPPORTED_SPEC | 고정 버전으로 해석할 수 없는 새 선언 |
| DISCOVERY_LIMIT | 결과 한도 초과로 전체 대상을 수집하지 못함 |
| PACKAGE_LIMIT | 설정된 패키지 검사 수 한도 초과 |
| TOTAL_TIMEOUT | 전체 검사/목록 수집 시간 한도 초과 |
| TIMEOUT | 개별 실행 또는 출력 대기 시간 초과 |
| INTERRUPTED | 실행 중단 |
| START_FAILED | CLI 실행 환경 준비/프로세스 시작 실패 |
| EXIT_ERROR | CLI가 비정상 종료 코드 반환 |
| OUTPUT_LIMIT | 프로세스 출력 크기 제한 초과 |
| MALFORMED_OUTPUT | JSON/위험 근거 형식 오류 또는 요청 버전과 불일치 |
| DOWNLOAD_FAILED | GuardDog가 패키지 다운로드 오류 반환 |
| SCANNER_ERRORS | 일부 GuardDog 규칙의 검사 오류 |
| EXECUTION_FAILED | 그 밖의 실행 실패 또는 예기치 않은 검사 중단 |

패키지는 최대 500개까지 목록을 수집하고, 파일 오류 누적 시에도 수집을 중단해 결과 크기를 제한한다.
한도를 넘으면 DISCOVERY_LIMIT을 남기고 미완료로 판정한다. total은 수집된 수이며 수집하지 못한
수량을 추정하지 않는다. 실행 수·시간·출력 제한도 유지한다. 실행하지 못한 수집 대상은 결과에서 사라지지 않는다.

## 예시: 시간 초과

```json
{
  "review": { "status": "DONE", "verdict": "NEEDS_CHANGES", "findingCount": 0 },
  "findings": [],
  "context": {
    "guarddog": {
      "status": "FAILED", "scope": "NEW_DIRECT_NPM_PYPI", "requiresAttention": true,
      "counts": { "total": 1, "completed": 0, "partial": 0, "failed": 1, "skipped": 0, "risks": 0 },
      "packages": [{
        "ecosystem": "pypi", "name": "example-package", "version": "1.0.0",
        "files": ["requirements.txt"], "status": "FAILED", "risks": [],
        "problems": [{ "code": "TIMEOUT", "message": "GuardDog 실행 시간 초과", "file": null }]
      }],
      "problems": [], "durationMs": 1000
    }
  }
}
```

## 호환성

기존 세 가지 verdict 값과 findings/리뷰 API 구조는 유지한다. pr-guard-web은 이번 단계에서 수정하지
않으며 패키지 전용 UI는 다음 단계에 연결한다. 과거 리뷰의 context에는 guarddog가 없을 수 있고
그 리뷰도 계속 조회할 수 있다. 과거 판정은 자동 변경하지 않으므로 새 정책 적용에는 재검사가 필요하다.

## 3단계 검증 결과 (2026-10-31)

- Windows와 Linux 컨테이너 단위 테스트 151개 통과.
- 실제 GuardDog 3.2.0 및 일회용 PostgreSQL을 사용하는 통합 테스트 18개 통과.
- Docker API 이미지와 검증 이미지 빌드 통과. 마지막 verification-summary.json 상태 PASSED.
- 검사 결과·정책 이유의 JSONB 저장, CHECK_C/VERDICT 이벤트, 리뷰 API 직렬화 확인.
- DISABLED/NO_CHANGES/COMPLETED/PARTIAL/FAILED, 한도 초과, 과거 context 조회 확인.
- 실제 미존재 PyPI 패키지 검사에서 finding 0건, FAILED / NEEDS_CHANGES / DOWNLOAD_FAILED 확인.
- 유효한 위험 근거와 규칙 오류가 함께 있을 때 근거를 유지하고 PARTIAL / NEEDS_CHANGES로 반환함을 확인.

원본 테스트 결과는 build/reports/guarddog/junit/, 대표 API 응답은 같은 디렉터리의
review-timeout.json, review-partial.json, review-live-missing.json, review-disabled.json,
review-no-changes.json, review-package-limit.json, review-live-six.json에 남긴다.
GitHub 응답과 checkout 전송은 로컬 테스트 자료이며, 실제 Git 소스·검사 프로세스·DB 저장과 API
controller를 검증했다. 원격 PR·코멘트 생성이나 배포는 수행하지 않았다. 임시 DB/네트워크는 정리했다.
