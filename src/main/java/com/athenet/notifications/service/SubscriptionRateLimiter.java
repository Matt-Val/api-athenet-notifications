package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import com.athenet.notifications.config.SubscriptionRateLimitProperties;
import com.athenet.notifications.exception.RegistrationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Contadores por instancia: una ventana fija desde el primer intento de cada clave. */
@Component
public class SubscriptionRateLimiter {
    private final SubscriptionRateLimitProperties properties;
    private final Clock clock;
    private final TokenService tokens;
    private final Map<String, Counter> counters = new HashMap<>();
    private Instant nextCleanup = Instant.MIN;

    public SubscriptionRateLimiter(SubscriptionRateLimitProperties properties, Clock clock, TokenService tokens) {
        this.properties = properties;
        this.clock = clock;
        this.tokens = tokens;
    }

    public synchronized void check(String remoteIp, String normalizedEmail) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanup) || counters.size() >= properties.getMaxEntries()) {
            counters.values().removeIf(counter -> !now.isBefore(counter.expiresAt));
            nextCleanup = now.plusSeconds(60);
        }
        String ipKey = "ip:" + tokens.hash(remoteIp);
        String emailKey = "email:" + tokens.hash(normalizedEmail);
        Counter ip = current(ipKey, now);
        Counter email = current(emailKey, now);
        if (ip.attempts >= properties.getPerIp() || email.attempts >= properties.getPerEmail()) throw limited();
        int newEntries = (counters.containsKey(ipKey) ? 0 : 1) + (counters.containsKey(emailKey) ? 0 : 1);
        // No desalojar contadores vigentes: permitiría reiniciar el límite usando claves nuevas.
        if (counters.size() + newEntries > properties.getMaxEntries()) throw limited();
        counters.put(ipKey, new Counter(ip.attempts + 1, ip.expiresAt));
        counters.put(emailKey, new Counter(email.attempts + 1, email.expiresAt));
    }

    private Counter current(String key, Instant now) {
        Counter counter = counters.get(key);
        return counter == null || !now.isBefore(counter.expiresAt)
                ? new Counter(0, now.plus(properties.getWindow())) : counter;
    }

    private static RegistrationException limited() {
        return new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Intenta más tarde.");
    }

    private record Counter(int attempts, Instant expiresAt) {}
}
