package com.athenet.notifications.exception;

// Motivo acotado de fallo; no conserva respuestas ni detalles privados del broker.
public class ConfirmationPublicationException extends RuntimeException {
    public enum Reason { BROKER_UNAVAILABLE, NACK, UNROUTABLE, CONFIRM_TIMEOUT, CONFIRM_FAILED, INTERRUPTED, INVALID_MESSAGE }
    private final Reason reason;

    public ConfirmationPublicationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
