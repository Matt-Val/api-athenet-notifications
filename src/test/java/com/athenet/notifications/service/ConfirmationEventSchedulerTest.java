package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.athenet.notifications.repository.SubscriptionConfirmationEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

class ConfirmationEventSchedulerTest {
    private final SubscriptionConfirmationEventRepository events = mock(SubscriptionConfirmationEventRepository.class);
    private final ConfirmationEventPublicationService publication = mock(ConfirmationEventPublicationService.class);
    private final NewsletterProperties properties = new NewsletterProperties();
    private final Instant now = Instant.parse("2026-10-16T14:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test void dispatchesDueEventsByConfiguredBatchAndContinuesAfterTransactionFailure() {
        properties.setBatchSize(2);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(events.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING, now, PageRequest.of(0, 2)))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("transaction failed")).when(publication).publish(first);
        new ConfirmationEventScheduler(events, publication, properties, clock).dispatch();
        verify(publication).publish(first); verify(publication).publish(second);
    }

    @Test void requiresItsOwnExplicitEnablement() {
        var runner = new ApplicationContextRunner().withUserConfiguration(ConfirmationEventScheduler.class)
                .withBean(SubscriptionConfirmationEventRepository.class, () -> events)
                .withBean(ConfirmationEventPublicationService.class, () -> publication)
                .withBean(NewsletterProperties.class, () -> properties).withBean(Clock.class, () -> clock);
        runner.run(context -> assertThat(context).doesNotHaveBean(ConfirmationEventScheduler.class));
        runner.withPropertyValues("app.newsletter.publisher-enabled=true", "app.newsletter.worker-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ConfirmationEventScheduler.class));
        runner.withPropertyValues("app.newsletter.confirmation-publisher-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ConfirmationEventScheduler.class));
        verifyNoInteractions(events, publication);
    }

    @Test void interruptedDispatchStopsBeforePublishing() {
        when(events.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING, now, PageRequest.of(0, 20)))
                .thenReturn(List.of(UUID.randomUUID()));
        try {
            Thread.currentThread().interrupt();
            new ConfirmationEventScheduler(events, publication, properties, clock).dispatch();
            verifyNoInteractions(publication);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
