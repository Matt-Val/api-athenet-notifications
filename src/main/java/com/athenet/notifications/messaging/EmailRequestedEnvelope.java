package com.athenet.notifications.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import com.athenet.notifications.config.RabbitConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record EmailRequestedEnvelope(
        @NotBlank String messageId,
        @NotBlank String occurredAt,
        @NotNull @Pattern(regexp = "notificacion\\.email\\.solicitado") String type,
        @NotNull @Valid Data data) {

    public record Data(@NotNull @Positive Long deliveryId) {}

    public static EmailRequestedEnvelope forDelivery(Long deliveryId, Instant createdAt) {
        String messageId = UUID.nameUUIDFromBytes(("athenet:email-delivery:" + deliveryId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new EmailRequestedEnvelope(messageId, createdAt.toString(), RabbitConfig.EMAIL_ROUTING_KEY,
                new Data(deliveryId));
    }
}
