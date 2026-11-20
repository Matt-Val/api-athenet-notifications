package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.mail.MailTransport;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;

@Service
public class DeliveryWorker {
    public enum Outcome { DONE, RETRY, DEFERRED, FAILED, NOT_FOUND }
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final EmailDeliveryRepository deliveries;
    private final SubscriberRepository subscribers;
    private final ResultStateRepository states;
    private final EventNoticeStateRepository eventStates;
    private final MailTransport mail;
    private final NewsletterProperties properties;
    private final Clock clock;

    public DeliveryWorker(EmailDeliveryRepository deliveries, SubscriberRepository subscribers,
                          ResultStateRepository states, EventNoticeStateRepository eventStates,
                          MailTransport mail, NewsletterProperties properties, Clock clock) {
        super();
        this.deliveries = deliveries;
        this.subscribers = subscribers;
        this.states = states;
        this.eventStates = eventStates;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Outcome process(Long id) {
        var delivery = deliveries.findLockedById(id).orElse(null);
        var now = clock.instant();
        if (delivery == null) return Outcome.NOT_FOUND;
        if (delivery.getStatus() == EmailDelivery.Status.FAILED) return Outcome.FAILED;
        if (delivery.getStatus() != EmailDelivery.Status.PENDING) return Outcome.DONE;
        var subscriber = subscribers.findLockedById(delivery.getSubscriberId()).orElse(null);
        if (!eligible(delivery, subscriber)) {
            delivery.setStatus(EmailDelivery.Status.SKIPPED);
            return Outcome.DONE;
        }
        if (delivery.getAttempts() >= properties.getMaxAttempts()) {
            delivery.setStatus(EmailDelivery.Status.FAILED);
            return Outcome.FAILED;
        }
        if (delivery.getNextAttemptAt().isAfter(now)) return Outcome.DEFERRED;
        delivery.setAttempts(delivery.getAttempts() + 1);
        try {
            // El lock evita envíos concurrentes; SMTP y la BD no son una transacción distribuida.
            mail.send(delivery.getRecipient(), delivery.getSubject(), delivery.getBody());
            delivery.setStatus(EmailDelivery.Status.SENT);
            delivery.setSentAt(clock.instant());
            delivery.setLastError(null);
            return Outcome.DONE;
        } catch (RuntimeException ex) {
            delivery.setLastError(ex.getClass().getSimpleName());
            if (delivery.getAttempts() >= properties.getMaxAttempts()) delivery.setStatus(EmailDelivery.Status.FAILED);
            else delivery.setNextAttemptAt(clock.instant().plus(Duration.ofSeconds(30L << Math.min(delivery.getAttempts() - 1, 6))));
            log.warn("Falló entrega {} (intento {}, tipo {})", id, delivery.getAttempts(), ex.getClass().getSimpleName());
            return delivery.getStatus() == EmailDelivery.Status.FAILED ? Outcome.FAILED : Outcome.RETRY;
        }
    }

    private boolean eligible(EmailDelivery delivery, Subscriber subscriber) {
        if (subscriber == null || subscriber.getGeneration() != delivery.getSubscriberGeneration()) return false;
        if (delivery.getKind() == EmailDelivery.Kind.CONFIRMATION) {
            return subscriber.getStatus() == Subscriber.Status.PENDING
                    && subscriber.getConfirmationExpiresAt() != null
                    && subscriber.getConfirmationExpiresAt().isAfter(clock.instant())
                    && delivery.getConfirmationHash() != null
                    && Objects.equals(delivery.getConfirmationHash(), subscriber.getConfirmationHash());
        }
        if (delivery.getKind() == EmailDelivery.Kind.EVENT) {
            return subscriber.getStatus() == Subscriber.Status.ACTIVE
                    && delivery.getEventInternalId() != null && delivery.getEventVersion() != null
                    && eventStates.findById(delivery.getEventInternalId()).map(state -> state.isSporting()
                        && state.getLatestVersion() == delivery.getEventVersion()).orElse(false);
        }
        return delivery.getKind() == EmailDelivery.Kind.RESULT
                && delivery.getMatchId() != null && delivery.getResultVersion() != null
                && subscriber.getStatus() == Subscriber.Status.ACTIVE
                && states.findById(delivery.getMatchId()).map(state -> state.getLatestVersion() == delivery.getResultVersion()).orElse(false);
    }
}
