package com.prguard.execution;

/** 러너 하나를 띄우고 상태를 보는 곳. 쿠버네티스 Job 이나 로컬 docker 컨테이너. */
public interface TestRunner {

    /** 실행을 시작하고 실행 이름(Job · 컨테이너 이름)을 돌려준다. */
    String start(TestRunJob job);

    /** 실행 환경(Pod · 컨테이너) 상태. 찾지 못하면 null */
    RunnerStatus status(String name);

    /** 다 쓴 실행을 지운다. 실패해도 예외를 던지지 않는다. */
    void cleanup(String name);

    /**
     * 러너에 넘기는 값. 러너 이미지는 환경변수로 받는다 (runner/run-tests.sh).
     *
     * @param side        BASE 또는 HEAD
     * @param prNumber    포크 PR 의 커밋은 원본 레포에서 refs/pull/N/head 로만 받을 수 있다
     * @param callbackUrl 이 실행 전용 콜백 주소 (…/api/test-runs/{id})
     */
    record TestRunJob(long runId, String side, String repoUrl, int prNumber, String sha, String callbackUrl,
                      String token) {
    }

    /**
     * @param phase   SCHEDULING(자리 기다림), PULLING(이미지 받는 중), RUNNING, SUCCEEDED, FAILED
     * @param message 사람이 읽는 설명 (대기 이유 등)
     */
    record RunnerStatus(Phase phase, String message) {

        public boolean started() {
            return phase == Phase.RUNNING || phase == Phase.SUCCEEDED || phase == Phase.FAILED;
        }
    }

    enum Phase {
        SCHEDULING, PULLING, RUNNING, SUCCEEDED, FAILED
    }
}
