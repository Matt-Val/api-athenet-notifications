package com.athenet.notifications.service;

import java.time.Clock;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.model.Subscriber;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import com.athenet.notifications.exception.ConfirmationException;
import com.athenet.notifications.repository.*;

@Service
public class SubscriptionService {
    private final SubscriberRepository subscribers;
    private final EmailDeliveryRepository deliveries;
    private final TokenService tokens;
    private final NewsletterProperties properties;
    private final Clock clock;
    private final SubscriptionConfirmationEventRepository confirmationEvents;

    public SubscriptionService(SubscriberRepository subscribers, EmailDeliveryRepository deliveries, TokenService tokens,
                               NewsletterProperties properties, Clock clock,
                               SubscriptionConfirmationEventRepository confirmationEvents) {
        super();
        this.subscribers = subscribers;
        this.deliveries = deliveries;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        this.confirmationEvents = confirmationEvents;
    }

    @Transactional
    public void subscribe(String email, boolean consent) {
        if (!consent) throw new IllegalArgumentException("Debes aceptar recibir el boletín.");
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        var now = clock.instant();
        var subscriber = subscribers.findByEmail(normalized).orElseGet(Subscriber::new);
        subscriber.setConsent(true);
        subscriber.setConsentAcceptedAt(now);
        if (subscriber.getStatus() == Subscriber.Status.ACTIVE) return;

        String token = tokens.generate();
        subscriber.setEmail(normalized);
        subscriber.setStatus(Subscriber.Status.PENDING);
        subscriber.setGeneration(subscriber.getGeneration() + 1);
        subscriber.setConfirmationHash(tokens.hash(token));
        subscriber.setConfirmationExpiresAt(now.plus(properties.getConfirmationTtl()));
        subscriber.setConfirmationRequestedAt(now);
        subscriber.setConfirmedAt(null);
        subscriber.setUnsubscribeToken(tokens.generate());
        subscribers.saveAndFlush(subscriber);

        String url = base(properties.getPublicBaseUrl()) + "/suscripcion/confirmar?token=" + token;
        var delivery = DeliveryFactory.pending(subscriber, EmailDelivery.Kind.CONFIRMATION,
                "Confirma tu suscripción al boletín de Athenet", """
                Solicitaste recibir resultados de competencias y avisos de eventos deportivos de Athenet.

                Confirma tu correo en este enlace:
                %s

                El enlace vence en %s horas. Si no solicitaste la suscripción, puedes ignorar este correo.
                """.formatted(url, properties.getConfirmationTtl().toHours()), now);
        delivery.setConfirmationHash(subscriber.getConfirmationHash());
        deliveries.save(delivery);
    }

    @Transactional
    public void confirm(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unknownConfirmation();
        var subscriber = subscribers.findByConfirmationHash(tokens.hash(token))
                .orElseThrow(SubscriptionService::unknownConfirmation);
        // Se conserva el hash después de confirmar para reconocer la repetición del mismo enlace.
        if (subscriber.getStatus() == Subscriber.Status.ACTIVE) return;
        if (subscriber.getStatus() != Subscriber.Status.PENDING) throw unknownConfirmation();
        var now = clock.instant();
        if (subscriber.getConfirmationExpiresAt() == null || !subscriber.getConfirmationExpiresAt().isAfter(now)) {
            throw new ConfirmationException(HttpStatus.GONE, "El enlace de confirmación venció. Solicita una nueva suscripción.");
        }
        subscriber.setStatus(Subscriber.Status.ACTIVE);
        subscriber.setConfirmedAt(now);
        confirmationEvents.save(SubscriptionConfirmationEvent.pending(subscriber, now));
    }

    @Transactional
    public void unsubscribe(String token) {
        var subscriber = subscribers.findByUnsubscribeToken(token)
                .orElseThrow(() -> new IllegalArgumentException("El enlace para cancelar la suscripción no es válido."));
        subscriber.setStatus(Subscriber.Status.UNSUBSCRIBED);
        subscriber.setConfirmationHash(null);
        subscriber.setConfirmationExpiresAt(null);
    }

    static String base(String url) { return url.replaceAll("/+$", ""); }

    private static ConfirmationException unknownConfirmation() {
        return new ConfirmationException(HttpStatus.NOT_FOUND, "El enlace de confirmación no es válido.");
    }
}
