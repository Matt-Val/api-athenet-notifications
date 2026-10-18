package com.athenet.notifications.client;

import com.athenet.notifications.config.TurnstileProperties;
import com.athenet.notifications.exception.RegistrationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TurnstileClient {
    private static final Logger log = LoggerFactory.getLogger(TurnstileClient.class);
    private static final String SITEVERIFY = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private final RestClient client;
    private final TurnstileProperties properties;
    private final ObjectMapper mapper;

    
    public TurnstileClient(@Qualifier("turnstileRestClient") RestClient client,
                          TurnstileProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    public void verify(String token, String remoteIp) {
        if (token == null || token.isBlank() || token.length() > 2048) throw rejected();
        if (properties.getSecretKey() == null || properties.getSecretKey().isBlank()) throw unavailable();

        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);
        form.add("remoteip", remoteIp);
        JsonNode result;
        try {
            String body = client.post().uri(SITEVERIFY).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            if (body == null || body.isBlank()) throw unavailable();
            result = mapper.readTree(body);
        } catch (RestClientException | JsonProcessingException ex) {
            // No registrar el cuerpo, token, clave ni la excepción HTTP original.
            throw unavailable();
        }
        if (result == null || !result.path("success").isBoolean()) throw unavailable();
        if (!result.path("success").booleanValue()) {
            log.warn("Turnstile: Siteverify rechazó el token (clave pública de prueba: {}).",
                    usesTestSecret());
            for (JsonNode code : result.path("error-codes")) {
                if (switch (code.asText()) {
                    case "internal-error", "invalid-input-secret", "missing-input-secret", "bad-request" -> true;
                    default -> false;
                }) throw unavailable();
            }
            throw rejected();
        }
        if (!result.path("hostname").isTextual()
                || !properties.getAllowedHostnames().contains(result.path("hostname").asText())) {
            String hostname = result.path("hostname").asText("");
            String safeHostname = hostname.matches("[A-Za-z0-9.-]{1,253}") ? hostname : "<ausente o inválido>";
            log.warn("Turnstile: hostname rechazado: {} (clave pública de prueba: {}).", safeHostname, usesTestSecret());
            throw rejected();
        }
    }

    private boolean usesTestSecret() {
        return "1x0000000000000000000000000000000AA".equals(properties.getSecretKey());
    }

    private static RegistrationException rejected() {
        return new RegistrationException(HttpStatus.FORBIDDEN, "La verificación de Turnstile no es válida. Intenta nuevamente.");
    }

    private static RegistrationException unavailable() {
        return new RegistrationException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos verificar Turnstile. Intenta nuevamente.");
    }
}
