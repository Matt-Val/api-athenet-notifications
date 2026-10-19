package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import com.athenet.notifications.exception.ConfirmationException;
import org.springframework.http.HttpStatus;

class SubscriptionServiceTest {
    private final Instant now = Instant.parse("2026-10-13T20:00:00Z");
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final SubscriptionConfirmationEventRepository events = mock(SubscriptionConfirmationEventRepository.class);
    private final NewsletterProperties properties = new NewsletterProperties();
    private final String token = "a".repeat(43);
    private final TokenService tokens = new TokenService();
    private final SubscriptionService service = new SubscriptionService(subscribers, deliveries, tokens,
            properties, Clock.fixed(now, ZoneOffset.UTC), events);

    @BeforeEach void setup() {
        when(subscribers.saveAndFlush(any())).thenAnswer(invocation -> {
            Subscriber s = invocation.getArgument(0); s.setId(1L); return s;
        });
    }

    @Test void newSubscriptionNormalizesEmailAndQueuesConfirmation() {
        service.subscribe(" Test@Example.COM ", true);
        var subscriber = ArgumentCaptor.forClass(Subscriber.class);
        var email = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(subscribers).saveAndFlush(subscriber.capture());
        verify(deliveries).save(email.capture());
        assertThat(subscriber.getValue().getEmail()).isEqualTo("test@example.com");
        assertThat(subscriber.getValue().getStatus()).isEqualTo(Subscriber.Status.PENDING);
        assertThat(subscriber.getValue().getConsent()).isTrue();
        assertThat(subscriber.getValue().getConsentAcceptedAt()).isEqualTo(now);
        assertThat(subscriber.getValue().getConfirmationExpiresAt()).isEqualTo(now.plus(Duration.ofHours(48)));
        assertThat(email.getValue().getKind()).isEqualTo(EmailDelivery.Kind.CONFIRMATION);
        assertThat(email.getValue().getBody()).contains("/suscripcion/confirmar?token=");
        assertThat(email.getValue().getBody()).doesNotContain(subscriber.getValue().getConfirmationHash());
    }

    @Test void rejectedConsentNeverReadsOrWritesSubscribers() {
        assertThatThrownBy(() -> service.subscribe("test@example.com", false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(subscribers, deliveries);
    }

    @Test void twoAdmittedRequestsKeepOneSubscriberAndRotatePendingToken() {
        var s = subscriber(Subscriber.Status.PENDING);
        when(subscribers.findByEmail(s.getEmail())).thenReturn(Optional.of(s));
        service.subscribe(s.getEmail(), true);
        String first = s.getConfirmationHash();
        service.subscribe(s.getEmail(), true);
        assertThat(s.getId()).isEqualTo(1L);
        assertThat(s.getConfirmationHash()).isNotEqualTo(first);
        verify(subscribers, times(2)).saveAndFlush(s);
        verify(deliveries, times(2)).save(any());
    }

    @Test void activeSubscriberDoesNotReceiveAnotherConfirmation() {
        var s = subscriber(Subscriber.Status.ACTIVE);
        when(subscribers.findByEmail(s.getEmail())).thenReturn(Optional.of(s));
        service.subscribe(s.getEmail(), true);
        verifyNoInteractions(deliveries);
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        assertThat(s.getConsent()).isTrue();
    }

    @Test void admittedPendingRequestRotatesTokenEvenWithinOneMinute() {
        var s = subscriber(Subscriber.Status.PENDING);
        s.setConfirmationRequestedAt(now.minusSeconds(60));
        s.setConfirmationHash("previous-hash"); s.setGeneration(1);
        when(subscribers.findByEmail(s.getEmail())).thenReturn(Optional.of(s));
        service.subscribe(s.getEmail(), true);
        verify(deliveries).save(any());
        assertThat(s.getConfirmationHash()).isNotEqualTo("previous-hash");
        assertThat(s.getGeneration()).isEqualTo(2);
    }

    @Test void resubscribeRotatesTokensAndInvalidatesOldDeliveries() {
        var s = subscriber(Subscriber.Status.UNSUBSCRIBED);
        s.setUnsubscribeToken("old-token"); s.setGeneration(1);
        when(subscribers.findByEmail(s.getEmail())).thenReturn(Optional.of(s));
        service.subscribe(s.getEmail(), true);
        assertThat(s.getGeneration()).isEqualTo(2);
        assertThat(s.getUnsubscribeToken()).isNotEqualTo("old-token");
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.PENDING);
    }

    @Test void confirmationActivatesAndRepeatDoesNotCreateAnotherEvent() {
        var s = subscriber(Subscriber.Status.PENDING);
        s.setConfirmationHash(tokens.hash(token));
        s.setConfirmationExpiresAt(now.plusSeconds(60));
        s.setGeneration(2);
        when(subscribers.findByConfirmationHash(tokens.hash(token))).thenReturn(Optional.of(s));
        service.confirm(token);
        service.confirm(token);
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        assertThat(s.getConfirmedAt()).isEqualTo(now);
        assertThat(s.getConfirmationHash()).isEqualTo(tokens.hash(token));
        var saved = ArgumentCaptor.forClass(SubscriptionConfirmationEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getSubscriberId()).isEqualTo(1L);
        assertThat(saved.getValue().getSubscriberGeneration()).isEqualTo(2);
        assertThat(saved.getValue().getOccurredAt()).isEqualTo(now);
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionConfirmationEvent.Status.PENDING);
    }

    @Test void consumedTokenStaysSuccessfulAfterItsOriginalExpiry() {
        var s = subscriber(Subscriber.Status.ACTIVE);
        s.setConfirmedAt(now.minusSeconds(120));
        s.setConfirmationExpiresAt(now.minusSeconds(60));
        when(subscribers.findByConfirmationHash(tokens.hash(token))).thenReturn(Optional.of(s));
        service.confirm(token);
        assertThat(s.getConfirmedAt()).isEqualTo(now.minusSeconds(120));
        verifyNoInteractions(events);
    }

    @Test void expiredConfirmationDoesNotActivateOrCreateEvent() {
        var s = subscriber(Subscriber.Status.PENDING); s.setConfirmationExpiresAt(now);
        when(subscribers.findByConfirmationHash(tokens.hash(token))).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.confirm(token)).isInstanceOfSatisfying(ConfirmationException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.GONE));
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.PENDING);
        verifyNoInteractions(events);
    }

    @Test void unknownConfirmationIsNotFound() {
        assertThatThrownBy(() -> service.confirm(token)).isInstanceOfSatisfying(ConfirmationException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test void malformedConfirmationIsNotFoundWithoutDatabaseLookup() {
        assertThatThrownBy(() -> service.confirm("inventado")).isInstanceOfSatisfying(ConfirmationException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(subscribers, events);
    }

    @Test void unsubscribedSubscriberCannotBeReactivatedWithOldToken() {
        var s = subscriber(Subscriber.Status.UNSUBSCRIBED);
        when(subscribers.findByConfirmationHash(tokens.hash(token))).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.confirm(token)).isInstanceOfSatisfying(ConfirmationException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.UNSUBSCRIBED);
        verifyNoInteractions(events);
    }

    @Test void frontendLinkPreservesBasePathAndContainsOnlyRawTokenInMessage() {
        properties.setPublicBaseUrl("https://fabetabilo.github.io/web-athenet/");
        service.subscribe("test@example.com", true);
        var queued = ArgumentCaptor.forClass(EmailDelivery.class);
        var saved = ArgumentCaptor.forClass(Subscriber.class);
        verify(deliveries).save(queued.capture());
        verify(subscribers).saveAndFlush(saved.capture());
        String prefix = "https://fabetabilo.github.io/web-athenet/suscripcion/confirmar?token=";
        assertThat(queued.getValue().getBody()).contains(prefix);
        String rawToken = queued.getValue().getBody().split(java.util.regex.Pattern.quote(prefix))[1].split("\\s")[0];
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokens.hash(rawToken)).isEqualTo(saved.getValue().getConfirmationHash());
        assertThat(saved.getValue().getConfirmationHash()).isNotEqualTo(rawToken);
        assertThat(queued.getValue().getBody()).contains("48 horas");
    }

    @Test void unsubscribeDeactivatesAndInvalidatesConfirmation() {
        var s = subscriber(Subscriber.Status.ACTIVE); s.setConfirmationHash("hash");
        when(subscribers.findByUnsubscribeToken("token")).thenReturn(Optional.of(s));
        service.unsubscribe("token");
        assertThat(s.getStatus()).isEqualTo(Subscriber.Status.UNSUBSCRIBED);
        assertThat(s.getConfirmationHash()).isNull();
    }

    private Subscriber subscriber(Subscriber.Status status) {
        var s = new Subscriber(); s.setId(1L); s.setEmail("test@example.com"); s.setStatus(status); return s;
    }
}
