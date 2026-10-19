package com.athenet.notifications.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.subscriptions.rate-limit")
public class SubscriptionRateLimitProperties {
    @NotNull private Duration window = Duration.ofMinutes(15);
    @Min(2) private int perEmail = 3;
    @Min(2) private int perIp = 20;
    @Min(2) private int maxEntries = 20_000;

    @AssertTrue(message = "La ventana debe ser positiva")
    public boolean isWindowPositive() {
        return window != null && !window.isNegative() && !window.isZero();
    }
}
