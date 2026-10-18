package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import com.athenet.notifications.exception.EmailPublicationException;
import com.athenet.notifications.exception.EmailPublicationException.Reason;
import com.athenet.notifications.messaging.*;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class EmailTaskPublicationServiceTest {
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final EmailTaskPublisher publisher = mock(EmailTaskPublisher.class);
    private final Clock clock = mock(Clock.class);
    private final EmailTaskPublicationService service = new EmailTaskPublicationService(deliveries, publisher, clock);
    private final Instant now = Instant.parse("2026-10-15T04:00:00Z");
    private final EmailDelivery delivery = new EmailDelivery();

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(now);
        delivery.setId(1L); delivery.setCreatedAt(now); delivery.setNextAttemptAt(now);
        when(deliveries.findLockedById(1L)).thenReturn(Optional.of(delivery));
    }

    @Test void ackMarksOnlyPublicationAndDoesNotRepublishConfirmedTask() {
        service.publish(1L); service.publish(1L);
        verify(publisher).publish(any());
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
        assertThat(delivery.getPublishedAt()).isEqualTo(now);
        assertThat(delivery.getPublicationAttempts()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
        assertThat(delivery.getAttempts()).isZero();
        assertThat(delivery.getSentAt()).isNull();
    }

    @ParameterizedTest @EnumSource(Reason.class)
    void failureRemainsPendingAndPersistsIndependentRetry(Reason reason) {
        doThrow(new EmailPublicationException(reason)).when(publisher).publish(any());
        service.publish(1L);
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.PENDING);
        assertThat(delivery.getPublicationAttempts()).isEqualTo(1);
        assertThat(delivery.getPublicationError()).isEqualTo(reason.name());
        assertThat(delivery.getNextPublicationAt()).isEqualTo(now.plusSeconds(30));
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
        assertThat(delivery.getAttempts()).isZero();
        assertThat(delivery.getPublishedAt()).isNull();
    }

    @Test void recoveredPublicationKeepsDeliveryAndMessageIdAfterBackoff() {
        doThrow(new EmailPublicationException(Reason.CONFIRM_TIMEOUT)).doNothing().when(publisher).publish(any());
        service.publish(1L);
        service.publish(1L);
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        service.publish(1L);
        var messages = ArgumentCaptor.forClass(EmailRequestedEnvelope.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues().get(0)).isEqualTo(messages.getAllValues().get(1));
        assertThat(messages.getValue().data().deliveryId()).isEqualTo(1L);
        assertThat(delivery.getPublicationAttempts()).isEqualTo(2);
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
        assertThat(delivery.getNextPublicationAt()).isNull();
        assertThat(delivery.getPublicationError()).isNull();
    }

    @Test void existingRowsWithNullPublicationFieldsArePublishable() {
        delivery.setPublicationStatus(null); delivery.setPublicationAttempts(null);
        delivery.setCreatedAt(null); delivery.setNextPublicationAt(null);
        service.publish(1L);
        assertThat(delivery.getCreatedAt()).isEqualTo(now);
        assertThat(delivery.getPublicationAttempts()).isEqualTo(1);
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
    }

    @ParameterizedTest @EnumSource(value = EmailDelivery.Status.class, names = {"SENT", "SKIPPED", "FAILED"})
    void finalizedDeliveriesAreNeverPublished(EmailDelivery.Status status) {
        delivery.setStatus(status);
        service.publish(1L);
        verifyNoInteractions(publisher);
        assertThat(delivery.getPublicationAttempts()).isZero();
    }

    @Test void missingDeliveryDoesNothing() {
        service.publish(99L);
        verifyNoInteractions(publisher);
    }

    @Test void publicationNeverOverwritesSendState() {
        doAnswer(invocation -> {
            delivery.setStatus(EmailDelivery.Status.SENT);
            delivery.setSentAt(now);
            return null;
        }).when(publisher).publish(any());
        service.publish(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SENT);
        assertThat(delivery.getSentAt()).isEqualTo(now);
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
    }

    @Test void repeatedFailuresKeepRecoverableStateAndCapBackoff() {
        delivery.setPublicationAttempts(Integer.MAX_VALUE);
        doThrow(new EmailPublicationException(Reason.BROKER_UNAVAILABLE)).when(publisher).publish(any());
        service.publish(1L);
        assertThat(delivery.getPublicationAttempts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(delivery.getNextPublicationAt()).isEqualTo(now.plusSeconds(900));
        assertThat(delivery.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.PENDING);
    }
}
