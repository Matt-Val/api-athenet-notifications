package com.athenet.notifications.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import com.athenet.notifications.client.TurnstileClient;
import com.athenet.notifications.config.*;
import com.athenet.notifications.exception.RegistrationException;
import com.athenet.notifications.model.Subscriber;
import com.athenet.notifications.repository.*;
import com.athenet.notifications.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = SubscriptionController.class, properties = {
        "app.cors.allowed-origins=http://localhost:5173,https://fabetabilo.github.io"})
@Import({SecurityConfig.class, SubscriptionRegistrationService.class, SubscriptionService.class, TokenService.class})
@EnableConfigurationProperties(NewsletterProperties.class)
class SubscriptionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SubscriptionConfirmationEventRepository confirmationEvents;
    @MockitoBean SubscriberRepository subscribers;
    @MockitoBean EmailDeliveryRepository deliveries;
    @MockitoBean TurnstileClient turnstile;
    @MockitoBean SubscriptionRateLimiter limiter;
    @MockitoBean Clock clock;
    private final Instant now = Instant.parse("2026-10-14T22:00:00Z");

    @BeforeEach void setup() { when(clock.instant()).thenReturn(now); }

    @Test void publicRequestPersistsPendingEmailAndDatedConsent() throws Exception {
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" Test@Example.COM \",\"consent\":true,\"turnstileToken\":\"token\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        var saved = ArgumentCaptor.forClass(Subscriber.class);
        verify(subscribers).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("test@example.com");
        assertThat(saved.getValue().getStatus()).isEqualTo(Subscriber.Status.PENDING);
        assertThat(saved.getValue().getConsent()).isTrue();
        assertThat(saved.getValue().getConsentAcceptedAt()).isEqualTo(now);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"email\":\"bad\",\"consent\":true}", "{\"email\":\"a@example.com\"}",
            "{\"email\":\"a@example.com\",\"consent\":false}", "{\"email\":\"a@example.com\",\"consent\":null}"})
    void invalidPayloadNeverCallsCloudflareOrDatabase(String body) throws Exception {
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        verifyNoInteractions(subscribers, deliveries, limiter, turnstile);
    }

    @Test void missingCaptchaReturns403WithoutDatabaseAccess() throws Exception {
        doThrow(new RegistrationException(HttpStatus.FORBIDDEN, "captcha")).when(turnstile).verify(isNull(), anyString());
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"consent\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("status").value(403));
        verifyNoInteractions(subscribers, deliveries);
    }

    @Test void captchaFailureAndCloudflareOutageNeverAccessDatabase() throws Exception {
        for (var status : new HttpStatus[]{HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE}) {
            doThrow(new RegistrationException(status, "verification")).when(turnstile).verify(anyString(), anyString());
            mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().is(status.value())).andExpect(jsonPath("status").value(status.value()));
        }
        verifyNoInteractions(subscribers, deliveries);
    }

    @Test void rateLimitReturns429BeforeCloudflareAndDatabase() throws Exception {
        doThrow(new RegistrationException(HttpStatus.TOO_MANY_REQUESTS, "limited")).when(limiter).check(anyString(), anyString());
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(turnstile, subscribers, deliveries);
    }

    @Test void repeatedPendingEmailGetsSame202WithOneSubscriber() throws Exception {
        var subscriber = new Subscriber(); subscriber.setId(1L); subscriber.setEmail("a@example.com");
        subscriber.setStatus(Subscriber.Status.PENDING);
        when(subscribers.findByEmail("a@example.com")).thenReturn(Optional.of(subscriber));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().isAccepted());
        }
        verify(subscribers, times(2)).saveAndFlush(subscriber);
        assertThat(subscriber.getGeneration()).isEqualTo(2);
    }

    @Test void activeAndNewEmailsReceiveIdenticalResponseWithoutReactivating() throws Exception {
        String newResponse = mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var subscriber = new Subscriber(); subscriber.setStatus(Subscriber.Status.ACTIVE);
        when(subscribers.findByEmail("a@example.com")).thenReturn(Optional.of(subscriber));
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isAccepted()).andExpect(content().string(newResponse));
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        verify(deliveries, times(1)).save(any());
    }

    @Test void forgedForwardingHeadersDoNotReplacePeerIp() throws Exception {
        mvc.perform(post("/subscriptions").contentType(MediaType.APPLICATION_JSON).content(validBody())
                .with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .header("X-Forwarded-For", "198.51.100.1").header("CF-Connecting-IP", "198.51.100.2")
                .header("Forwarded", "for=198.51.100.3"))
                .andExpect(status().isAccepted());
        verify(turnstile).verify("token", "192.0.2.1");
        verify(limiter).check("192.0.2.1", "a@example.com");
    }

    @Test void allowsBothFrontendOriginsAndJsonPreflight() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "https://fabetabilo.github.io"}) {
            for (String path : new String[]{"/subscriptions", "/subscriptions/confirm"}) {
                mvc.perform(options(path).header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Content-Type"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
            }
        }
        mvc.perform(options("/subscriptions").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        verifyNoInteractions(subscribers, deliveries, turnstile, limiter);
    }

    @Test void confirmationIsAnonymousAndIdempotentWithoutCaptchaOrRateLimit() throws Exception {
        String token = "a".repeat(43);
        var subscriber = new Subscriber(); subscriber.setId(7L); subscriber.setGeneration(1);
        subscriber.setStatus(Subscriber.Status.PENDING); subscriber.setConfirmationExpiresAt(now.plusSeconds(3600));
        when(subscribers.findByConfirmationHash(new TokenService().hash(token))).thenReturn(Optional.of(subscriber));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/subscriptions/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        verify(confirmationEvents).save(any());
        verifyNoInteractions(turnstile, limiter, deliveries);
    }

    @Test void expiredPendingConfirmationReturns410WithoutActivating() throws Exception {
        String token = "a".repeat(43);
        var subscriber = new Subscriber(); subscriber.setStatus(Subscriber.Status.PENDING);
        subscriber.setConfirmationExpiresAt(now);
        when(subscribers.findByConfirmationHash(new TokenService().hash(token))).thenReturn(Optional.of(subscriber));
        mvc.perform(post("/subscriptions/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("status").value(410));
        assertThat(subscriber.getStatus()).isEqualTo(Subscriber.Status.PENDING);
        verifyNoInteractions(confirmationEvents, turnstile, limiter);
    }

    @ParameterizedTest @ValueSource(strings = {"inventado", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void unknownConfirmationReturns404(String token) throws Exception {
        mvc.perform(post("/subscriptions/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("status").value(404));
        verifyNoInteractions(confirmationEvents, turnstile, limiter, deliveries);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"token\":null}", "{\"token\":\"\"}"})
    void missingConfirmationTokenReturns400(String body) throws Exception {
        mvc.perform(post("/subscriptions/confirm").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(subscribers, confirmationEvents, turnstile, limiter, deliveries);
    }

    @Test void getCannotActivateSubscription() throws Exception {
        mvc.perform(get("/subscriptions/confirm").param("token", "a".repeat(43)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(subscribers, confirmationEvents, turnstile, limiter, deliveries);
    }

    private String validBody() { return "{\"email\":\"a@example.com\",\"consent\":true,\"turnstileToken\":\"token\"}"; }
}
