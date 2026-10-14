# 실행 검증 (쿠버네티스)

PR 의 base · head 커밋에서 레포 테스트를 실제로 돌려, base 에서 통과하던 테스트가 head 에서 깨지면 BLOCKER 로 지적한다.
클라우드(GKE · DigitalOcean · 직접 설치 등)와 상관없이 같은 매니페스트와 설정으로 동작한다.

```
API (AnalysisPipeline)
  └ TestRunService ── Job 2개 (base, head) ──→ 러너 Pod (runner/run-tests.sh)
        ↑ Pod 상태 읽기                          ├ clone
        │                                        ├ GET extra-files  증거 · 관측 테스트 받기
        │                                        ├ GET selection    돌릴 테스트 클래스 받기 (영향 테스트 선택)
        │                                        ├ gradlew/mvnw test (고른 클래스만, 호출 기록 trace-agent)
        │                                        ├ 실패한 테스트 클래스만 다시 실행 (flaky 판별)
        │                                        ├ head 만: GET mutation → PIT 변경분 변이 테스트
        └─────────── 진행 · 결과 ←──────────────── └ POST progress · report (JUnit · rerun · trace · probe · mutations, 일회용 토큰)
```

화면의 리뷰 파이프라인에는 "실행 검증 · 쿠버네티스" 레인으로 보인다:
실행 준비 → base/head Pod 기동 → (테스트 선택) → base/head 빌드 · 테스트 → (head) 변이 테스트 → 차등 비교 → 판정.
러너가 하는 일의 기준은 [`../../README.md`](../../README.md) 의 "영향 테스트 선택" · "테스트 신뢰도".
`EXEC_RUNNER=none`(기본)이면 이 노드들은 "실행 환경 준비 중"으로 회색 표시된다.

## 클러스터 준비

```bash
kubectl apply -k deploy/k8s
```

- `prguard-runs` 네임스페이스 (Pod Security `restricted` 강제)
- `prguard-api` 서비스 계정: 이 네임스페이스 안에서 Job 생성 · 삭제, Pod 조회만 가능
- ResourceQuota · LimitRange: 동시 러너 수와 자원 상한
- NetworkPolicy: 들어오는 연결 차단, 나가는 연결은 DNS · HTTP(S)만 (사설 대역 제외).
  NetworkPolicy 를 지원하는 CNI(Calico, Cilium, GKE Dataplane V2 등)에서만 적용된다
- 격리 런타임(gVisor)은 `runtimeclass-gvisor.example.yaml` 참고. 쓰면 API 에 `EXEC_RUNTIME_CLASS=gvisor`

## API 연결

클러스터 안에서 돌면 서비스 계정을 자동으로 쓴다. 밖(Railway 등)이면 서비스 계정 토큰으로 kubeconfig 를 만든다.

```bash
TOKEN=$(kubectl -n prguard-runs get secret prguard-api-token -o jsonpath='{.data.token}' | base64 -d)
CA=$(kubectl -n prguard-runs get secret prguard-api-token -o jsonpath='{.data.ca\.crt}')
SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
cat > kubeconfig <<KUBE
apiVersion: v1
kind: Config
clusters: [{name: c, cluster: {server: $SERVER, certificate-authority-data: $CA}}]
users: [{name: prguard-api, user: {token: $TOKEN}}]
contexts: [{name: c, context: {cluster: c, user: prguard-api, namespace: prguard-runs}}]
current-context: c
KUBE
```

API 환경변수:

| 변수 | 예 | 설명 |
|---|---|---|
| `EXEC_RUNNER` | `kubernetes` | `none`(끔) · `kubernetes` · `docker`(로컬 개발) |
| `KUBECONFIG` | `/etc/prguard/kubeconfig` | 클러스터 밖일 때 위 파일 경로 |
| `EXEC_CALLBACK_URL` | `https://pr-guard-api-production.up.railway.app` | 러너가 결과를 보낼 API 주소 (러너 쪽에서 닿아야 한다) |
| `EXEC_IMAGE` | `ghcr.io/dongguk-creative-fusion-2026/pr-guard-test-runner:latest` | 러너 이미지 (`runner/`, `runner-image` 워크플로가 올린다) |
| `EXEC_NAMESPACE` | `prguard-runs` | |
| `EXEC_RUNTIME_CLASS` | `gvisor` | 비우면 기본 런타임 |
| `EXEC_CPU` · `EXEC_MEMORY` · `EXEC_TIMEOUT` | `2` · `3Gi` · `PT15M` | 러너 하나의 한도 |

GHCR 패키지가 비공개면 `prguard-runs` 에 imagePullSecret 을 두거나 패키지를 공개로 바꾼다.

## 로컬에서 확인 (minikube)

API · 웹은 이 PC 에서, 러너는 minikube 에서 돈다. PR 코멘트는 끄고(읽기만) 돌린다.

```bash
# 1. 클러스터와 러너 이미지 (이미지는 minikube 안에서 바로 빌드)
minikube start --driver=docker --memory=6144 --cpus=4
minikube image build -t pr-guard-test-runner:dev runner
kubectl --context minikube apply -k deploy/k8s

# 2. DB
docker compose up -d postgres

# 3. API (이 PC). 러너 Pod 는 host.docker.internal 로 이 API 에 결과를 보낸다
#    GITHUB_TOKEN 을 비우면 PR 코멘트 · 그래프 워크플로가 돌지 않는다 (읽기만, 무인증 한도 60회/시간)
./gradlew bootJar
EXEC_RUNNER=kubernetes EXEC_IMAGE=pr-guard-test-runner:dev EXEC_CALLBACK_URL=http://host.docker.internal:8080 \
  GITHUB_TOKEN= GITHUB_COMMENT_ENABLED=false java -jar build/libs/app.jar

# Pod 에서 API 가 보이는지
kubectl run nettest --rm -i --restart=Never --image=pr-guard-test-runner:dev --image-pull-policy=Never \
  --command -- curl -s http://host.docker.internal:8080/actuator/health

# 4. 웹 (pr-guard-web)
API_BASE_URL=http://localhost:8080 npm run build && npm start
```

시연 순서 (sandbox 또는 테스트가 많은 `demo-petclinic`):

1. 화면에서 `dongguk-creative-fusion-2026/pr-guard-sandbox` 를 등록하고 설정에서 실행 검증을 켠다.
2. 첫 리뷰는 기준 기록이 없어 **전체 실행**한다. 이 실행의 호출 기록이 다음 PR 의 기준이 된다.
3. 같은 base 의 다른 PR 을 다시 리뷰하면 **선택 실행 · 변이 테스트 · flaky 재실행**이 돌고, 리뷰 화면의 "테스트 선택"에서 장면이 나온다.

열린 PR 이 많으면 등록 직후 모두 리뷰 대기열에 들어간다. 시연할 PR 만 남기려면 DB 에서 나머지 대기 리뷰를 `SUPERSEDED` 로 바꾼다.

```bash
docker compose exec -T postgres psql -U prguard -d prguard \
  -c "UPDATE reviews SET status='SUPERSEDED', finished_at=now() WHERE status='PENDING' AND pr_number <> 23;"
```

러너 이미지에는 JDK 21(기본)과 17 이 있고, Gradle 툴체인이 둘을 찾는다 (`org.gradle.java.installations.paths`). 다른 버전을 요구하는 레포는 빌드가 실패한다.

Windows 에서 Docker Desktop 이 `remove …\Docker\run\*.sock: The file cannot be accessed by the system` 으로 멈추면
Docker 를 끄고 `%LOCALAPPDATA%\Docker\run` · `%LOCALAPPDATA%\docker-secrets-engine` 폴더를 다른 이름으로 옮긴 뒤 다시 켠다 (남은 소켓 파일을 지우지 못해 생기는 문제).

쿠버네티스 없이 흐름만 보려면 `EXEC_RUNNER=docker` (API 가 있는 곳의 docker 로 러너를 띄운다).

## 남은 일

- 의존성 · Gradle 배포판 캐시 (지금은 실행마다 받아서 sandbox 기준 3~6분). 영향 테스트 선택은 테스트 실행 시간을 줄이지만 빌드 · 의존성 시간은 그대로다
- 의존성 프록시를 두고 NetworkPolicy egress 를 프록시 · GitHub · API 로만 좁히기
- 큐 길이 기반 자동 확장 (KEDA), 스팟 노드
- 리뷰 워커가 실행을 기다리는 동안 다른 리뷰가 밀린다 → 실행 대기를 비동기로
