package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import com.athenet.notifications.config.*;
import com.athenet.notifications.exception.EmailPublicationException;
import com.athenet.notifications.exception.EmailPublicationException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class EmailTaskPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NewsletterProperties properties = new NewsletterProperties();
    private final EmailTaskPublisher publisher = new EmailTaskPublisher(template, mapper, properties);
    private final Instant now = Instant.parse("2026-10-15T04:00:00Z");
    private final EmailRequestedEnvelope envelope = EmailRequestedEnvelope.forDelivery(42L, now);

    @Test void sendsPersistentJsonContainingOnlyDeliveryIdAndWaitsForAck() throws Exception {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(envelope.messageId());
            var json = mapper.readTree(message.getBody());
            assertThat(json.path("data").size()).isEqualTo(1);
            assertThat(json.path("data").path("deliveryId").asLong()).isEqualTo(42);
            assertThat(json.path("type").asText()).isEqualTo("notificacion.email.solicitado");
            assertThat(json.path("occurredAt").asText()).isEqualTo(now.toString());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq("athenet.topic"), eq("notificacion.email.solicitado"), any(Message.class), any(CorrelationData.class));
        publisher.publish(envelope);
        verify(template).send(eq("athenet.topic"), eq("notificacion.email.solicitado"), any(Message.class), any(CorrelationData.class));
    }

    @Test void ackWithReturnedMessageIsStillAFailure() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE",
                    RabbitConfig.EXCHANGE, RabbitConfig.EMAIL_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.UNROUTABLE);
    }

    @Test void brokerNackDoesNotCountAsPublished() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "private broker details"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.NACK);
    }

    @Test void connectionFailureDoesNotExposeBrokerDetails() {
        doThrow(new AmqpException("private broker details")).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.BROKER_UNAVAILABLE);
    }

    @Test void missingConfirmationTimesOut() {
        properties.setPublishConfirmTimeout(Duration.ofMillis(1));
        assertReason(Reason.CONFIRM_TIMEOUT);
    }

    @Test void exceptionalConfirmationIsRecoverable() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().completeExceptionally(new IllegalStateException("private broker details"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.CONFIRM_FAILED);
    }

    @Test void interruptedWaitRestoresThreadInterruptStatus() {
        try {
            Thread.currentThread().interrupt();
            assertReason(Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void retriesReuseMessageIdentityWithIndependentConfirmCorrelations() {
        List<String> messageIds = new ArrayList<>();
        List<String> correlations = new ArrayList<>();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            messageIds.add(message.getMessageProperties().getMessageId());
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish(EmailRequestedEnvelope.forDelivery(42L, now));
        publisher.publish(EmailRequestedEnvelope.forDelivery(42L, now));
        assertThat(messageIds).containsExactly(envelope.messageId(), envelope.messageId());
        assertThat(correlations).doesNotHaveDuplicates();
    }

    private void assertReason(Reason reason) {
        assertThatThrownBy(() -> publisher.publish(envelope)).isInstanceOfSatisfying(EmailPublicationException.class, ex -> {
            assertThat(ex.getReason()).isEqualTo(reason);
            assertThat(ex.getMessage()).doesNotContain("private broker details");
            assertThat(ex.getCause()).isNull();
        });
    }
}
