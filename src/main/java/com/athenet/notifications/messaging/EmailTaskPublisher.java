package com.athenet.notifications.messaging;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.config.RabbitConfig;
import com.athenet.notifications.exception.EmailPublicationException;
import com.athenet.notifications.exception.EmailPublicationException.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EmailTaskPublisher {
    private final RabbitTemplate template;
    private final ObjectMapper mapper;
    private final NewsletterProperties properties;

    public EmailTaskPublisher(@Qualifier("emailTaskRabbitTemplate") RabbitTemplate template,
                              @Qualifier("rabbitObjectMapper") ObjectMapper mapper, NewsletterProperties properties) {
        super();
        this.template = template;
        this.mapper = mapper;
        this.properties = properties;
    }

    public void publish(EmailRequestedEnvelope envelope) {
        // La correlación identifica este intento; el messageId del sobre permanece estable al reintentar.
        var correlation = new CorrelationData();
        try {
            var headers = new MessageProperties();
            headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            headers.setContentEncoding("UTF-8");
            headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            headers.setMessageId(envelope.messageId());
            headers.setType(envelope.type());
            headers.setTimestamp(Date.from(Instant.parse(envelope.occurredAt())));
            var message = new Message(mapper.writeValueAsBytes(envelope), headers);
            template.send(RabbitConfig.EXCHANGE, RabbitConfig.EMAIL_ROUTING_KEY, message, correlation);
            var confirm = correlation.getFuture().get(properties.getPublishConfirmTimeout().toNanos(), TimeUnit.NANOSECONDS);
            // Un mensaje no enrutado también puede recibir ACK: el return debe revisarse antes de aceptar.
            if (correlation.getReturned() != null) throw new EmailPublicationException(Reason.UNROUTABLE);
            if (!confirm.isAck()) throw new EmailPublicationException(Reason.NACK);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmailPublicationException(Reason.INTERRUPTED);
        } catch (TimeoutException ex) {
            throw new EmailPublicationException(Reason.CONFIRM_TIMEOUT);
        } catch (ExecutionException ex) {
            throw new EmailPublicationException(Reason.CONFIRM_FAILED);
        } catch (AmqpException ex) {
            throw new EmailPublicationException(Reason.BROKER_UNAVAILABLE);
        } catch (JsonProcessingException ex) {
            throw new EmailPublicationException(Reason.INVALID_MESSAGE);
        }
    }
}
