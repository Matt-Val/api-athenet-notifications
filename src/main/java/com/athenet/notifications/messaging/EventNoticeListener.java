package com.athenet.notifications.messaging;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.athenet.notifications.config.EventRabbitConfig;
import com.athenet.notifications.service.EventNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.newsletter.events-enabled", havingValue = "true")
public class EventNoticeListener {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final EventNotificationService events;

    public EventNoticeListener(ObjectMapper rabbitObjectMapper, Validator validator, EventNotificationService events) {
        this.mapper = rabbitObjectMapper; this.validator = validator; this.events = events;
    }

    @RabbitListener(queues = EventRabbitConfig.QUEUE)
    public void receive(Message message) throws IOException {
        // Se lee el contrato JSON sin resolver el header de clase del productor.
        var envelope = mapper.readValue(message.getBody(), EventNoticeEnvelope.class);
        if (envelope == null || !validator.validate(envelope).isEmpty()
                || (envelope.version() == null && !envelope.type().equals("evento.publicado"))) {
            throw new IllegalArgumentException("Contrato de aviso de evento inválido");
        }
        var data = envelope.data();
        if (!envelope.type().equals("evento.cancelado") && data.status() != null && !data.status().equals("PUBLISHED")) {
            throw new IllegalArgumentException("Un aviso de publicación o modificación debe estar publicado");
        }
        if (envelope.type().equals("evento.cancelado") && "PUBLISHED".equals(data.status())) {
            throw new IllegalArgumentException("La cancelación no puede mantener estado publicado");
        }
        var start = LocalDateTime.of(LocalDate.parse(data.eventDate()), LocalTime.parse(data.startTime()));
        var end = LocalDateTime.of(LocalDate.parse(data.endDate()), LocalTime.parse(data.endTime()));
        if (!end.isAfter(start)) throw new IllegalArgumentException("El término del evento debe ser posterior al inicio");
        events.accept(envelope, Instant.parse(envelope.occurredAt()));
    }
}
