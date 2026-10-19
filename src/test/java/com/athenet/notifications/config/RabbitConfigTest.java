package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.time.Duration;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.messaging.EmailMessageRecoverer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

class RabbitConfigTest {
    @Test void springResolvesAllQueuesAndKeepsEmailAndResultRoutingIndependent() {
        var connection = mock(ConnectionFactory.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectionFactory.class, () -> connection);
            context.registerBean(NewsletterProperties.class, NewsletterProperties::new);
            context.registerBean(EmailMessageRecoverer.class, () -> mock(EmailMessageRecoverer.class));
            context.register(RabbitConfig.class);
            context.refresh();

            var queues = context.getBeansOfType(Queue.class).values();
            assertThat(context.getBeansOfType(TopicExchange.class).values()).allSatisfy(exchange -> {
                assertThat(exchange.isDurable()).isTrue();
                assertThat(exchange.isAutoDelete()).isFalse();
                assertThat(exchange.getType()).isEqualTo("topic");
            });
            assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder(
                    "notificaciones.resultados", "notificaciones.resultados.dlq",
                    "notificaciones.correos", "notificaciones.correos.dlq", "notificaciones.suscripciones.confirmadas");
            assertThat(queues).allSatisfy(queue -> {
                assertThat(queue.isDurable()).isTrue();
                assertThat(queue.isExclusive()).isFalse();
                assertThat(queue.isAutoDelete()).isFalse();
            });
            var emailQueue = context.getBean("emailQueue", Queue.class);
            assertThat(emailQueue.getArguments()).containsEntry("x-dead-letter-exchange", "athenet.dlx")
                    .containsEntry("x-dead-letter-routing-key", "notificaciones.correos");
            var emailDlq = context.getBean("emailDlq", Queue.class);
            assertThat(emailDlq.getArguments()).doesNotContainKeys("x-dead-letter-exchange", "x-dead-letter-routing-key");
            var confirmations = context.getBean("confirmationEventsQueue", Queue.class);
            assertThat(confirmations.getArguments()).isEmpty();

            var routes = context.getBeansOfType(Binding.class).values().stream().collect(
                    java.util.stream.Collectors.toMap(Binding::getDestination,
                            binding -> binding.getExchange() + "/" + binding.getRoutingKey()));
            assertThat(routes).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "notificaciones.resultados", "athenet.topic/resultado.registrado",
                    "notificaciones.resultados.dlq", "athenet.dlx/notificaciones.resultados.dlq",
                    "notificaciones.correos", "athenet.topic/notificacion.email.solicitado",
                    "notificaciones.correos.dlq", "athenet.dlx/notificaciones.correos",
                    "notificaciones.suscripciones.confirmadas", "athenet.topic/subscription.confirmed"));
        }
        verifyNoInteractions(connection);
    }

    @Test void usesAnIndependentDurableQueueAndDedicatedDeadLetterRouting() {
        var config = new RabbitConfig();
        var queue = config.resultsQueue();
        assertThat(queue.getName()).isEqualTo("notificaciones.resultados");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "athenet.dlx")
                .containsEntry("x-dead-letter-routing-key", "notificaciones.resultados.dlq");
        assertThat(config.resultsBinding(queue, config.resultsExchange()).getRoutingKey()).isEqualTo("resultado.registrado");
        assertThat(config.resultsDlqBinding(config.resultsDlq(), config.deadLetterExchange()).getRoutingKey())
                .isEqualTo("notificaciones.resultados.dlq");
        assertThat(config.resultsDlq().getArguments()).doesNotContainKeys("x-dead-letter-exchange", "x-dead-letter-routing-key");
    }

    @Test void resultRetriesUseExactlyOneTwoAndFourSecondsWithoutJitter() {
        var policy = new RabbitConfig().resultRetryPolicy();
        assertThat(policy.shouldRetry(new IllegalArgumentException("invalid contract"))).isTrue();
        assertThat(policy.shouldRetry(new IllegalStateException("database unavailable"))).isTrue();
        var delays = policy.getBackOff().start();
        assertThat(delays.nextBackOff()).isEqualTo(1_000);
        assertThat(delays.nextBackOff()).isEqualTo(2_000);
        assertThat(delays.nextBackOff()).isEqualTo(4_000);
        assertThat(delays.nextBackOff()).isEqualTo(-1);
    }

    @Test void emailRetriesAreBoundedAndOnlyApplyToRetryableDeliveryFailures() {
        var policy = new RabbitConfig().emailRetryPolicy(new NewsletterProperties());
        assertThat(policy.shouldRetry(new RuntimeException(new EmailDeliveryRetryException(1L)))).isTrue();
        assertThat(policy.shouldRetry(new AmqpRejectAndDontRequeueException("invalid"))).isFalse();
        assertThat(policy.shouldRetry(new IllegalArgumentException("invalid"))).isFalse();
        assertThat(policy.getTimeout()).isEqualTo(Duration.ZERO);
        var delays = policy.getBackOff().start();
        assertThat(delays.nextBackOff()).isEqualTo(30_000);
        assertThat(delays.nextBackOff()).isEqualTo(60_000);
        assertThat(delays.nextBackOff()).isEqualTo(120_000);
        assertThat(delays.nextBackOff()).isEqualTo(240_000);
        assertThat(delays.nextBackOff()).isEqualTo(-1);
    }
}
