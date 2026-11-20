package com.athenet.notifications.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "newsletter_event_state") @Getter @Setter
public class EventNoticeState {
    @Id @Column(name = "internal_id", length = 255) private String internalId;
    @Column(name = "latest_version", nullable = false) private long latestVersion;
    @Column(name = "publication_handled", nullable = false) private boolean publicationHandled;
    @Column(nullable = false) private boolean sporting;
    @Column(name = "latest_type", length = 40) private String latestType;
    @Version @Column(name = "entity_version") private Long entityVersion;
}
