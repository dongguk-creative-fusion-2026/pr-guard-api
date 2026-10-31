# 불변식 검증 PoC

Hypothesis로 승인된 규칙 하나(게시글 수정 전후 작성자 ID 유지)의 반례를 head에서 찾고, 동일 입력을 base/head에 각각 두 번 재생해 신규 위반·기존 위반·불안정·반례 미발견을 분류하는 독립 프로토타입이다.

```sh
python -m pip install -r invariant-poc/requirements.txt
python -m unittest discover -s invariant-poc -v
```

현재 테스트는 메모리 fixture로 탐색과 분류만 확인한다. 실제 Spring 앱, PostgreSQL, PR Guard 파이프라인, GitHub, UI와 연결하지 않았으며 PR을 자동 분석하지 않는다. 실제 연결에는 격리된 DB를 예제마다 초기화하고 인증·수정 요청을 수행한 뒤 작성자 ID를 관측하는 `Adapter.run` 구현이 필요하다. 메서드는 예제마다 동일 fixture를 보장해야 하며 실패 시 예외를 발생시켜야 한다.

반례 미발견은 제한된 예산에서 발견하지 못했다는 의미이며 불변식의 증명이 아니다. 결과는 판정에 사용하지 않는다. 타임아웃, 실행 격리, 입력 저장·민감정보 처리, 복잡한 입력 전략은 실제 연동 전에 구현해야 한다.
