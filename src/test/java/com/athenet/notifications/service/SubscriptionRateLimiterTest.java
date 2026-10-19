package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.athenet.notifications.config.SubscriptionRateLimitProperties;
import com.athenet.notifications.exception.RegistrationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SubscriptionRateLimiterTest {
    private final Clock clock = mock(Clock.class);
    private final SubscriptionRateLimitProperties properties = new SubscriptionRateLimitProperties();
    private final SubscriptionRateLimiter limiter = new SubscriptionRateLimiter(properties, clock, new TokenService());
    private final Instant now = Instant.parse("2026-10-14T22:00:00Z");

    @Test void allowsRepeatedRequestsThenLimitsEmailAcrossDifferentIps() {
        when(clock.instant()).thenReturn(now);
        for (int i = 0; i < 3; i++) limiter.check("192.0.2." + i, "a@example.com");
        assertLimited(() -> limiter.check("192.0.2.100", "a@example.com"));
    }

    @Test void limitsIpAcrossDifferentEmails() {
        properties.setPerIp(2);
        when(clock.instant()).thenReturn(now);
        limiter.check("192.0.2.1", "a@example.com");
        limiter.check("192.0.2.1", "b@example.com");
        assertLimited(() -> limiter.check("192.0.2.1", "c@example.com"));
        limiter.check("192.0.2.2", "c@example.com");
    }

    @Test void limitsResetAtExactWindowExpiry() {
        when(clock.instant()).thenReturn(now);
        for (int i = 0; i < 3; i++) limiter.check("192.0.2.1", "a@example.com");
        when(clock.instant()).thenReturn(now.plus(properties.getWindow()).minusMillis(1));
        assertLimited(() -> limiter.check("192.0.2.1", "a@example.com"));
        when(clock.instant()).thenReturn(now.plus(properties.getWindow()));
        limiter.check("192.0.2.1", "a@example.com");
    }

    @Test void boundsMemoryWithoutEvictingActiveCounters() {
        properties.setMaxEntries(2);
        when(clock.instant()).thenReturn(now);
        limiter.check("192.0.2.1", "a@example.com");
        assertLimited(() -> limiter.check("192.0.2.2", "b@example.com"));
        limiter.check("192.0.2.1", "a@example.com");
        limiter.check("192.0.2.1", "a@example.com");
        assertLimited(() -> limiter.check("192.0.2.1", "a@example.com"));
        when(clock.instant()).thenReturn(now.plus(properties.getWindow()));
        limiter.check("192.0.2.2", "b@example.com");
    }

    @Test void concurrentAttemptsCannotExceedEmailLimit() throws Exception {
        when(clock.instant()).thenReturn(now);
        var admitted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) jobs.add(executor.submit(() -> {
                try { limiter.check("192.0.2.1", "a@example.com"); admitted.incrementAndGet(); }
                catch (RegistrationException ex) { assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS); }
            }));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertThat(admitted.get()).isEqualTo(3);
    }

    private void assertLimited(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RegistrationException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
