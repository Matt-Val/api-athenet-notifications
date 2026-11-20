package com.athenet.notifications.config;

import java.util.Set;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("app.turnstile")
public class TurnstileProperties {
    // El vacío permite arrancar el servicio; el registro responde 503 hasta configurar la clave.
    private String secretKey = "";
    @NotEmpty private Set<@NotBlank String> allowedHostnames = Set.of("localhost", "fabetabilo.github.io");
}