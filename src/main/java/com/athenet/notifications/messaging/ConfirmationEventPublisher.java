package com.athenet.notifications.messaging;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.config.RabbitConfig;
import com.athenet.notifications.exception.ConfirmationPublicationException;
import com.athenet.notifications.exception.ConfirmationPublicationException.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ConfirmationEventPublisher {
    private final RabbitTemplate template;
    private final ObjectMapper mapper;
    private final NewsletterProperties properties;

    public ConfirmationEventPublisher(@Qualifier("confirmationEventRabbitTemplate") RabbitTemplate template,
                                      @Qualifier("rabbitObjectMapper") ObjectMapper mapper, NewsletterProperties properties) {
        super();
        this.template = template;
        this.mapper = mapper;
        this.properties = properties;
    }

    public void publish(SubscriptionConfirmedEnvelope envelope) {
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
            template.send(RabbitConfig.EXCHANGE, RabbitConfig.CONFIRMATION_ROUTING_KEY, message, correlation);
            var confirm = correlation.getFuture().get(properties.getPublishConfirmTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (correlation.getReturned() != null) throw new ConfirmationPublicationException(Reason.UNROUTABLE);
            if (!confirm.ack()) throw new ConfirmationPublicationException(Reason.NACK);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConfirmationPublicationException(Reason.INTERRUPTED);
        } catch (TimeoutException ex) {
            throw new ConfirmationPublicationException(Reason.CONFIRM_TIMEOUT);
        } catch (ExecutionException ex) {
            throw new ConfirmationPublicationException(Reason.CONFIRM_FAILED);
        } catch (AmqpException ex) {
            throw new ConfirmationPublicationException(Reason.BROKER_UNAVAILABLE);
        } catch (JsonProcessingException ex) {
            throw new ConfirmationPublicationException(Reason.INVALID_MESSAGE);
        }
    }
}
