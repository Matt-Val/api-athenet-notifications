package com.athenet.notifications.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.newsletter")
public class NewsletterProperties {
    @NotBlank private String apiBaseUrl = "http://localhost:8084";
    @NotBlank private String publicBaseUrl = "http://localhost:5173";
    @NotBlank @Email private String from = "boletin@athenet.test";
    @NotNull private Duration confirmationTtl = Duration.ofHours(48);
    @Min(1) private int maxAttempts = 5;
    @Min(1) private int batchSize = 20;
    @NotNull private Duration publishConfirmTimeout = Duration.ofSeconds(5);

    @AssertTrue(message = "El timeout de confirmación debe ser mayor a cero y como máximo 30 segundos")
    public boolean isPublishConfirmTimeoutValid() {
        return publishConfirmTimeout != null && !publishConfirmTimeout.isNegative() && !publishConfirmTimeout.isZero()
                && publishConfirmTimeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
