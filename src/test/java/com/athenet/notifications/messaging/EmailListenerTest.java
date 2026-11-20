package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.service.DeliveryWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.dao.DataAccessResourceFailureException;

class EmailListenerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeliveryWorker worker = mock(DeliveryWorker.class);
    private final EmailRequestedEnvelope envelope = EmailRequestedEnvelope.forDelivery(42L, Instant.parse("2026-10-16T03:00:00Z"));
    private ValidatorFactory validators;
    private EmailListener listener;

    @BeforeEach void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        listener = new EmailListener(mapper, validators.getValidator(), worker);
    }
    @AfterEach void close() { validators.close(); }

    @Test void consumesOnlyDeliveryIdAndIgnoresForeignJavaHeader() throws Exception {
        when(worker.process(42L)).thenReturn(DeliveryWorker.Outcome.DONE);
        var message = message(mapper.writeValueAsString(envelope));
        message.getMessageProperties().setHeader("__TypeId__", "untrusted.ForeignClass");
        listener.receive(message);
        verify(worker).process(42L);
    }

    @ParameterizedTest @EnumSource(value = DeliveryWorker.Outcome.class, names = {"RETRY", "DEFERRED"})
    void retryableOutcomeNeverReturnsSuccess(DeliveryWorker.Outcome outcome) throws Exception {
        when(worker.process(42L)).thenReturn(outcome);
        assertThatThrownBy(() -> listener.receive(message(mapper.writeValueAsString(envelope))))
                .isInstanceOfSatisfying(EmailDeliveryRetryException.class, ex -> assertThat(ex.getDeliveryId()).isEqualTo(42L));
    }

    @ParameterizedTest @EnumSource(value = DeliveryWorker.Outcome.class, names = {"FAILED", "NOT_FOUND"})
    void terminalFailureRejectsWithoutRequeue(DeliveryWorker.Outcome outcome) throws Exception {
        when(worker.process(42L)).thenReturn(outcome);
        assertThatThrownBy(() -> listener.receive(message(mapper.writeValueAsString(envelope))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test void transactionFailureIsRetryableWithoutLeakingItsMessage() throws Exception {
        when(worker.process(42L)).thenThrow(new DataAccessResourceFailureException("private connection details"));
        assertThatThrownBy(() -> listener.receive(message(mapper.writeValueAsString(envelope))))
                .isInstanceOf(EmailDeliveryRetryException.class).hasMessageNotContaining("private connection details");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{", "{}",
            "{\"messageId\":\"not-uuid\",\"occurredAt\":\"2026-10-16T03:00:00Z\",\"type\":\"notificacion.email.solicitado\",\"data\":{\"deliveryId\":42}}"})
    void malformedEnvelopeNeverCallsWorker(String json) {
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(worker);
    }

    @Test void invalidTimeTypeAndDeliveryIdAreRejected() throws Exception {
        String json = mapper.writeValueAsString(envelope);
        for (String invalid : new String[]{json.replace("notificacion.email.solicitado", "resultado.registrado"),
                json.replace(envelope.occurredAt(), "invalid-time"), json.replace("\"deliveryId\":42", "\"deliveryId\":0"),
                json.replace("\"deliveryId\":42", "\"deliveryId\":null")}) {
            assertThatThrownBy(() -> listener.receive(message(invalid))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        verifyNoInteractions(worker);
    }

    private Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
