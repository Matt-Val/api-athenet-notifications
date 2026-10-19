package com.athenet.notifications.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import java.time.Duration;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.messaging.EmailMessageRecoverer;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "athenet.topic";
    public static final String ROUTING_KEY = "resultado.registrado";
    public static final String QUEUE = "notificaciones.resultados";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String DLX = "athenet.dlx";
    public static final String EMAIL_ROUTING_KEY = "notificacion.email.solicitado";
    public static final String EMAIL_QUEUE = "notificaciones.correos";
    public static final String EMAIL_DLQ = EMAIL_QUEUE + ".dlq";
    public static final String CONFIRMATION_ROUTING_KEY = "subscription.confirmed";
    public static final String CONFIRMATION_QUEUE = "notificaciones.suscripciones.confirmadas";

    @Bean TopicExchange resultsExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean TopicExchange deadLetterExchange() { return new TopicExchange(DLX, true, false); }
    @Bean Queue resultsQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DLQ).build();
    }
    @Bean Queue resultsDlq() { return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding resultsBinding(Queue resultsQueue, TopicExchange resultsExchange) {
        return BindingBuilder.bind(resultsQueue).to(resultsExchange).with(ROUTING_KEY);
    }
    @Bean Binding resultsDlqBinding(Queue resultsDlq, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(resultsDlq).to(deadLetterExchange).with(DLQ);
    }
    @Bean Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(EMAIL_QUEUE).build();
    }
    @Bean Queue emailDlq() { return QueueBuilder.durable(EMAIL_DLQ).build(); }
    @Bean Binding emailBinding(@Qualifier("emailQueue") Queue queue,
                              @Qualifier("resultsExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(EMAIL_ROUTING_KEY);
    }
    @Bean Binding emailDlqBinding(@Qualifier("emailDlq") Queue queue,
                                 @Qualifier("deadLetterExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(EMAIL_QUEUE);
    }
    @Bean RabbitTemplate emailTaskRabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setUsePublisherConnection(true);
        return template;
    }
    // Conserva los eventos de dominio hasta que se acuerde su consumidor; no dispara SMTP.
    @Bean Queue confirmationEventsQueue() { return QueueBuilder.durable(CONFIRMATION_QUEUE).build(); }
    @Bean Binding confirmationEventsBinding(@Qualifier("confirmationEventsQueue") Queue queue,
                                            @Qualifier("resultsExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(CONFIRMATION_ROUTING_KEY);
    }
    @Bean RabbitTemplate confirmationEventRabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setUsePublisherConnection(true);
        return template;
    }
    @Bean RetryPolicy resultRetryPolicy() {
        return RetryPolicy.builder().maxRetries(3).delay(Duration.ofSeconds(1)).jitter(Duration.ZERO)
                .multiplier(2).maxDelay(Duration.ofSeconds(4)).build();
    }

    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
            @Qualifier("resultRetryPolicy") RetryPolicy policy,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryPolicy(policy)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }

    @Bean RetryPolicy emailRetryPolicy(NewsletterProperties properties) {
        return RetryPolicy.builder().maxRetries(properties.getMaxAttempts() - 1L)
                .delay(Duration.ofSeconds(30)).jitter(Duration.ZERO).multiplier(2).maxDelay(Duration.ofSeconds(1920))
                .predicate(failure -> EmailDeliveryRetryException.findIn(failure) != null).build();
    }

    @Bean SimpleRabbitListenerContainerFactory emailRabbitListenerContainerFactory(ConnectionFactory connectionFactory,
            @Qualifier("emailRetryPolicy") RetryPolicy policy, EmailMessageRecoverer recoverer,
            @Value("${app.newsletter.worker-enabled:false}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryPolicy(policy).recoverer(recoverer).build());
        return factory;
    }
}
