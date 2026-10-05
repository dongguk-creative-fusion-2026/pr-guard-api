package com.prguard.history;

import java.util.List;

/** @param time 커밋 시각 (epoch seconds) */
public record CommitInfo(String sha, long time, String author, String subject, List<String> files) {
}
