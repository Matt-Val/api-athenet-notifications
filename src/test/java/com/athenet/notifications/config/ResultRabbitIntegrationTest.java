package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.athenet.notifications.messaging.ResultEnvelope;
import com.athenet.notifications.messaging.ResultListener;
import com.athenet.notifications.service.ResultNotificationService;
import com.athenet.notifications.support.TestRabbitGuard;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Integración opcional: RabbitMQ real exclusivo; servicio de resultados simulado, sin RDS ni SMTP. */
@EnabledIfEnvironmentVariable(named = "NOTIFICATIONS_RABBIT_TEST_ENABLED", matches = "true")
class ResultRabbitIntegrationTest {
    @Test void invalidMessageReachesOnlyItsDlqAfterRetriesAndNextValidMessageIsProcessed() throws Exception {
        var env = System.getenv();
        String host = env.getOrDefault("NOTIFICATIONS_RABBIT_TEST_HOST", "127.0.0.1");
        int port = Integer.parseInt(env.getOrDefault("NOTIFICATIONS_RABBIT_TEST_PORT", "5673"));
        String vhost = env.get("NOTIFICATIONS_RABBIT_TEST_VHOST");
        TestRabbitGuard.validate(host, port, vhost);
        var connection = new CachingConnectionFactory(host, port);
        connection.setVirtualHost(vhost);
        connection.setUsername(env.getOrDefault("NOTIFICATIONS_RABBIT_TEST_USER", "notifications_dev"));
        connection.setPassword(env.getOrDefault("NOTIFICATIONS_RABBIT_TEST_PASSWORD", "notifications_dev"));
        connection.setConnectionTimeout(5_000);
        var config = new RabbitConfig();
        var admin = new RabbitAdmin(connection);
        var legacyProbe = QueueBuilder.durable("notifications_test.legacy_probe").build();
        var otherProbe = QueueBuilder.durable("notifications_test.other_probe").build();
        var queues = List.of(config.resultsQueue(), config.resultsDlq(), legacyProbe, otherProbe);
        var container = config.rabbitListenerContainerFactory(connection, config.resultRetryPolicy(), false)
                .createListenerContainer(null);
        boolean ownsQueues = false;
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            for (var queue : queues) {
                assertThat(admin.getQueueInfo(queue.getName())).as("El vhost debe estar reservado y sin estas colas")
                        .isNull();
            }
            ownsQueues = true;
            admin.declareExchange(config.resultsExchange());
            admin.declareExchange(config.deadLetterExchange());
            for (var queue : queues) admin.declareQueue(queue);
            admin.declareBinding(config.resultsBinding(config.resultsQueue(), config.resultsExchange()));
            admin.declareBinding(config.resultsDlqBinding(config.resultsDlq(), config.deadLetterExchange()));
            admin.declareBinding(BindingBuilder.bind(legacyProbe).to(config.deadLetterExchange()).with(RabbitConfig.QUEUE));
            admin.declareBinding(BindingBuilder.bind(otherProbe).to(config.deadLetterExchange()).with("competencia.eventos.dlq"));
            var mapper = new ApplicationConfig().rabbitObjectMapper();
            var results = mock(ResultNotificationService.class);
            var listener = new ResultListener(mapper, validators.getValidator(), results);
            var attempts = new AtomicInteger();
            var validProcessed = new CountDownLatch(1);
            container.setQueues(config.resultsQueue());
            container.setShutdownTimeout(5_000);
            container.setMessageListener((MessageListener) message -> {
                boolean invalid = Arrays.equals(message.getBody(), "{}".getBytes(StandardCharsets.UTF_8));
                if (invalid) attempts.incrementAndGet();
                try { listener.receive(message); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
                if (!invalid) validProcessed.countDown();
            });
            container.start();
            var template = new RabbitTemplate(connection);
            var invalidProperties = new MessageProperties();
            invalidProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            template.send(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY,
                    new Message("{}".getBytes(StandardCharsets.UTF_8), invalidProperties));
            var valid = new ResultEnvelope(UUID.randomUUID().toString(), Instant.now().toString(), "resultado.registrado",
                    new ResultEnvelope.ResultData(1L, 1L, 5L, null, 1, 202L, 203L, null, null,
                            2, 1, false, new ResultEnvelope.Points(3, 1, 0), "Copa 2026", "Equipo A", "Equipo B", "Final"));
            template.send(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY,
                    new Message(mapper.writeValueAsBytes(valid), new MessageProperties()));
            assertThat(validProcessed.await(20, TimeUnit.SECONDS)).isTrue();
            container.stop();
            assertThat(attempts.get()).isEqualTo(4);
            verify(results).accept(any(), any(Instant.class));
            var dead = template.receive(RabbitConfig.DLQ, 5_000);
            assertThat(dead).isNotNull();
            assertThat(dead.getBody()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            assertThat(dead.getMessageProperties().getReceivedExchange()).isEqualTo(RabbitConfig.DLX);
            assertThat(dead.getMessageProperties().getReceivedRoutingKey()).isEqualTo(RabbitConfig.DLQ);
            var deaths = (List<?>) dead.getMessageProperties().getHeaders().get("x-death");
            assertThat(deaths).isNotEmpty();
            var death = (Map<?, ?>) deaths.getFirst();
            assertThat(death.get("reason").toString()).isEqualTo("rejected");
            assertThat(death.get("queue").toString()).isEqualTo(RabbitConfig.QUEUE);
            assertThat(template.receive(legacyProbe.getName())).isNull();
            assertThat(template.receive(otherProbe.getName())).isNull();
            assertThat(admin.getQueueInfo(RabbitConfig.QUEUE)).isNotNull()
                    .satisfies(info -> assertThat(info.getMessageCount()).isZero());
        } finally {
            try {
                container.stop(); container.destroy();
                if (ownsQueues) {
                    for (var queue : queues) {
                        var info = admin.getQueueInfo(queue.getName());
                        if (info != null && info.getMessageCount() == 0 && info.getConsumerCount() == 0) {
                            admin.deleteQueue(queue.getName(), true, true);
                        }
                    }
                }
            } finally { connection.destroy(); }
        }
    }
}
