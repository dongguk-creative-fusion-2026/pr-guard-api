package com.prguard.execution;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 러너 의존성 캐시 (Gradle · Maven). 실행마다 의존성 · Gradle 배포판을 새로 받지 않게 프로젝트별로 남겨 둔다.
 *
 * base 러너는 이미 머지된 코드라 캐시를 읽고 쓰고, head 러너는 믿을 수 없는 PR 코드라 읽기만 한다
 * (Gradle 읽기 전용 의존성 캐시). 프로젝트마다 다른 칸을 써서 한 레포가 다른 레포의 캐시를 바꿀 수 없다.
 *
 * @param enabled 켜면 러너에 /cache 를 붙인다
 * @param pvc     쿠버네티스: 캐시 PersistentVolumeClaim 이름 (deploy/k8s/runner-cache.yaml). docker 러너는 이름 붙은 볼륨을 쓴다
 */
@ConfigurationProperties("prguard.execution.cache")
public record RunnerCacheProperties(Boolean enabled, String pvc) {

    public RunnerCacheProperties {
        enabled = enabled != null && enabled;
        pvc = pvc == null || pvc.isBlank() ? "prguard-runner-cache" : pvc;
    }

    public static final RunnerCacheProperties OFF = new RunnerCacheProperties(false, null);

    /** 캐시 안 프로젝트 칸 이름: https://github.com/Owner/Repo.git → owner--repo */
    static String key(String repoUrl) {
        String path = repoUrl.replaceFirst("^[a-z]+://[^/]+/", "").replaceFirst("\\.git$", "");
        String key = path.toLowerCase(Locale.ROOT).replace('/', '-').replaceAll("[^a-z0-9._-]", "-");
        // 하위 경로로 빠져나가거나 너무 길지 않게
        key = key.replace("..", "-");
        return key.length() > 100 ? key.substring(0, 100) : key;
    }

    /** base 만 쓴다. head(PR 코드)는 읽기만 */
    static boolean writable(String side) {
        return "BASE".equals(side);
    }
}
