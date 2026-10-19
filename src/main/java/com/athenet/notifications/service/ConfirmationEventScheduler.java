package com.athenet.notifications.service;

import java.time.Clock;
import java.util.UUID;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.athenet.notifications.repository.SubscriptionConfirmationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.newsletter.confirmation-publisher-enabled", havingValue = "true")
public class ConfirmationEventScheduler {
    private static final Logger log = LoggerFactory.getLogger(ConfirmationEventScheduler.class);
    private final SubscriptionConfirmationEventRepository events;
    private final ConfirmationEventPublicationService publication;
    private final NewsletterProperties properties;
    private final Clock clock;

    public ConfirmationEventScheduler(SubscriptionConfirmationEventRepository events,
                                      ConfirmationEventPublicationService publication,
                                      NewsletterProperties properties, Clock clock) {
        super();
        this.events = events;
        this.publication = publication;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.newsletter.dispatch-delay:5000}")
    public void dispatch() {
        for (UUID id : events.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING,
                clock.instant(), PageRequest.of(0, properties.getBatchSize()))) {
            if (Thread.currentThread().isInterrupted()) break;
            try { publication.publish(id); }
            catch (RuntimeException ex) { log.warn("No se pudo publicar confirmación {} (tipo {})", id, ex.getClass().getSimpleName()); }
        }
    }
}
