package com.athenet.notifications.model;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "newsletter_subscriber") @Getter @Setter
public class Subscriber {
    public enum Status { PENDING, ACTIVE, UNSUBSCRIBED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    // Nullable para conservar filas del borrador sin inventar consentimiento anterior.
    private Boolean consent;
    @Column(name = "consent_accepted_at") private Instant consentAcceptedAt;
    @Column(length = 64) private String confirmationHash;
    private Instant confirmationExpiresAt;
    private Instant confirmationRequestedAt;
    private Instant confirmedAt;
    @Column(nullable = false, unique = true, length = 64) private String unsubscribeToken;
    @Column(nullable = false) private long generation;
    @Version private long entityVersion;
}
