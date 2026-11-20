package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Duration;
import com.athenet.notifications.exception.EmailPublicationException;
import com.athenet.notifications.messaging.EmailRequestedEnvelope;
import com.athenet.notifications.messaging.EmailTaskPublisher;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailTaskPublicationService {
    private static final Logger log = LoggerFactory.getLogger(EmailTaskPublicationService.class);
    private final EmailDeliveryRepository deliveries;
    private final EmailTaskPublisher publisher;
    private final Clock clock;

    public EmailTaskPublicationService(EmailDeliveryRepository deliveries, EmailTaskPublisher publisher, Clock clock) {
        super();
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void publish(Long id) {
        var delivery = deliveries.findLockedById(id).orElse(null);
        if (delivery == null || delivery.getStatus() != EmailDelivery.Status.PENDING
                || delivery.getPublicationStatus() == EmailDelivery.PublicationStatus.CONFIRMED) return;
        var now = clock.instant();
        var dueAt = delivery.getNextPublicationAt() != null ? delivery.getNextPublicationAt() : delivery.getNextAttemptAt();
        if (dueAt.isAfter(now)) return;
        int attempts = (int) Math.min(Integer.MAX_VALUE,
                (delivery.getPublicationAttempts() == null ? 0L : delivery.getPublicationAttempts().longValue()) + 1);
        delivery.setPublicationAttempts(attempts);
        delivery.setPublicationStatus(EmailDelivery.PublicationStatus.PENDING);
        if (delivery.getCreatedAt() == null) delivery.setCreatedAt(delivery.getNextAttemptAt());
        try {
            publisher.publish(EmailRequestedEnvelope.forDelivery(delivery.getId(), delivery.getCreatedAt()));
        } catch (EmailPublicationException ex) {
            delivery.setPublicationError(ex.getReason().name());
            long delay = Math.min(900, 30L << Math.min(attempts - 1, 5));
            delivery.setNextPublicationAt(clock.instant().plus(Duration.ofSeconds(delay)));
            log.warn("Publicación de entrega {} pendiente (intento {}, motivo {})", id, attempts, ex.getReason());
            return;
        }
        // El lock cubre publicar y registrar el ACK. Nunca modificar aquí el estado de envío SMTP.
        delivery.setPublicationStatus(EmailDelivery.PublicationStatus.CONFIRMED);
        delivery.setPublishedAt(clock.instant());
        delivery.setNextPublicationAt(null);
        delivery.setPublicationError(null);
    }
}
