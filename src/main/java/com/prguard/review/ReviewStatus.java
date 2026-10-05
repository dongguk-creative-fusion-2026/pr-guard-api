package com.prguard.review;

public enum ReviewStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    /** 리뷰 전에 PR 에 새 커밋이 올라왔거나 PR 이 닫혔다. */
    SUPERSEDED
}
