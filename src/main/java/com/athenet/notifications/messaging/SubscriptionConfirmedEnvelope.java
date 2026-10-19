package com.athenet.notifications.messaging;

import com.athenet.notifications.model.SubscriptionConfirmationEvent;

/** Aviso histórico de confirmación. No transporta correo, tokens ni órdenes de activación. */
public record SubscriptionConfirmedEnvelope(String messageId, String occurredAt, String type, Data data) {
    public record Data(Long subscriberId, long generation, String confirmedAt) {}

    public static SubscriptionConfirmedEnvelope forEvent(SubscriptionConfirmationEvent event) {
        String confirmedAt = event.getOccurredAt().toString();
        return new SubscriptionConfirmedEnvelope(event.getId().toString(), confirmedAt,
                SubscriptionConfirmationEvent.TYPE,
                new Data(event.getSubscriberId(), event.getSubscriberGeneration(), confirmedAt));
    }
}
