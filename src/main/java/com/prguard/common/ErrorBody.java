package com.prguard.common;

import java.time.OffsetDateTime;

public record ErrorBody(OffsetDateTime timestamp, String code, String message) {

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(OffsetDateTime.now(), code, message);
    }
}
