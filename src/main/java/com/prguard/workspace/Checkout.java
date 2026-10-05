package com.prguard.workspace;

import java.nio.file.Path;

/**
 * PR 하나를 분석하려고 꺼내 둔 소스.
 *
 * @param mirror  레포 전체 이력이 있는 clone (git log/blame 용)
 * @param baseDir merge-base 커밋의 소스
 * @param headDir PR head 커밋의 소스
 * @param baseSha PR 브랜치가 갈라져 나온 커밋 (merge-base). 이번 PR 이 만든 변경만 보려고 base 브랜치 끝이 아니라 이걸 쓴다
 */
public record Checkout(Path mirror, Path baseDir, Path headDir, String baseSha, String headSha) {
}
