package com.athenet.notifications.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record EventNoticeEnvelope(@NotBlank String messageId, @NotBlank String occurredAt,
        @NotNull @Pattern(regexp = "evento\\.(publicado|modificado|cancelado)") String type,
        @Min(1) Long version, @NotNull @Valid EventData data) {
    public record EventData(@NotBlank @Size(max = 255) String internalId,
            @NotBlank @Size(max = 255) String title, @Size(max = 2000) String description,
            @NotNull @Pattern(regexp = "MATCH|TRACKDAY|MEETING|OTHER") String type,
            @Pattern(regexp = "PUBLISHED|CANCELLED|DRAFT") String status, boolean official,
            @NotBlank String eventDate, @NotBlank String startTime,
            @NotBlank String endDate, @NotBlank String endTime,
            @Size(max = 255) String location, @Size(max = 255) String address) {
        public EventData {
            title = clean(title); description = clean(description);
            location = clean(location); address = clean(address);
        }
        private static String clean(String value) {
            return value == null ? null : value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Z}]", " ").strip();
        }
        public boolean sporting() { return "MATCH".equals(type) || "TRACKDAY".equals(type); }
    }
    public long noticeVersion() { return version == null ? 1 : version; }
}
