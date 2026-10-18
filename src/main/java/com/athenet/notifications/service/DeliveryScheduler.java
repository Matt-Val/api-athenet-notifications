package com.athenet.notifications.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;

@Component
@ConditionalOnProperty(name = "app.newsletter.publisher-enabled", havingValue = "true")
public class DeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);
    private final EmailDeliveryRepository deliveries;
    private final EmailTaskPublicationService publication;
    private final NewsletterProperties properties;
    private final Clock clock;

    public DeliveryScheduler(EmailDeliveryRepository deliveries, EmailTaskPublicationService publication,
                             NewsletterProperties properties, Clock clock) {
        super();
        this.deliveries = deliveries;
        this.publication = publication;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.newsletter.dispatch-delay:5000}")
    public void dispatch() {
        for (Long id : deliveries.findPublicationDueIds(EmailDelivery.Status.PENDING,
                EmailDelivery.PublicationStatus.PENDING, clock.instant(), PageRequest.of(0, properties.getBatchSize()))) {
            if (Thread.currentThread().isInterrupted()) break;
            try { publication.publish(id); }
            catch (RuntimeException ex) { log.warn("No se pudo publicar entrega {} (tipo {})", id, ex.getClass().getSimpleName()); }
        }
    }
}
