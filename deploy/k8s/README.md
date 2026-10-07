# 실행 검증 (쿠버네티스)

PR 의 base · head 커밋에서 레포 테스트를 실제로 돌려, base 에서 통과하던 테스트가 head 에서 깨지면 BLOCKER 로 지적한다.
클라우드(GKE · DigitalOcean · 직접 설치 등)와 상관없이 같은 매니페스트와 설정으로 동작한다.

```
API (AnalysisPipeline)
  └ TestRunService ── Job 2개 (base, head) ──→ 러너 Pod (runner/run-tests.sh)
        ↑ Pod 상태 읽기                          ├ clone → gradlew/mvnw test
        └─────────── 진행 · JUnit 결과 ←───────── └ POST /api/test-runs/{id}/progress · /report (일회용 토큰)
```

화면의 리뷰 파이프라인에는 "실행 검증 · 쿠버네티스" 레인으로 보인다:
실행 준비 → base/head Pod 기동 → base/head 빌드 · 테스트 → 차등 비교 → 판정.
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

```bash
minikube start --memory=6144 --cpus=4
docker build -t pr-guard-test-runner:dev runner && minikube image load pr-guard-test-runner:dev
kubectl --context minikube apply -k deploy/k8s
# API 컨테이너를 minikube 네트워크에 붙이고 위 kubeconfig(서버 https://192.168.49.2:8443)를 넣는다
#   EXEC_RUNNER=kubernetes EXEC_IMAGE=pr-guard-test-runner:dev EXEC_CALLBACK_URL=http://<API 컨테이너 IP>:8080
```

쿠버네티스 없이 흐름만 보려면 `EXEC_RUNNER=docker` (API 가 있는 곳의 docker 로 러너를 띄운다).

## 남은 일

- 의존성 · Gradle 배포판 캐시 (지금은 실행마다 받아서 sandbox 기준 3~6분)
- 의존성 프록시를 두고 NetworkPolicy egress 를 프록시 · GitHub · API 로만 좁히기
- 큐 길이 기반 자동 확장 (KEDA), 스팟 노드
- 리뷰 워커가 실행을 기다리는 동안 다른 리뷰가 밀린다 → 실행 대기를 비동기로
