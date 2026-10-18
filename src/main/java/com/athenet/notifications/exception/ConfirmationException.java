package com.athenet.notifications.exception;

import org.springframework.http.HttpStatus;

public class ConfirmationException extends RuntimeException {
    private final HttpStatus status;

    public ConfirmationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
