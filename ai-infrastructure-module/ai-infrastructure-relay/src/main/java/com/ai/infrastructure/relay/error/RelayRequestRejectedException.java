package com.ai.infrastructure.relay.error;

import org.springframework.http.HttpStatus;

public class RelayRequestRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public RelayRequestRejectedException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

