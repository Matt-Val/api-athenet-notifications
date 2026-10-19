package com.athenet.notifications.model;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(name = "newsletter_email_delivery", uniqueConstraints = {
        @UniqueConstraint(name = "uq_newsletter_result_recipient", columnNames = {"match_id", "result_version", "subscriber_id"}),
        @UniqueConstraint(name = "uq_newsletter_event_recipient", columnNames = {"event_internal_id", "event_notice_key", "subscriber_id"})},
        indexes = {
                @Index(name = "idx_newsletter_delivery_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_newsletter_publication_due", columnList = "status,publication_status,next_publication_at")})
public class EmailDelivery {
    public enum Kind { CONFIRMATION, RESULT, EVENT }
    public enum Status { PENDING, SENT, SKIPPED, FAILED }
    public enum PublicationStatus { PENDING, CONFIRMED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "subscriber_id", nullable = false) private Long subscriberId;
    @Column(nullable = false) private long subscriberGeneration;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(name = "match_id") private Long matchId;
    @Column(name = "result_version") private Integer resultVersion;
    @Column(name = "event_internal_id", length = 255) private String eventInternalId;
    @Column(name = "event_version") private Long eventVersion;
    @Column(name = "event_notice_key", length = 40) private String eventNoticeKey;
    @Column(nullable = false, length = 254) private String recipient;
    @Column(nullable = false, length = 500) private String subject;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Column(length = 64) private String confirmationHash;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    private Instant sentAt;
    @Column(length = 120) private String lastError;
    // Nullable para poder cargar las entregas existentes antes de agregar estos campos en RDS.
    @Enumerated(EnumType.STRING) @Column(name = "publication_status")
    private PublicationStatus publicationStatus = PublicationStatus.PENDING;
    @Column(name = "publication_attempts") private Integer publicationAttempts = 0;
    @Column(name = "next_publication_at") private Instant nextPublicationAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "publication_error", length = 80) private String publicationError;
    @Column(name = "created_at") private Instant createdAt;
}
