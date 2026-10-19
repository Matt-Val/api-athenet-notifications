package com.athenet.notifications.controller;

import com.athenet.notifications.service.SubscriptionRegistrationService;
import com.athenet.notifications.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/subscriptions")
public class SubscriptionController {
    private final SubscriptionRegistrationService registrations;
    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionRegistrationService registrations, SubscriptionService subscriptions) {
        super();
        this.registrations = registrations;
        this.subscriptions = subscriptions;
    }

    public record SubscriptionRequest(
            @NotBlank @Email @Size(max = 254) @Pattern(regexp = "[^\\s\\p{Cntrl}]+") String email,
            @NotNull @AssertTrue Boolean consent,
            String turnstileToken) {
        public SubscriptionRequest { if (email != null) email = email.strip(); }
    }

    public record MessageResponse(String message) {}
    public record ConfirmationRequest(@NotBlank @Size(max = 2048) String token) {}

    @PostMapping
    public ResponseEntity<MessageResponse> subscribe(@Valid @RequestBody SubscriptionRequest body,
                                                     HttpServletRequest request) {
        // Solo la IP de la conexión. Los headers de proxy requieren una configuración de confianza posterior.
        registrations.register(body.email(), body.turnstileToken(), request.getRemoteAddr());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new MessageResponse(
                "Si tu correo necesita confirmación, recibirás un enlace para activar la suscripción."));
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> confirm(@Valid @RequestBody ConfirmationRequest body) {
        subscriptions.confirm(body.token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MessageResponse("Tu suscripción está activa."));
    }
}
