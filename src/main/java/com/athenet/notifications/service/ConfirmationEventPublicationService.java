package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import com.athenet.notifications.exception.ConfirmationPublicationException;
import com.athenet.notifications.messaging.ConfirmationEventPublisher;
import com.athenet.notifications.messaging.SubscriptionConfirmedEnvelope;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.athenet.notifications.repository.SubscriptionConfirmationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmationEventPublicationService {
    private static final Logger log = LoggerFactory.getLogger(ConfirmationEventPublicationService.class);
    private final SubscriptionConfirmationEventRepository events;
    private final ConfirmationEventPublisher publisher;
    private final Clock clock;

    public ConfirmationEventPublicationService(SubscriptionConfirmationEventRepository events,
                                               ConfirmationEventPublisher publisher, Clock clock) {
        super();
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void publish(UUID id) {
        var event = events.findLockedById(id).orElse(null);
        if (event == null || event.getStatus() == SubscriptionConfirmationEvent.Status.PUBLISHED) return;
        var now = clock.instant();
        var dueAt = event.getNextPublicationAt() != null ? event.getNextPublicationAt() : event.getOccurredAt();
        if (dueAt.isAfter(now)) return;
        int attempts = (int) Math.min(Integer.MAX_VALUE,
                (event.getPublicationAttempts() == null ? 0L : event.getPublicationAttempts().longValue()) + 1);
        event.setPublicationAttempts(attempts);
        try {
            publisher.publish(SubscriptionConfirmedEnvelope.forEvent(event));
        } catch (ConfirmationPublicationException ex) {
            event.setPublicationError(ex.getReason().name());
            long delay = Math.min(900, 30L << Math.min(attempts - 1, 5));
            event.setNextPublicationAt(clock.instant().plus(Duration.ofSeconds(delay)));
            log.warn("Evento de confirmación {} pendiente (intento {}, motivo {})", id, attempts, ex.getReason());
            return;
        }
        event.setStatus(SubscriptionConfirmationEvent.Status.PUBLISHED);
        event.setPublishedAt(clock.instant());
        event.setNextPublicationAt(null);
        event.setPublicationError(null);
    }
}
