package com.athenet.notifications.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cola propia; el listener reutiliza la política de reintentos 1/2/4 s del consumidor externo. */
@Configuration
@ConditionalOnProperty(name = "app.newsletter.events-enabled", havingValue = "true")
public class EventRabbitConfig {
    public static final String QUEUE = "notificaciones.eventos";
    public static final String DLQ = QUEUE + ".dlq";
    @Bean Declarables eventDeclarations() {
        var exchange = new TopicExchange(RabbitConfig.EXCHANGE, true, false);
        var dlx = new TopicExchange(RabbitConfig.DLX, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(RabbitConfig.DLX).deadLetterRoutingKey(DLQ).build();
        var dead = QueueBuilder.durable(DLQ).build();
        return new Declarables(queue, dead,
                BindingBuilder.bind(queue).to(exchange).with("evento.publicado"),
                BindingBuilder.bind(queue).to(exchange).with("evento.modificado"),
                BindingBuilder.bind(queue).to(exchange).with("evento.cancelado"),
                BindingBuilder.bind(dead).to(dlx).with(DLQ));
    }
}
