package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

class DeliverySchedulerTest {
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final EmailTaskPublicationService publication = mock(EmailTaskPublicationService.class);
    private final NewsletterProperties properties = new NewsletterProperties();
    private final Instant now = Instant.parse("2026-10-15T04:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test void queriesOnlyDuePublicationsAndContinuesAfterOneTransactionFails() {
        when(deliveries.findPublicationDueIds(EmailDelivery.Status.PENDING, EmailDelivery.PublicationStatus.PENDING,
                now, PageRequest.of(0, 20))).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("transaction failed")).when(publication).publish(1L);
        new DeliveryScheduler(deliveries, publication, properties, clock).dispatch();
        verify(publication).publish(1L);
        verify(publication).publish(2L);
    }

    @Test void schedulerRequiresExplicitPublisherEnablement() {
        var runner = new ApplicationContextRunner().withUserConfiguration(DeliveryScheduler.class)
                .withBean(EmailDeliveryRepository.class, () -> deliveries)
                .withBean(EmailTaskPublicationService.class, () -> publication)
                .withBean(NewsletterProperties.class, () -> properties)
                .withBean(Clock.class, () -> clock);
        runner.run(context -> assertThat(context).doesNotHaveBean(DeliveryScheduler.class));
        runner.withPropertyValues("app.newsletter.publisher-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DeliveryScheduler.class));
        verifyNoInteractions(deliveries, publication);
    }
}
