package com.prguard.common;

import org.springframework.http.HttpStatus;

/** 컨트롤러까지 올라가서 {timestamp, code, message} 로 응답되는 예외. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
