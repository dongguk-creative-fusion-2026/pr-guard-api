package com.prguard.github;

import java.util.List;

/** 열린 PR 목록 조회 결과. notModified 면 pulls 는 비어 있고 이전 상태를 그대로 쓰면 된다. */
public record OpenPulls(boolean notModified, String etag, List<GitHubPull> pulls) {
}
