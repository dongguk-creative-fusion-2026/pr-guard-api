package com.prguard.events;

public enum StageStatus {
    RUNNING,
    DONE,
    FAILED,
    /** 실행하지 않음 (규칙 미구현, LLM 꺼짐, dry-run 등) */
    SKIPPED
}
