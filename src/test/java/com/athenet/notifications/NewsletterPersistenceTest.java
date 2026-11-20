package com.athenet.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ContextConfiguration;
import com.athenet.notifications.client.TurnstileClient;
import com.athenet.notifications.mail.MailTransport;
import com.athenet.notifications.messaging.ResultEnvelope.*;
import com.athenet.notifications.messaging.EmailTaskPublisher;
import com.athenet.notifications.messaging.EmailListener;
import com.athenet.notifications.messaging.EmailRequestedEnvelope;
import com.athenet.notifications.messaging.ConfirmationEventPublisher;
import com.athenet.notifications.exception.ConfirmationPublicationException;
import com.athenet.notifications.exception.EmailPublicationException;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import com.athenet.notifications.service.*;
import com.athenet.notifications.support.TestDatabaseGuard;

/** Requiere una BD PostgreSQL exclusiva de pruebas, nunca la BD del servicio. */
@EnabledIfEnvironmentVariable(named = "NOTIFICATIONS_DB_TEST_URL", matches = ".+")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@SpringBootTest(properties = {
        "spring.datasource.url=${NOTIFICATIONS_DB_TEST_URL}",
        "spring.datasource.username=${NOTIFICATIONS_DB_TEST_USER:notifications_user}",
        "spring.datasource.password=${NOTIFICATIONS_DB_TEST_PASSWORD:notifications_dev}",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.newsletter.worker-enabled=false", "app.newsletter.publisher-enabled=false",
        "app.newsletter.confirmation-publisher-enabled=false", "spring.rabbitmq.dynamic=false"})
class NewsletterPersistenceTest {
    @Autowired SubscriberRepository subscribers;
    @MockitoSpyBean EmailDeliveryRepository deliveries;
    @Autowired ResultStateRepository states;
    @Autowired ResultNotificationService results;
    @Autowired EventNotificationService eventNotices;
    @Autowired EventNoticeStateRepository eventStates;
    @Autowired DeliveryWorker worker;
    @Autowired SubscriptionRegistrationService registrations;
    @Autowired SubscriptionService subscriptions;
    @Autowired EmailTaskPublicationService publications;
    @Autowired EmailListener emailListener;
    @Autowired DeliveryFailureService deliveryFailures;
    @Autowired ConfirmationEventPublicationService confirmationPublications;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @PersistenceContext EntityManager entityManager;
    @MockitoSpyBean SubscriptionConfirmationEventRepository confirmationEvents;
    @MockitoBean MailTransport mail;
    @MockitoBean TurnstileClient turnstile;
    @MockitoBean EmailTaskPublisher publisher;
    @MockitoBean ConfirmationEventPublisher confirmationPublisher;

    @BeforeEach void setup() {
        confirmationEvents.deleteAll(); deliveries.deleteAll(); subscribers.deleteAll(); states.deleteAll(); eventStates.deleteAll();
        var subscriber = new Subscriber(); subscriber.setEmail("test@example.com");
        subscriber.setStatus(Subscriber.Status.ACTIVE); subscriber.setGeneration(1);
        subscriber.setConfirmedAt(Instant.parse("2026-10-13T19:00:00Z"));
        subscriber.setUnsubscribeToken("a".repeat(43)); subscribers.saveAndFlush(subscriber);
    }

    @Test void duplicateEventAndRepeatedWorkerCallProduceOneDelivery() {
        var result = result(); var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        results.accept(result, occurredAt); results.accept(result, occurredAt);
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(1);
        var id = deliveries.findAll().getFirst().getId(); worker.process(id); worker.process(id);
        assertThat(deliveries.findById(id).orElseThrow().getStatus()).isEqualTo(EmailDelivery.Status.SENT);
        assertThat(deliveries.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        verify(mail, times(1)).send(eq("test@example.com"), eq("Resultado: A 2-1 B"),
                contains("A venció 2-1 a B en Final de la Liga."));
    }

    @Test void sportPublicationIsPersistedOnceAndRepublishingDoesNotNotifyAgain() {
        var notice = com.athenet.notifications.support.EventNoticeFixtures.notice("evento.publicado", 1, "MATCH");
        var occurredAt = Instant.parse(notice.occurredAt());
        eventNotices.accept(notice, occurredAt); eventNotices.accept(notice, occurredAt);
        eventNotices.accept(com.athenet.notifications.support.EventNoticeFixtures.notice("evento.publicado", 2, "MATCH"), occurredAt);
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(eventStates.findById("EVT-42").orElseThrow().isPublicationHandled()).isTrue();
    }

    @Test void eventRecipientFailureRollsBackStateAndAllDeliveries() {
        addSubscriber("second@example.com", Subscriber.Status.ACTIVE, Instant.parse("2026-10-13T19:00:00Z"));
        var writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("storage failure");
            entityManager.persist(invocation.getArgument(0)); entityManager.flush();
            return invocation.getArgument(0);
        }).when(deliveries).save(any(EmailDelivery.class));
        var notice = com.athenet.notifications.support.EventNoticeFixtures.notice("evento.publicado", 1, "MATCH");
        assertThatThrownBy(() -> eventNotices.accept(notice, Instant.parse(notice.occurredAt()))).isInstanceOf(IllegalStateException.class);
        assertThat(eventStates.count()).isZero(); assertThat(deliveries.count()).isZero();
        verifyNoInteractions(mail, publisher);
    }

    @Test void cancellationSupersedesPendingInitialEventAndUnsubscribePreventsCancellationMail() {
        var initial = com.athenet.notifications.support.EventNoticeFixtures.notice("evento.publicado", 1, "TRACKDAY");
        eventNotices.accept(initial, Instant.parse(initial.occurredAt()));
        Long first = deliveries.findAll().getFirst().getId();
        var cancelled = com.athenet.notifications.support.EventNoticeFixtures.notice("evento.cancelado", 2, "TRACKDAY");
        eventNotices.accept(cancelled, Instant.parse(cancelled.occurredAt()));
        worker.process(first);
        assertThat(deliveries.findById(first).orElseThrow().getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        var subscriber = subscribers.findAll().getFirst(); subscriber.setStatus(Subscriber.Status.UNSUBSCRIBED); subscribers.saveAndFlush(subscriber);
        deliveries.findAll().forEach(delivery -> worker.process(delivery.getId()));
        verifyNoInteractions(mail);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secondRecipientFailureRollsBackFlushedDeliveryAndPreservesPreviousVersion(boolean existingResult) {
        var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        if (existingResult) results.accept(result(), occurredAt);
        addSubscriber("second@example.com", Subscriber.Status.ACTIVE, occurredAt.minusSeconds(60));
        long previousDeliveries = deliveries.count();
        var writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("storage failure");
            var delivery = invocation.getArgument(0, EmailDelivery.class);
            entityManager.persist(delivery);
            entityManager.flush(); // El primer destinatario llega a PostgreSQL antes del fallo del segundo.
            return delivery;
        }).when(deliveries).save(any(EmailDelivery.class));
        assertThatThrownBy(() -> results.accept(result(existingResult ? 2 : 1, 3, 1), occurredAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(writes.get()).isEqualTo(2);
        assertThat(deliveries.count()).isEqualTo(previousDeliveries);
        if (existingResult) assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(1);
        else assertThat(states.findById(1L)).isEmpty();
        verifyNoInteractions(mail, publisher);
    }

    @Test void repeatedAndOlderResultsDoNotCreateMoreDeliveries() {
        var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        results.accept(result(5, 2, 1), occurredAt);
        var originalId = deliveries.findAll().getFirst().getId();
        results.accept(result(5, 2, 1), occurredAt);
        results.accept(result(3, 3, 1), occurredAt);
        assertThat(deliveries.findAll()).extracting(EmailDelivery::getId).containsExactly(originalId);
        assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(5);
        verifyNoInteractions(mail, publisher);
    }

    @Test void recipientQueryIncludesConfirmationBoundaryAndExcludesPendingLateAndUnsubscribed() {
        var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        addSubscriber("boundary@example.com", Subscriber.Status.ACTIVE, occurredAt);
        addSubscriber("late@example.com", Subscriber.Status.ACTIVE, occurredAt.plusSeconds(1));
        addSubscriber("pending@example.com", Subscriber.Status.PENDING, null);
        addSubscriber("unsubscribed@example.com", Subscriber.Status.UNSUBSCRIBED, occurredAt.minusSeconds(60));
        results.accept(result(), occurredAt);
        assertThat(deliveries.findAll()).extracting(EmailDelivery::getRecipient)
                .containsExactlyInAnyOrder("test@example.com", "boundary@example.com");
        assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(1);
        verifyNoInteractions(mail, publisher);
    }

    @Test void unsubscriptionBeforeResultPreventsCreatingDeliveryButAdvancesVersion() {
        subscriptions.unsubscribe(subscribers.findAll().getFirst().getUnsubscribeToken());
        results.accept(result(), Instant.parse("2026-10-13T20:00:00Z"));
        assertThat(deliveries.count()).isZero();
        assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(1);
        verifyNoInteractions(mail, publisher);
    }

    @Test void unsubscriptionAfterPreparationSkipsQueuedResultWithoutSending() {
        results.accept(result(), Instant.parse("2026-10-13T20:00:00Z"));
        var row = deliveries.findAll().getFirst();
        subscriptions.unsubscribe(subscribers.findAll().getFirst().getUnsubscribeToken());
        worker.process(row.getId()); worker.process(row.getId());
        var saved = deliveries.findById(row.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getSentAt()).isNull();
        verifyNoInteractions(mail);
    }

    @Test void confirmedResubscriptionDoesNotSendResultFromPreviousGeneration() {
        results.accept(result(), Instant.parse("2026-10-13T20:00:00Z"));
        var old = deliveries.findAll().getFirst();
        subscriptions.unsubscribe(subscribers.findAll().getFirst().getUnsubscribeToken());
        subscriptions.subscribe("test@example.com", true);
        subscriptions.confirm(confirmationToken());
        var subscriber = subscribers.findAll().getFirst();
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        assertThat(subscriber.getGeneration()).isEqualTo(2);
        worker.process(old.getId());
        var saved = deliveries.findById(old.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(saved.getAttempts()).isZero();
        verifyNoInteractions(mail);
    }

    @Test void correctionSupersedesPendingResultAndOnlyLatestTaskSendsOnce() {
        var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        results.accept(result(), occurredAt);
        var old = deliveries.findAll().getFirst();
        results.accept(result(2, 3, 1), occurredAt.plusSeconds(1));
        var latest = deliveries.findAll().stream().filter(d -> d.getResultVersion() == 2).findFirst().orElseThrow();
        worker.process(old.getId()); worker.process(latest.getId()); worker.process(latest.getId());
        var skipped = deliveries.findById(old.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(EmailDelivery.Status.SKIPPED);
        assertThat(skipped.getAttempts()).isZero();
        var sent = deliveries.findById(latest.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(EmailDelivery.Status.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();
        verify(mail).send(eq("test@example.com"), eq("Resultado corregido: A 3-1 B"), anyString());
    }

    @Test void databaseUniqueConstraintPreventsDuplicateRecipientVersionEvenOutsideResultService() {
        results.accept(result(), Instant.parse("2026-10-13T20:00:00Z"));
        var duplicate = new EmailDelivery();
        org.springframework.beans.BeanUtils.copyProperties(deliveries.findAll().getFirst(), duplicate, "id");
        assertThatThrownBy(() -> deliveries.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries.count()).isEqualTo(1);
    }

    @Test void concurrentResultsOnExistingMatchSerializeAndCreateOneDelivery() throws Exception {
        var state = new ResultState(); state.setMatchId(1L); states.saveAndFlush(state);
        var occurredAt = Instant.parse("2026-10-13T20:00:00Z");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); results.accept(result(), occurredAt); });
            var second = executor.submit(() -> { await(start); results.accept(result(), occurredAt); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(states.findById(1L).orElseThrow().getLatestVersion()).isEqualTo(1);
        verifyNoInteractions(mail, publisher);
    }

    @Test void simultaneousRegistrationsReturnNormallyAndKeepOnePendingSubscriber() throws Exception {
        String email = "concurrent@example.com";
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); registrations.register(email, "token-1", "192.0.2.1"); });
            var second = executor.submit(() -> { await(start); registrations.register(email, "token-2", "192.0.2.2"); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        var found = subscribers.findAll().stream().filter(s -> email.equals(s.getEmail())).toList();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getStatus()).isEqualTo(Subscriber.Status.PENDING);
        assertThat(found.getFirst().getConsent()).isTrue();
        assertThat(found.getFirst().getConsentAcceptedAt()).isNotNull();
        assertThat(deliveries.count()).isEqualTo(2);
        verify(turnstile).verify("token-1", "192.0.2.1");
        verify(turnstile).verify("token-2", "192.0.2.2");
    }

    @Test void concurrentConfirmationsCommitOneActivationAndOneEvent() throws Exception {
        subscriptions.subscribe("confirm@example.com", true);
        String token = confirmationToken();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); subscriptions.confirm(token); });
            var second = executor.submit(() -> { await(start); subscriptions.confirm(token); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        subscriptions.confirm(token);
        var subscriber = subscribers.findAll().stream().filter(s -> "confirm@example.com".equals(s.getEmail())).findFirst().orElseThrow();
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        assertThat(subscriber.getConfirmationHash()).isEqualTo(new TokenService().hash(token));
        assertThat(confirmationEvents.count()).isEqualTo(1);
        var event = confirmationEvents.findAll().getFirst();
        assertThat(event.getSubscriberId()).isEqualTo(subscriber.getId());
        assertThat(event.getOccurredAt()).isEqualTo(subscriber.getConfirmedAt());
    }

    @Test void eventWriteFailureRollsBackActivation() {
        subscriptions.subscribe("rollback-confirm@example.com", true);
        String token = confirmationToken();
        doThrow(new IllegalStateException("event storage unavailable")).when(confirmationEvents).save(any());
        assertThatThrownBy(() -> subscriptions.confirm(token)).isInstanceOf(IllegalStateException.class);
        var subscriber = subscribers.findAll().stream().filter(s -> "rollback-confirm@example.com".equals(s.getEmail())).findFirst().orElseThrow();
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.PENDING);
        assertThat(subscriber.getConfirmedAt()).isNull();
        assertThat(confirmationEvents.count()).isZero();
    }

    @Test void confirmedPublicationPersistsWithoutMarkingEmailSent() {
        subscriptions.subscribe("publish@example.com", true);
        Long id = deliveries.findAll().getFirst().getId();
        publications.publish(id); publications.publish(id);
        var saved = deliveries.findById(id).orElseThrow();
        assertThat(saved.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
        assertThat(saved.getPublicationAttempts()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
        assertThat(saved.getAttempts()).isZero();
        verify(publisher).publish(any());
        verifyNoInteractions(mail);
    }

    @Test void publicationFailureCommitsRetryState() {
        subscriptions.subscribe("retry-publish@example.com", true);
        Long id = deliveries.findAll().getFirst().getId();
        doThrow(new EmailPublicationException(EmailPublicationException.Reason.UNROUTABLE)).when(publisher).publish(any());
        publications.publish(id);
        var saved = deliveries.findById(id).orElseThrow();
        assertThat(saved.getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.PENDING);
        assertThat(saved.getPublicationError()).isEqualTo("UNROUTABLE");
        assertThat(saved.getPublicationAttempts()).isEqualTo(1);
        assertThat(saved.getNextPublicationAt()).isAfter(Instant.now());
        assertThat(saved.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
    }

    @Test void concurrentPublishersSubmitOnlyOneConfirmedTask() throws Exception {
        subscriptions.subscribe("concurrent-publish@example.com", true);
        Long id = deliveries.findAll().getFirst().getId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); publications.publish(id); });
            var second = executor.submit(() -> { await(start); publications.publish(id); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        verify(publisher).publish(any());
        assertThat(deliveries.findById(id).orElseThrow().getPublicationStatus()).isEqualTo(EmailDelivery.PublicationStatus.CONFIRMED);
    }

    @Test void legacyNullPublicationFieldsCanBeQueriedLoadedAndPublished() {
        subscriptions.subscribe("legacy-publish@example.com", true);
        var row = deliveries.findAll().getFirst();
        row.setPublicationStatus(null); row.setPublicationAttempts(null);
        row.setCreatedAt(null); row.setNextPublicationAt(null);
        deliveries.saveAndFlush(row);
        var page = org.springframework.data.domain.PageRequest.of(0, 20);
        assertThat(deliveries.findPublicationDueIds(EmailDelivery.Status.PENDING, EmailDelivery.PublicationStatus.PENDING,
                Instant.now(), page)).contains(row.getId());
        publications.publish(row.getId());
        assertThat(deliveries.findPublicationDueIds(EmailDelivery.Status.PENDING, EmailDelivery.PublicationStatus.PENDING,
                Instant.now(), page)).doesNotContain(row.getId());
        assertThat(deliveries.findById(row.getId()).orElseThrow().getPublicationAttempts()).isEqualTo(1);
    }

    @Test void listenerRejectionDoesNotRollBackSmtpFailureState() throws Exception {
        subscriptions.subscribe("smtp-failure@example.com", true);
        var row = deliveries.findAll().getFirst();
        doThrow(new IllegalStateException("SMTP unavailable")).when(mail).send(anyString(), anyString(), anyString());
        var message = new org.springframework.amqp.core.Message(
                mapper.writeValueAsBytes(EmailRequestedEnvelope.forDelivery(row.getId(), row.getCreatedAt())),
                new org.springframework.amqp.core.MessageProperties());
        assertThatThrownBy(() -> emailListener.receive(message)).isInstanceOf(EmailDeliveryRetryException.class);
        var saved = deliveries.findById(row.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastError()).isEqualTo("IllegalStateException");
        deliveryFailures.exhausted(row.getId());
        assertThat(deliveries.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(EmailDelivery.Status.FAILED);
    }

    @Test void concurrentConsumerCallsSendOneEmail() throws Exception {
        subscriptions.subscribe("concurrent-send@example.com", true);
        var row = deliveries.findAll().getFirst();
        var message = new org.springframework.amqp.core.Message(
                mapper.writeValueAsBytes(EmailRequestedEnvelope.forDelivery(row.getId(), row.getCreatedAt())),
                new org.springframework.amqp.core.MessageProperties());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); emailListener.receive(message); });
            var second = executor.submit(() -> { await(start); emailListener.receive(message); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        verify(mail).send(eq("concurrent-send@example.com"), anyString(), anyString());
        assertThat(deliveries.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(EmailDelivery.Status.SENT);
    }

    @Test void confirmedEventPublishesOnceAndDoesNotReactivateAfterUnsubscription() {
        subscriptions.subscribe("confirmation-event@example.com", true);
        String token = confirmationToken();
        subscriptions.confirm(token); subscriptions.confirm(token);
        var event = confirmationEvents.findAll().getFirst();
        var subscriber = subscribers.findById(event.getSubscriberId()).orElseThrow();
        subscriptions.unsubscribe(subscriber.getUnsubscribeToken());
        confirmationPublications.publish(event.getId()); confirmationPublications.publish(event.getId());
        assertThat(confirmationEvents.count()).isEqualTo(1);
        var saved = confirmationEvents.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PUBLISHED);
        assertThat(saved.getPublicationAttempts()).isEqualTo(1);
        assertThat(saved.getPublishedAt()).isNotNull();
        assertThat(subscribers.findById(event.getSubscriberId()).orElseThrow().getStatus())
                .isEqualTo(Subscriber.Status.UNSUBSCRIBED);
        assertThat(deliveries.count()).isEqualTo(1);
        verify(confirmationPublisher).publish(any());
        verifyNoInteractions(mail);
    }

    @Test void legacyConfirmationFailureCommitsRetryAndRemainsRecoverable() {
        subscriptions.subscribe("legacy-confirmation-event@example.com", true);
        subscriptions.confirm(confirmationToken());
        var event = confirmationEvents.findAll().getFirst();
        event.setPublicationAttempts(null); event.setNextPublicationAt(null);
        confirmationEvents.saveAndFlush(event);
        var page = org.springframework.data.domain.PageRequest.of(0, 20);
        assertThat(confirmationEvents.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING,
                Instant.now(), page)).contains(event.getId());
        doThrow(new ConfirmationPublicationException(ConfirmationPublicationException.Reason.UNROUTABLE))
                .when(confirmationPublisher).publish(any());
        confirmationPublications.publish(event.getId());
        var saved = confirmationEvents.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PENDING);
        assertThat(saved.getPublicationAttempts()).isEqualTo(1);
        assertThat(saved.getPublicationError()).isEqualTo("UNROUTABLE");
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getNextPublicationAt()).isAfter(Instant.now());
        assertThat(confirmationEvents.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING,
                Instant.now(), page)).doesNotContain(event.getId());
        assertThat(confirmationEvents.findPublicationDueIds(SubscriptionConfirmationEvent.Status.PENDING,
                saved.getNextPublicationAt().plusSeconds(1), page)).contains(event.getId());
    }

    @Test void concurrentConfirmationPublishersCommitOnlyOnePublication() throws Exception {
        subscriptions.subscribe("concurrent-confirmation-event@example.com", true);
        subscriptions.confirm(confirmationToken());
        var id = confirmationEvents.findAll().getFirst().getId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); confirmationPublications.publish(id); });
            var second = executor.submit(() -> { await(start); confirmationPublications.publish(id); });
            start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        verify(confirmationPublisher).publish(any());
        assertThat(confirmationEvents.findById(id).orElseThrow().getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PUBLISHED);
    }

    private String confirmationToken() {
        var matcher = java.util.regex.Pattern.compile("[?&]token=([A-Za-z0-9_-]{43})")
                .matcher(deliveries.findAll().stream().filter(d -> d.getKind() == EmailDelivery.Kind.CONFIRMATION)
                        .findFirst().orElseThrow().getBody());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static void await(CountDownLatch start) {
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("No se liberó el inicio concurrente"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    private Subscriber addSubscriber(String email, Subscriber.Status status, Instant confirmedAt) {
        var subscriber = new Subscriber();
        subscriber.setEmail(email); subscriber.setStatus(status); subscriber.setConfirmedAt(confirmedAt);
        subscriber.setGeneration(1); subscriber.setUnsubscribeToken(new TokenService().generate());
        return subscribers.saveAndFlush(subscriber);
    }

    private ResultData result() { return result(1, 2, 1); }
    private ResultData result(int version, int homeScore, int awayScore) {
        return new ResultData(1L, 2L, 3L, null, version, 10L, 11L, 20L, 21L, homeScore, awayScore, false,
                new Points(3, 1, 0), "Liga", "A", "B", "Final");
    }
}
