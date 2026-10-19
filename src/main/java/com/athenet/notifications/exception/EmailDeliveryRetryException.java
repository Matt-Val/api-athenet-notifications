package com.athenet.notifications.exception;

// Causa resumida para reintentar tras terminar la transacción del worker.}
public class EmailDeliveryRetryException extends RuntimeException {
    private final Long deliveryId;

    public EmailDeliveryRetryException(Long deliveryId) {
        super("Entrega de correo pendiente de reintento: " + deliveryId);
        this.deliveryId = deliveryId;
    }

    public Long getDeliveryId() { return deliveryId; }

    public static EmailDeliveryRetryException findIn(Throwable failure) {
        for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause()) {
            if (failure instanceof EmailDeliveryRetryException retry) return retry;
        }
        return null;
    }
}
