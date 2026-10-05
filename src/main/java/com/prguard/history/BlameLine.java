package com.prguard.history;

/**
 * base 의 한 라인을 마지막으로 바꾼 커밋.
 *
 * @param line base 기준 라인 번호
 */
public record BlameLine(String file, int line, String sha, String author, long time, String summary) {
}
