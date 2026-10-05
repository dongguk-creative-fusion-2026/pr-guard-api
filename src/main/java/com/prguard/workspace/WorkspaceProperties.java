package com.prguard.workspace;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dir        clone 과 worktree 를 두는 디렉터리
 * @param maxRepoKb  이보다 큰 레포는 받지 않는다 (GitHub API 의 size, KB)
 * @param gitTimeout git 명령 하나의 제한 시간
 * @param keepMirror 마지막 사용 후 이 시간이 지난 clone 은 지운다
 */
@ConfigurationProperties("prguard.workspace")
public record WorkspaceProperties(Path dir, long maxRepoKb, Duration gitTimeout, Duration keepMirror) {
}
