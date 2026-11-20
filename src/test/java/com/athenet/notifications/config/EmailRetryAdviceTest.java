package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import com.athenet.notifications.config.*;
import com.athenet.notifications.messaging.EmailMessageRecoverer;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.service.DeliveryFailureService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.core.retry.RetryPolicy;

class EmailRetryAdviceTest {
    @Test void repeatedFailureIsBoundedAndRecoveredWithoutSleeping() {
        var target = mock(Endpoint.class);
        var failures = mock(DeliveryFailureService.class);
        var message = new Message(new byte[0], new MessageProperties());
        doThrow(new EmailDeliveryRetryException(42L)).when(target).receive(isNull(), eq(message));
        Endpoint endpoint = withRetry(target, failures);
        assertThatThrownBy(() -> endpoint.receive(null, message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(target, times(5)).receive(null, message);
        verify(failures).exhausted(42L);
    }

    @Test void invalidMessageIsRejectedWithoutRetryOrDeliveryUpdate() {
        var target = mock(Endpoint.class);
        var failures = mock(DeliveryFailureService.class);
        var message = new Message(new byte[0], new MessageProperties());
        doThrow(new AmqpRejectAndDontRequeueException("invalid")).when(target).receive(isNull(), eq(message));
        Endpoint endpoint = withRetry(target, failures);
        assertThatThrownBy(() -> endpoint.receive(null, message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(target).receive(null, message);
        verifyNoInteractions(failures);
    }

    private Endpoint withRetry(Endpoint target, DeliveryFailureService failures) {
        var productionPolicy = new RabbitConfig().emailRetryPolicy(new NewsletterProperties());
        var fastPolicy = RetryPolicy.builder().maxRetries(4).delay(Duration.ZERO).jitter(Duration.ZERO)
                .predicate(productionPolicy::shouldRetry).build();
        var advice = RetryInterceptorBuilder.stateless().retryPolicy(fastPolicy)
                .recoverer(new EmailMessageRecoverer(failures)).build();
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        return (Endpoint) proxy.getProxy();
    }

    public static class Endpoint {
        public void receive(Channel channel, Message message) { }
    }
}
