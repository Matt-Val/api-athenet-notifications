package com.athenet.notifications.model;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Evento pendiente de publicación, creado en la misma transacción que activa al suscriptor. */
@Entity @Getter @Setter
@Table(name = "newsletter_confirmation_event", uniqueConstraints = @UniqueConstraint(
        name = "uq_newsletter_confirmation_generation", columnNames = {"subscriber_id", "subscriber_generation"}),
        indexes = {
                @Index(name = "idx_newsletter_confirmation_pending", columnList = "status,occurred_at"),
                @Index(name = "idx_newsletter_confirmation_due", columnList = "status,next_publication_at,occurred_at")})
public class SubscriptionConfirmationEvent {
    public static final String TYPE = "subscription.confirmed";
    public enum Status { PENDING, PUBLISHED }

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "subscriber_id", nullable = false) private Long subscriberId;
    @Column(name = "subscriber_generation", nullable = false) private long subscriberGeneration;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    // Nullable para admitir los eventos creados antes de incorporar el publicador.
    @Column(name = "publication_attempts") private Integer publicationAttempts = 0;
    @Column(name = "next_publication_at") private Instant nextPublicationAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "publication_error", length = 80) private String publicationError;
    @Version private long entityVersion;

    public static SubscriptionConfirmationEvent pending(Subscriber subscriber, Instant now) {
        var event = new SubscriptionConfirmationEvent();
        event.setSubscriberId(subscriber.getId());
        event.setSubscriberGeneration(subscriber.getGeneration());
        event.setOccurredAt(now);
        event.setNextPublicationAt(now);
        return event;
    }
}
