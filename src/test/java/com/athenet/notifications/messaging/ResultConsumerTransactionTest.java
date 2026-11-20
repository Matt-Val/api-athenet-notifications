package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import com.athenet.notifications.service.ResultNotificationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Comprueba el límite transaccional del listener; no simula persistencia ni rollback de PostgreSQL. */
class ResultConsumerTransactionTest {
    private final Instant now = Instant.parse("2026-10-16T12:00:00Z");
    private final ResultStateRepository states = mock(ResultStateRepository.class);
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final ResultState state = new ResultState();

    @Test void listenerReturnsOnlyAfterTransactionCommitsAllDeliveriesAndVersion() throws Exception {
        configureRepositories();
        var prepared = new ArrayList<EmailDelivery>();
        when(deliveries.save(any(EmailDelivery.class))).thenAnswer(invocation -> {
            assertThat(transactions.active).isTrue();
            assertThat(transactions.commits).isZero();
            assertThat(state.getLatestVersion()).isEqualTo(1);
            var delivery = invocation.getArgument(0, EmailDelivery.class);
            prepared.add(delivery);
            return delivery;
        });
        receive();
        assertThat(prepared).hasSize(2);
        assertThat(prepared).extracting(EmailDelivery::getResultVersion).containsOnly(2);
        assertThat(state.getLatestVersion()).isEqualTo(2);
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
        assertThat(transactions.active).isFalse();
    }

    @Test void failurePreparingSecondRecipientRollsBackAndReachesListenerForRetry() {
        configureRepositories();
        var calls = new AtomicInteger();
        when(deliveries.save(any(EmailDelivery.class))).thenAnswer(invocation -> {
            assertThat(transactions.active).isTrue();
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("storage failure");
            return invocation.getArgument(0);
        });
        assertThatThrownBy(this::receive).isInstanceOf(IllegalStateException.class).hasMessage("storage failure");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.active).isFalse();
        assertThat(state.getLatestVersion()).isEqualTo(1);
    }

    @Test void concurrentFirstInsertFailurePropagatesAndRetryCanDiscardAlreadyProcessedVersion() throws Exception {
        state.setMatchId(1L); state.setLatestVersion(2);
        when(states.findLockedByMatchId(1L)).thenReturn(Optional.empty(), Optional.of(state));
        when(states.saveAndFlush(any(ResultState.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent match insert"));
        assertThatThrownBy(this::receive).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        receive();
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.active).isFalse();
        verifyNoInteractions(subscribers, deliveries);
    }

    private void configureRepositories() {
        state.setMatchId(1L); state.setLatestVersion(1);
        when(states.findLockedByMatchId(1L)).thenAnswer(invocation -> {
            assertThat(transactions.active).isTrue();
            return Optional.of(state);
        });
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now))
                .thenReturn(List.of(subscriber(1L), subscriber(2L)));
    }

    private Subscriber subscriber(Long id) {
        var subscriber = new Subscriber();
        subscriber.setId(id); subscriber.setEmail("recipient" + id + "@example.com");
        subscriber.setGeneration(1); subscriber.setUnsubscribeToken("token" + id);
        return subscriber;
    }

    private void receive() throws Exception {
        var target = new ResultNotificationService(states, subscribers, deliveries, new NewsletterProperties(),
                Clock.fixed(now, ZoneOffset.UTC));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var listener = new ResultListener(mapper, validators.getValidator(), (ResultNotificationService) proxy.getProxy());
            var envelope = new ResultEnvelope("56ae47f9-3fc2-40cb-95fb-2cc28cde3adb", now.toString(),
                    "resultado.registrado", new ResultEnvelope.ResultData(1L, 1L, 5L, null, 2,
                    202L, 203L, null, null, 3, 1, false, new ResultEnvelope.Points(3, 1, 0),
                    "Copa 2026", "Equipo A", "Equipo B", "Final"));
            listener.receive(new Message(mapper.writeValueAsBytes(envelope), new MessageProperties()));
        }
    }

    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean active;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { active = true; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; active = false; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; active = false; }
    }
}
