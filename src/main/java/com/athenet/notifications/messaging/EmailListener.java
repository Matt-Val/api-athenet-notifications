package com.athenet.notifications.messaging;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import com.athenet.notifications.config.RabbitConfig;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.service.DeliveryWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EmailListener {
    private static final Logger log = LoggerFactory.getLogger(EmailListener.class);
    private final ObjectMapper mapper;
    private final Validator validator;
    private final DeliveryWorker worker;

    public EmailListener(@Qualifier("rabbitObjectMapper") ObjectMapper mapper, Validator validator, DeliveryWorker worker) {
        super();
        this.mapper = mapper;
        this.validator = validator;
        this.worker = worker;
    }

    @RabbitListener(queues = RabbitConfig.EMAIL_QUEUE, containerFactory = "emailRabbitListenerContainerFactory")
    public void receive(Message message) {
        EmailRequestedEnvelope envelope;
        try {
            // Se procesa JSON explícito: el header Java __TypeId__ no selecciona clases.
            envelope = mapper.readValue(message.getBody(), EmailRequestedEnvelope.class);
            if (envelope == null || !validator.validate(envelope).isEmpty()) throw invalidMessage();
            if (!UUID.fromString(envelope.messageId()).toString().equalsIgnoreCase(envelope.messageId())) throw invalidMessage();
            Instant.parse(envelope.occurredAt());
        } catch (IOException | IllegalArgumentException | DateTimeParseException ex) {
            throw invalidMessage();
        }
        Long id = envelope.data().deliveryId();
        DeliveryWorker.Outcome outcome;
        try { outcome = worker.process(id); }
        catch (RuntimeException ex) {
            log.warn("No se pudo completar transacción de entrega {} (tipo {})", id, ex.getClass().getSimpleName());
            throw new EmailDeliveryRetryException(id);
        }
        // process() pertenece a otro bean: al llegar aquí el estado ya se confirmó en RDS.
        switch (outcome) {
            case RETRY, DEFERRED -> throw new EmailDeliveryRetryException(id);
            case FAILED, NOT_FOUND -> throw new AmqpRejectAndDontRequeueException("Entrega agotada o inexistente: " + id);
            case DONE -> { }
        }
    }

    private static AmqpRejectAndDontRequeueException invalidMessage() {
        return new AmqpRejectAndDontRequeueException("Contrato de tarea de correo inválido.");
    }
}
