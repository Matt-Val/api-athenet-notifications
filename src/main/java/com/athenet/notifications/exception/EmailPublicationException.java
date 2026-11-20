package com.athenet.notifications.exception;

public class EmailPublicationException extends RuntimeException {
    public enum Reason { BROKER_UNAVAILABLE, NACK, UNROUTABLE, CONFIRM_TIMEOUT, CONFIRM_FAILED, INTERRUPTED, INVALID_MESSAGE }
    private final Reason reason;

    public EmailPublicationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
