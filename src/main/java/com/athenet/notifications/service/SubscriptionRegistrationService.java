package com.athenet.notifications.service;

import java.util.Locale;
import java.sql.SQLException;
import com.athenet.notifications.client.TurnstileClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionRegistrationService {
    private final SubscriptionRateLimiter rateLimiter;
    private final TurnstileClient turnstile;
    private final SubscriptionService subscriptions;

    public SubscriptionRegistrationService(SubscriptionRateLimiter rateLimiter, TurnstileClient turnstile,
                                          SubscriptionService subscriptions) {
        super();
        this.rateLimiter = rateLimiter;
        this.turnstile = turnstile;
        this.subscriptions = subscriptions;
    }

    // Sin transacción: Siteverify y el rate limit ocurren antes de abrir la transacción de suscripción.
    public void register(String email, String token, String remoteIp) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        rateLimiter.check(remoteIp, normalized);
        turnstile.verify(token, remoteIp);
        try {
            subscriptions.subscribe(normalized, true);
        } catch (DataIntegrityViolationException ex) {
            if (!(ex.getMostSpecificCause() instanceof SQLException sql) || !"23505".equals(sql.getSQLState())) throw ex;
            // Dos altas simultáneas pueden no ver aún la fila. Releer en una transacción nueva
            // después del rollback resuelve la colisión del correo único sin repetir Siteverify.
            subscriptions.subscribe(normalized, true);
        }
    }
}
