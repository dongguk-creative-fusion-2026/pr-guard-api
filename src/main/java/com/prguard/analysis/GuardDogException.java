package com.prguard.analysis;

/** Stable public reason; raw subprocess output and credentials are never returned to the API. */
public final class GuardDogException extends IllegalStateException {
    private final GuardDogReport.Reason reason;

    public GuardDogException(GuardDogReport.Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GuardDogException(GuardDogReport.Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public GuardDogReport.Reason reason() { return reason; }
}
