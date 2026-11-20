package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.mail.MailTransport;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import com.athenet.notifications.service.DeliveryWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class EmailConsumerTransactionTest {
    @Test void listenerRequestsRetryOnlyAfterWorkerTransactionHasCommitted() throws Exception {
        var deliveries = mock(EmailDeliveryRepository.class);
        var subscribers = mock(SubscriberRepository.class);
        var states = mock(ResultStateRepository.class);
        var mail = mock(MailTransport.class);
        var now = Instant.parse("2026-10-16T03:00:00Z");
        var subscriber = new Subscriber(); subscriber.setId(1L); subscriber.setGeneration(1);
        subscriber.setStatus(Subscriber.Status.PENDING); subscriber.setConfirmationHash("hash");
        subscriber.setConfirmationExpiresAt(now.plusSeconds(60));
        var delivery = new EmailDelivery(); delivery.setId(42L); delivery.setSubscriberId(1L);
        delivery.setSubscriberGeneration(1); delivery.setKind(EmailDelivery.Kind.CONFIRMATION);
        delivery.setConfirmationHash("hash"); delivery.setNextAttemptAt(now);
        delivery.setRecipient("test@example.com"); delivery.setSubject("Confirmar"); delivery.setBody("Mensaje");
        when(deliveries.findLockedById(42L)).thenReturn(Optional.of(delivery));
        when(subscribers.findLockedById(1L)).thenReturn(Optional.of(subscriber));
        doThrow(new IllegalStateException("SMTP failed")).when(mail).send(anyString(), anyString(), anyString());
        var transactions = new RecordingTransactions();
        var worker = new DeliveryWorker(deliveries, subscribers, states,
                mock(com.athenet.notifications.repository.EventNoticeStateRepository.class), mail,
                new NewsletterProperties(), Clock.fixed(now, ZoneOffset.UTC));
        var proxy = new ProxyFactory(worker);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var mapper = new ObjectMapper();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var listener = new EmailListener(mapper, validators.getValidator(), (DeliveryWorker) proxy.getProxy());
            var message = new Message(mapper.writeValueAsBytes(EmailRequestedEnvelope.forDelivery(42L, now)), new MessageProperties());
            assertThatThrownBy(() -> listener.receive(message)).isInstanceOf(EmailDeliveryRetryException.class);
            assertThat(transactions.commits).isEqualTo(1);
            assertThat(transactions.rollbacks).isZero();
            assertThat(delivery.getAttempts()).isEqualTo(1);
            assertThat(delivery.getLastError()).isEqualTo("IllegalStateException");
        }
    }

    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
