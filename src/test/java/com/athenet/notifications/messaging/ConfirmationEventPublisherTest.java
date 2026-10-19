package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import com.athenet.notifications.config.*;
import com.athenet.notifications.exception.ConfirmationPublicationException;
import com.athenet.notifications.exception.ConfirmationPublicationException.Reason;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmationEventPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NewsletterProperties properties = new NewsletterProperties();
    private final ConfirmationEventPublisher publisher = new ConfirmationEventPublisher(template, mapper, properties);
    private final Instant now = Instant.parse("2026-10-16T14:00:00Z");
    private final SubscriptionConfirmedEnvelope envelope = envelope();

    @Test void publishesPersistentHistoricalEventWithOnlyAgreedFields() throws Exception {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            var headers = message.getMessageProperties();
            assertThat(headers.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(headers.getContentType()).isEqualTo("application/json");
            assertThat(headers.getContentEncoding()).isEqualTo("UTF-8");
            assertThat(headers.getMessageId()).isEqualTo(envelope.messageId());
            assertThat(headers.getType()).isEqualTo("subscription.confirmed");
            assertThat(headers.getTimestamp()).isEqualTo(Date.from(now));
            assertThat(headers.getHeaders()).doesNotContainKey("__TypeId__");
            var json = mapper.readTree(message.getBody());
            assertThat(json.size()).isEqualTo(4);
            assertThat(json.path("messageId").asText()).isEqualTo(envelope.messageId());
            assertThat(json.path("occurredAt").asText()).isEqualTo(now.toString());
            assertThat(json.path("type").asText()).isEqualTo("subscription.confirmed");
            assertThat(json.path("data").size()).isEqualTo(3);
            assertThat(json.path("data").path("subscriberId").asLong()).isEqualTo(3);
            assertThat(json.path("data").path("generation").asLong()).isEqualTo(2);
            assertThat(json.path("data").path("confirmedAt").asText()).isEqualTo(now.toString());
            assertThat(json.toString()).doesNotContain("email", "token");
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq("athenet.topic"), eq("subscription.confirmed"), any(Message.class), any(CorrelationData.class));
        publisher.publish(envelope);
        verify(template).send(eq("athenet.topic"), eq("subscription.confirmed"), any(Message.class), any(CorrelationData.class));
    }

    @Test void ackWithReturnRemainsUnpublished() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE",
                    RabbitConfig.EXCHANGE, RabbitConfig.CONFIRMATION_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.UNROUTABLE);
    }

    @Test void nackDoesNotCountAsPublished() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "private broker details"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.NACK);
    }

    @Test void brokerFailureIsSummarizedWithoutSensitiveCause() {
        doThrow(new AmqpException("private broker details")).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.BROKER_UNAVAILABLE);
    }

    @Test void missingConfirmHasBoundedWait() {
        properties.setPublishConfirmTimeout(Duration.ofMillis(1));
        assertReason(Reason.CONFIRM_TIMEOUT);
    }

    @Test void exceptionalConfirmIsRecoverable() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().completeExceptionally(new IllegalStateException("private broker details"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertReason(Reason.CONFIRM_FAILED);
    }

    @Test void interruptionIsPreserved() {
        try {
            Thread.currentThread().interrupt();
            assertReason(Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void serializationFailureNeverSendsAMessage() throws Exception {
        var broken = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("private broker details") {}).when(broken).writeValueAsBytes(any());
        assertThatThrownBy(() -> new ConfirmationEventPublisher(template, broken, properties).publish(envelope))
                .isInstanceOfSatisfying(ConfirmationPublicationException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(Reason.INVALID_MESSAGE));
        verifyNoInteractions(template);
    }

    @Test void retriesKeepSameMessageButUseIndependentConfirmCorrelations() {
        List<String> messages = new ArrayList<>();
        List<String> correlations = new ArrayList<>();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            messages.add(message.getMessageProperties().getMessageId());
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish(envelope); publisher.publish(envelope);
        assertThat(messages).containsExactly(envelope.messageId(), envelope.messageId());
        assertThat(correlations).doesNotHaveDuplicates();
    }

    private SubscriptionConfirmedEnvelope envelope() {
        var event = new SubscriptionConfirmationEvent();
        event.setId(UUID.fromString("0759fbdf-1518-4c3b-a352-079f133c0cda"));
        event.setSubscriberId(3L); event.setSubscriberGeneration(2); event.setOccurredAt(now);
        return SubscriptionConfirmedEnvelope.forEvent(event);
    }

    private void assertReason(Reason reason) {
        assertThatThrownBy(() -> publisher.publish(envelope)).isInstanceOfSatisfying(ConfirmationPublicationException.class, ex -> {
            assertThat(ex.getReason()).isEqualTo(reason);
            assertThat(ex.getMessage()).doesNotContain("private broker details");
            assertThat(ex.getCause()).isNull();
        });
    }
}
