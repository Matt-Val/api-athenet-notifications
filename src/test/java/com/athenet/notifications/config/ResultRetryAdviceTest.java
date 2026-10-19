package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.athenet.notifications.messaging.ResultListener;
import com.athenet.notifications.service.ResultNotificationService;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;

class ResultRetryAdviceTest {
    private final ConnectionFactory connection = mock(ConnectionFactory.class);
    private final ResultNotificationService results = mock(ResultNotificationService.class);
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final List<Long> delays = new ArrayList<>();
    private Endpoint target;
    private Endpoint endpoint;

    @BeforeEach void setup() {
        var config = new RabbitConfig();
        var productionPolicy = config.resultRetryPolicy();
        // Conserva la política y el número de reintentos reales; solo evita las pausas del test.
        RetryPolicy withoutWaiting = new RetryPolicy() {
            @Override public boolean shouldRetry(Throwable failure) { return productionPolicy.shouldRetry(failure); }
            @Override public BackOff getBackOff() {
                return () -> {
                    var backOff = productionPolicy.getBackOff().start();
                    return () -> {
                        long delay = backOff.nextBackOff();
                        if (delay < 0) return delay;
                        delays.add(delay);
                        return 0;
                    };
                };
            }
        };
        var factory = config.rabbitListenerContainerFactory(connection, withoutWaiting, false);
        var container = factory.createListenerContainer(null);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected")).isEqualTo(false);
        var listener = new ResultListener(new ApplicationConfig().rabbitObjectMapper(), validators.getValidator(), results);
        target = spy(new Endpoint(listener));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        for (Advice advice : (Advice[]) Objects.requireNonNull(ReflectionTestUtils.getField(container, "adviceChain"))) {
            proxy.addAdvice(advice);
        }
        endpoint = (Endpoint) proxy.getProxy();
    }

    @AfterEach void close() {
        validators.close();
        verifyNoInteractions(connection);
    }

    @Test void invalidResultIsRejectedAfterFourAttemptsAndNextValidResultCanProceed() throws Exception {
        var invalid = message("{}");
        assertThatThrownBy(() -> endpoint.receive(null, invalid)).isInstanceOf(ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(target, times(4)).receive(null, invalid);
        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L);
        verifyNoInteractions(results);
        var valid = validMessage();
        endpoint.receive(null, valid);
        verify(target).receive(null, valid);
        verify(results).accept(any(), any(Instant.class));
    }

    @Test void permanentDatabaseFailureIsRejectedAfterThreeRetries() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(results).accept(any(), any(Instant.class));
        var message = validMessage();
        assertThatThrownBy(() -> endpoint.receive(null, message)).isInstanceOf(ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(results, times(4)).accept(any(), any(Instant.class));
        verify(target, times(4)).receive(null, message);
        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L);
    }

    @Test void transientDatabaseFailureCanSucceedOnFirstRetry() throws Exception {
        doThrow(new IllegalStateException("temporary database failure")).doNothing()
                .when(results).accept(any(), any(Instant.class));
        var message = validMessage();
        endpoint.receive(null, message);
        verify(target, times(2)).receive(null, message);
        verify(results, times(2)).accept(any(), any(Instant.class));
        assertThat(delays).containsExactly(1_000L);
    }

    private Message validMessage() {
        return message("""
                {"messageId":"5ec1dce2-ef36-4fb5-a2ea-72ad5b4c92df","occurredAt":"2026-10-16T12:00:00Z",
                 "type":"resultado.registrado","data":{"matchId":1,"competitionId":1,"sportId":5,"version":1,
                 "homeTeamId":202,"awayTeamId":203,"homeScore":2,"awayScore":1,
                 "competitionName":"Copa 2026","homeTeamName":"Equipo A","awayTeamName":"Equipo B",
                 "round":"Final","points":{"win":3,"draw":1,"loss":0}}}
                """);
    }

    private Message message(String json) { return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties()); }

    public static class Endpoint {
        private final ResultListener listener;
        public Endpoint(ResultListener listener) { this.listener = listener; }
        public void receive(Channel channel, Message message) throws IOException { listener.receive(message); }
    }
}
