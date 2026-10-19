package com.athenet.notifications.support;

import java.util.UUID;
import com.athenet.notifications.messaging.EventNoticeEnvelope;

public final class EventNoticeFixtures {
    private EventNoticeFixtures() {}
    public static EventNoticeEnvelope notice(String type, long version, String eventType) {
        return new EventNoticeEnvelope(UUID.randomUUID().toString(), "2026-10-17T21:00:00Z", type, version,
                new EventNoticeEnvelope.EventData("EVT-42", "Jornada deportiva", "Descripción", eventType,
                        type.endsWith("cancelado") ? "CANCELLED" : "PUBLISHED", false,
                        "2026-10-24", "22:00", "2026-10-25", "00:30", "Estadio", null));
    }
}
