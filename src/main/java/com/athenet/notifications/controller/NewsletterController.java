package com.athenet.notifications.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import com.athenet.notifications.service.SubscriptionService;

@RestController @RequestMapping("/api/public/newsletter") @RequiredArgsConstructor @Validated
public class NewsletterController {
    private final SubscriptionService subscriptions;

    public record TokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
    public record MessageResponse(String message) {}

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> confirm(@Valid @RequestBody TokenRequest request) {
        subscriptions.confirm(request.token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MessageResponse("Tu suscripción está activa."));
    }

    @PostMapping(value = "/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> unsubscribe(@Valid @RequestBody TokenRequest request) {
        subscriptions.unsubscribe(request.token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MessageResponse("Tu suscripción fue cancelada."));
    }

    // Los escáneres de enlaces del correo pueden hacer GET: solo un POST cambia la suscripción.
    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmationPage(@RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        return page("Confirmar suscripción", "Recibirás resultados de competencias y avisos de eventos deportivos de Athenet.", "confirm", token);
    }

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribePage(@RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        return page("Cancelar suscripción", "Dejarás de recibir resultados y avisos de eventos deportivos de Athenet.", "unsubscribe", token);
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmForm(@RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        subscriptions.confirm(token);
        return html("Suscripción confirmada", "Tu suscripción está activa. Recibirás los nuevos resultados del boletín.");
    }

    @PostMapping(value = "/unsubscribe", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribeForm(@RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        subscriptions.unsubscribe(token);
        return html("Suscripción cancelada", "Dejarás de recibir resultados y avisos de eventos deportivos.");
    }

    private ResponseEntity<String> page(String title, String description, String action, String token) {
        return html(title, description + "<form method=\"post\" action=\"/api/public/newsletter/" + action + "\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + HtmlUtils.htmlEscape(token) + "\">"
                + "<button type=\"submit\">" + title + "</button></form>");
    }

    private ResponseEntity<String> html(String title, String content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                // no-referrer convierte el Origin del POST de navegación en null y CORS lo rechaza.
                // strict-origin conserva el origen del formulario sin incluir ruta ni token en Referer.
                .header("Referrer-Policy", "strict-origin")
                .cacheControl(CacheControl.noStore()).body("<!doctype html><html lang=\"es\"><meta charset=\"UTF-8\">"
                        + "<title>" + title + "</title><body><h1>" + title + "</h1>" + content + "</body></html>");
    }
}
