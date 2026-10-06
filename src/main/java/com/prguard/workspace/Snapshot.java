package com.prguard.workspace;

import java.nio.file.Path;

/**
 * 브랜치 하나의 최신 소스.
 *
 * @param mirror 레포 clone
 * @param dir    꺼낸 소스
 * @param sha    꺼낸 커밋
 */
public record Snapshot(Path mirror, Path dir, String sha) {
}
