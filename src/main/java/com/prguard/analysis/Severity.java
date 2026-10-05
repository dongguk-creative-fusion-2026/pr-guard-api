package com.prguard.analysis;

public enum Severity {
    /** 머지하면 깨지거나 위험함 */
    BLOCKER,
    /** 머지 전에 고쳐야 함 */
    MAJOR,
    /** 고치면 좋음 */
    MINOR,
    /** 참고 */
    INFO
}
