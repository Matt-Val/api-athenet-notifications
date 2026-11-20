package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import com.athenet.notifications.client.TurnstileClient;
import com.athenet.notifications.exception.RegistrationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class SubscriptionRegistrationServiceTest {
    private final SubscriptionRateLimiter limiter = mock(SubscriptionRateLimiter.class);
    private final TurnstileClient turnstile = mock(TurnstileClient.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);
    private final SubscriptionRegistrationService service = new SubscriptionRegistrationService(limiter, turnstile, subscriptions);

    @Test void validatesBeforeEnteringDatabaseService() {
        service.register(" Test@Example.COM ", "token", "192.0.2.1");
        var order = inOrder(limiter, turnstile, subscriptions);
        order.verify(limiter).check("192.0.2.1", "test@example.com");
        order.verify(turnstile).verify("token", "192.0.2.1");
        order.verify(subscriptions).subscribe("test@example.com", true);
    }

    @Test void rateLimitedRequestNeverCallsCloudflareOrDatabase() {
        doThrow(new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "limited")).when(limiter).check(any(), any());
        assertThatThrownBy(() -> service.register("a@example.com", "token", "192.0.2.1")).isInstanceOf(RegistrationException.class);
        verifyNoInteractions(turnstile, subscriptions);
    }

    @Test void rejectedCaptchaNeverCallsDatabase() {
        doThrow(new RegistrationException(HttpStatus.FORBIDDEN, "rejected")).when(turnstile).verify(any(), any());
        assertThatThrownBy(() -> service.register("a@example.com", "token", "192.0.2.1")).isInstanceOf(RegistrationException.class);
        verifyNoInteractions(subscriptions);
    }

    @Test void rereadsAfterConcurrentInsertWithoutRedeemingCaptchaAgain() {
        doThrow(new DataIntegrityViolationException("unique email", new SQLException("unique", "23505")))
                .doNothing().when(subscriptions).subscribe(any(), eq(true));
        service.register("a@example.com", "token", "192.0.2.1");
        verify(subscriptions, times(2)).subscribe("a@example.com", true);
        verify(turnstile).verify("token", "192.0.2.1");
    }

    @Test void repeatedDatabaseFailureIsNotSwallowed() {
        doThrow(new DataIntegrityViolationException("database failure")).when(subscriptions).subscribe(any(), eq(true));
        assertThatThrownBy(() -> service.register("a@example.com", "token", "192.0.2.1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(subscriptions).subscribe("a@example.com", true);
    }
}
