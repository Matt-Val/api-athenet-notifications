package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.athenet.notifications.exception.ConfirmationPublicationException;
import com.athenet.notifications.exception.ConfirmationPublicationException.Reason;
import com.athenet.notifications.messaging.*;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.athenet.notifications.model.Subscriber;
import com.athenet.notifications.repository.SubscriptionConfirmationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class ConfirmationEventPublicationServiceTest {
    private final SubscriptionConfirmationEventRepository events = mock(SubscriptionConfirmationEventRepository.class);
    private final ConfirmationEventPublisher publisher = mock(ConfirmationEventPublisher.class);
    private final Clock clock = mock(Clock.class);
    private final ConfirmationEventPublicationService service = new ConfirmationEventPublicationService(events, publisher, clock);
    private final Instant now = Instant.parse("2026-10-16T14:00:00Z");
    private final UUID id = UUID.fromString("0759fbdf-1518-4c3b-a352-079f133c0cda");
    private final SubscriptionConfirmationEvent event = new SubscriptionConfirmationEvent();

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(now);
        event.setId(id); event.setSubscriberId(3L); event.setSubscriberGeneration(1);
        event.setOccurredAt(now); event.setNextPublicationAt(now);
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
    }

    @Test void ackMarksPublishedAndRepeatedDispatchDoesNotPublishAgain() {
        service.publish(id); service.publish(id);
        verify(publisher).publish(any());
        assertThat(event.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getPublicationAttempts()).isEqualTo(1);
        assertThat(event.getNextPublicationAt()).isNull();
        assertThat(event.getPublicationError()).isNull();
    }

    @ParameterizedTest @EnumSource(Reason.class)
    void failureKeepsPendingAndSchedulesRetry(Reason reason) {
        doThrow(new ConfirmationPublicationException(reason)).when(publisher).publish(any());
        service.publish(id);
        assertThat(event.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PENDING);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublicationAttempts()).isEqualTo(1);
        assertThat(event.getPublicationError()).isEqualTo(reason.name());
        assertThat(event.getNextPublicationAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test void recoveryPreservesHistoricalIdentityAndConfirmationDate() {
        doThrow(new ConfirmationPublicationException(Reason.UNROUTABLE)).doNothing().when(publisher).publish(any());
        service.publish(id); service.publish(id);
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        service.publish(id);
        var messages = ArgumentCaptor.forClass(SubscriptionConfirmedEnvelope.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues().get(0)).isEqualTo(messages.getAllValues().get(1));
        assertThat(messages.getValue().messageId()).isEqualTo(id.toString());
        assertThat(messages.getValue().data().confirmedAt()).isEqualTo(now.toString());
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.getPublicationAttempts()).isEqualTo(2);
        assertThat(event.getPublishedAt()).isEqualTo(now.plusSeconds(30));
        assertThat(event.getPublicationError()).isNull();
        assertThat(event.getNextPublicationAt()).isNull();
    }

    @Test void futureEventsWaitWithoutIncrementingAttempts() {
        event.setNextPublicationAt(now.plusSeconds(60));
        service.publish(id);
        verifyNoInteractions(publisher);
        assertThat(event.getPublicationAttempts()).isZero();
    }

    @Test void legacyNullPublicationMetadataUsesOccurrenceDate() {
        event.setPublicationAttempts(null); event.setNextPublicationAt(null);
        service.publish(id);
        verify(publisher).publish(SubscriptionConfirmedEnvelope.forEvent(event));
        assertThat(event.getPublicationAttempts()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PUBLISHED);
    }

    @Test void futureLegacyEventWaitsForOccurrenceDate() {
        event.setNextPublicationAt(null); event.setOccurredAt(now.plusSeconds(1));
        service.publish(id);
        verifyNoInteractions(publisher);
    }

    @Test void missingRowDoesNothing() {
        service.publish(UUID.randomUUID());
        verifyNoInteractions(publisher);
    }

    @Test void publicationBudgetDoesNotOverflowAndBackoffIsCapped() {
        event.setPublicationAttempts(Integer.MAX_VALUE);
        doThrow(new ConfirmationPublicationException(Reason.BROKER_UNAVAILABLE)).when(publisher).publish(any());
        service.publish(id);
        assertThat(event.getPublicationAttempts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(event.getNextPublicationAt()).isEqualTo(now.plusSeconds(900));
    }

    @Test void laterUnsubscriptionDoesNotCancelHistoryOrReactivateSubscriber() {
        var subscriber = new Subscriber(); subscriber.setId(3L); subscriber.setGeneration(1);
        subscriber.setStatus(Subscriber.Status.ACTIVE);
        var historical = SubscriptionConfirmationEvent.pending(subscriber, now);
        historical.setId(id);
        subscriber.setStatus(Subscriber.Status.UNSUBSCRIBED);
        when(events.findLockedById(id)).thenReturn(Optional.of(historical));
        service.publish(id);
        verify(publisher).publish(SubscriptionConfirmedEnvelope.forEvent(historical));
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.UNSUBSCRIBED);
        assertThat(historical.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PUBLISHED);
    }
}
