package com.athenet.notifications.messaging;

import java.io.IOException;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.athenet.notifications.config.RabbitConfig;
import com.athenet.notifications.service.ResultNotificationService;

@Component
public class ResultListener {
    private final ObjectMapper rabbitObjectMapper;
    private final Validator validator;
    private final ResultNotificationService results;

    public ResultListener(ObjectMapper rabbitObjectMapper, Validator validator, ResultNotificationService results) {
        super();
        this.rabbitObjectMapper = rabbitObjectMapper;
        this.validator = validator;
        this.results = results;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void receive(Message message) throws IOException {
        // Se ignora __TypeId__: el contrato JSON es independiente de las clases del productor.
        var envelope = rabbitObjectMapper.readValue(message.getBody(), ResultEnvelope.class);
        if (envelope == null || !validator.validate(envelope).isEmpty()) {
            throw new IllegalArgumentException("Contrato de resultado inválido");
        }
        results.accept(envelope.data(), Instant.parse(envelope.occurredAt()));
    }
}
