package com.athenet.notifications.service;

import java.time.Instant;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.model.Subscriber;

final class DeliveryFactory {
    private DeliveryFactory() {}

    static EmailDelivery pending(Subscriber subscriber, EmailDelivery.Kind kind, String subject, String body, Instant now) {
        var delivery = new EmailDelivery();
        delivery.setSubscriberId(subscriber.getId());
        delivery.setSubscriberGeneration(subscriber.getGeneration());
        delivery.setRecipient(subscriber.getEmail());
        delivery.setKind(kind);
        delivery.setSubject(subject);
        delivery.setBody(body);
        delivery.setNextAttemptAt(now);
        delivery.setCreatedAt(now);
        delivery.setNextPublicationAt(now);
        return delivery;
    }
}
