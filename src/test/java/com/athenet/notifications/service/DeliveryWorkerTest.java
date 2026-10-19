package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.mail.MailTransport;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;

class DeliveryWorkerTest {
    private final Instant now = Instant.parse("2026-10-13T20:00:00Z");
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final ResultStateRepository states = mock(ResultStateRepository.class);
    private final EventNoticeStateRepository eventStates = mock(EventNoticeStateRepository.class);
    private final MailTransport mail = mock(MailTransport.class);
    private final Clock clock = mock(Clock.class);
    private final DeliveryWorker worker = new DeliveryWorker(deliveries, subscribers, states, eventStates, mail,
            new NewsletterProperties(), clock);
    private final EmailDelivery delivery = new EmailDelivery();
    private final Subscriber subscriber = new Subscriber();

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(now);
        subscriber.setId(1L); subscriber.setGeneration(1); subscriber.setStatus(Subscriber.Status.ACTIVE);
        delivery.setId(1L); delivery.setSubscriberId(1L); delivery.setSubscriberGeneration(1);
        delivery.setKind(EmailDelivery.Kind.RESULT); delivery.setNextAttemptAt(now);
        delivery.setMatchId(10L); delivery.setResultVersion(1);
        delivery.setRecipient("test@example.com"); delivery.setSubject("Resultado"); delivery.setBody("A 2–1 B");
        var state = new ResultState(); state.setMatchId(10L); state.setLatestVersion(1);
        when(deliveries.findLockedById(1L)).thenReturn(Optional.of(delivery));
        when(subscribers.findLockedById(1L)).thenReturn(Optional.of(subscriber));
        when(states.findById(10L)).thenReturn(Optional.of(state));
    }

    @Test void sendsOnceAndMarksSent() {
        worker.process(1L);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        worker.process(1L);
        verify(mail, times(1)).send("test@example.com", "Resultado", "A 2–1 B");
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SENT);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getSentAt()).isEqualTo(now);
    }

    @Test void eventNoticeUsesExistingTransportAndIsSentOnce() {
        delivery.setKind(EmailDelivery.Kind.EVENT);
        delivery.setEventInternalId("EVT-42"); delivery.setEventVersion(2L);
        var state = new EventNoticeState(); state.setLatestVersion(2); state.setSporting(true);
        when(eventStates.findById("EVT-42")).thenReturn(Optional.of(state));
        worker.process(1L); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SENT);
        verify(mail, times(1)).send(anyString(), anyString(), anyString());
        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNSUBSCRIBED", "GENERATION", "SUPERSEDED", "MISSING", "NON_SPORT"})
    void invalidOrSupersededEventNoticeIsSkipped(String reason) {
        delivery.setKind(EmailDelivery.Kind.EVENT);
        delivery.setEventInternalId("EVT-42"); delivery.setEventVersion(1L);
        var state = new EventNoticeState(); state.setLatestVersion(reason.equals("SUPERSEDED") ? 2 : 1);
        state.setSporting(!reason.equals("NON_SPORT"));
        when(eventStates.findById("EVT-42")).thenReturn(reason.equals("MISSING") ? Optional.empty() : Optional.of(state));
        if (reason.equals("UNSUBSCRIBED")) subscriber.setStatus(Subscriber.Status.UNSUBSCRIBED);
        if (reason.equals("GENERATION")) subscriber.setGeneration(2);
        worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(delivery.getAttempts()).isZero();
        verifyNoInteractions(mail);
    }

    @ParameterizedTest
    @EnumSource(value = EmailDelivery.Status.class, names = {"SENT", "SKIPPED"})
    void completedTaskRepetitionPreservesAttemptsAndSentDateWithoutCheckingRecipient(EmailDelivery.Status status) {
        delivery.setStatus(status);
        delivery.setAttempts(status == EmailDelivery.Status.SENT ? 1 : 0);
        delivery.setSentAt(status == EmailDelivery.Status.SENT ? now.minusSeconds(60) : null);
        int attempts = delivery.getAttempts();
        var sentAt = delivery.getSentAt();
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(delivery.getStatus()).isEqualTo(status);
        assertThat(delivery.getAttempts()).isEqualTo(attempts);
        assertThat(delivery.getSentAt()).isEqualTo(sentAt);
        verifyNoInteractions(subscribers, states, mail);
    }

    @Test void pendingSubscriberCannotReceiveQueuedResult() {
        subscriber.setStatus(Subscriber.Status.PENDING);
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(delivery.getAttempts()).isZero();
        assertThat(delivery.getSentAt()).isNull();
        verifyNoInteractions(mail);
    }

    @Test void missingSubscriberCannotReceiveQueuedResult() {
        when(subscribers.findLockedById(1L)).thenReturn(Optional.empty());
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(delivery.getAttempts()).isZero();
        verifyNoInteractions(mail);
    }

    @Test void unsubscribedRecipientIsSkipped() {
        subscriber.setStatus(Subscriber.Status.UNSUBSCRIBED); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED); verifyNoInteractions(mail);
    }

    @Test void resubscriptionDoesNotSendOldMail() {
        subscriber.setGeneration(2); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED); verifyNoInteractions(mail);
    }

    @Test void newerCorrectionSupersedesPendingOldResult() {
        var state = new ResultState(); state.setLatestVersion(2);
        when(states.findById(10L)).thenReturn(Optional.of(state)); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED); verifyNoInteractions(mail);
    }

    @Test void smtpFailureSchedulesOnlyThisDeliveryForRetry() {
        doThrow(new IllegalStateException("failed")).when(mail).send(anyString(), anyString(), anyString());
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.RETRY);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(delivery.getLastError()).isEqualTo("IllegalStateException");
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DEFERRED);
        verify(mail, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test void fifthFailureStopsAutomaticRetries() {
        delivery.setAttempts(4);
        doThrow(new IllegalStateException("failed")).when(mail).send(anyString(), anyString(), anyString());
        worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.FAILED);
    }

    @Test void expiredConfirmationIsNeverSent() {
        delivery.setKind(EmailDelivery.Kind.CONFIRMATION); delivery.setConfirmationHash("hash");
        subscriber.setStatus(Subscriber.Status.PENDING); subscriber.setConfirmationHash("hash");
        subscriber.setConfirmationExpiresAt(now.minusSeconds(1)); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED); verifyNoInteractions(mail);
    }

    @Test void validPendingConfirmationIsSent() {
        delivery.setKind(EmailDelivery.Kind.CONFIRMATION); delivery.setConfirmationHash("hash");
        subscriber.setStatus(Subscriber.Status.PENDING); subscriber.setConfirmationHash("hash");
        subscriber.setConfirmationExpiresAt(now.plusSeconds(60)); worker.process(1L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SENT);
    }

    @Test void aRetryCanSucceedAndThenDuplicatesDoNotSendAgain() {
        doThrow(new IllegalStateException("failed")).doNothing().when(mail).send(anyString(), anyString(), anyString());
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.RETRY);
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(delivery.getAttempts()).isEqualTo(2);
        assertThat(delivery.getLastError()).isNull();
        verify(mail, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test void persistentSmtpFailureStopsAtFiveRecordedAttempts() {
        doThrow(new IllegalStateException("failed")).when(mail).send(anyString(), anyString(), anyString());
        for (int attempt = 1; attempt <= 5; attempt++) {
            when(clock.instant()).thenReturn(delivery.getNextAttemptAt());
            assertThat(worker.process(1L)).isEqualTo(attempt == 5 ? DeliveryWorker.Outcome.FAILED : DeliveryWorker.Outcome.RETRY);
        }
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.FAILED);
        verify(mail, times(5)).send(anyString(), anyString(), anyString());
    }

    @Test void alreadyExhaustedPendingRowDoesNotSendAgain() {
        delivery.setAttempts(5);
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.FAILED);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.FAILED);
        verifyNoInteractions(mail);
    }

    @Test void missingDeliveryHasAnExplicitOutcome() {
        assertThat(worker.process(99L)).isEqualTo(DeliveryWorker.Outcome.NOT_FOUND);
        verifyNoInteractions(mail);
    }

    @Test void confirmationWithoutHashOrExpiryCannotSend() {
        delivery.setKind(EmailDelivery.Kind.CONFIRMATION);
        subscriber.setStatus(Subscriber.Status.PENDING);
        assertThat(worker.process(1L)).isEqualTo(DeliveryWorker.Outcome.DONE);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        verifyNoInteractions(mail);
    }
}
