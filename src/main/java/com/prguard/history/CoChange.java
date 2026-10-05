package com.prguard.history;

/**
 * 과거 이력에서 file 이 바뀐 커밋 중 partner 도 함께 바뀐 정도 (Zimmermann et al., ICSE 2004).
 *
 * @param directory   partner 가 파일이 아니라 디렉터리 (마이그레이션처럼 매번 새 파일이 생기는 경우)
 * @param support     함께 바뀐 커밋 수
 * @param fileCommits file 이 바뀐 커밋 수
 * @param confidence  support / fileCommits
 */
public record CoChange(String file, String partner, boolean directory, int support, int fileCommits,
                       double confidence) {
}
