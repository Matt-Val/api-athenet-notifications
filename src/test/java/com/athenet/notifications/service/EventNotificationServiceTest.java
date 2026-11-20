package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.athenet.notifications.support.EventNoticeFixtures.notice;
import java.time.*;
import java.util.*;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.messaging.EventNoticeEnvelope;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class EventNotificationServiceTest {
    private final Instant now = Instant.parse("2026-10-17T21:00:00Z");
    private final EventNoticeStateRepository states = mock(EventNoticeStateRepository.class);
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final EventNoticeState state = new EventNoticeState();
    private final NewsletterProperties properties = new NewsletterProperties();
    private final EventNotificationService service = new EventNotificationService(states, subscribers, deliveries,
            properties, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        state.setInternalId("EVT-42");
        when(states.findLockedByInternalId("EVT-42")).thenReturn(Optional.of(state));
        properties.setPublicBaseUrl("https://front.example.test");
    }

    @ParameterizedTest @ValueSource(strings = {"MATCH", "TRACKDAY"})
    void initialSportNoticeQueuesForExistingActiveSubscribersWithoutEventLink(String type) {
        withRecipients(); service.accept(notice("evento.publicado", 1, type), now);
        var captor = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(deliveries, times(2)).save(captor.capture());
        var notices = captor.getAllValues();
        assertThat(notices).allSatisfy(delivery -> {
            assertThat(delivery.getKind()).isEqualTo(EmailDelivery.Kind.EVENT);
            assertThat(delivery.getSubject()).isEqualTo("Nuevo evento deportivo: Jornada deportiva");
            assertThat(delivery.getBody()).contains("24/10/2026 22:00", "25/10/2026 00:30", "Horario de Chile", "Lugar: Estadio");
            assertThat(delivery.getBody()).doesNotContain(properties.getPublicBaseUrl());
            assertThat(delivery.getEventNoticeKey()).isEqualTo("INITIAL");
            assertThat(delivery.getEventVersion()).isEqualTo(1);
            assertThat(delivery.getMatchId()).isNull();
        });
        assertThat(notices.get(0).getBody()).contains("token=token1");
        assertThat(notices.get(1).getBody()).contains("token=token2");
        assertThat(state.isPublicationHandled()).isTrue();
    }

    @Test void newUuidAndRepublishingDoNotCreateAnotherInitialNotice() {
        withRecipients(); service.accept(notice("evento.publicado", 1, "MATCH"), now);
        service.accept(notice("evento.publicado", 1, "MATCH"), now);
        service.accept(notice("evento.publicado", 5, "MATCH"), now);
        verify(deliveries, times(2)).save(any());
        assertThat(state.getLatestVersion()).isEqualTo(5);
    }

    @ParameterizedTest @ValueSource(strings = {"MEETING", "OTHER"})
    void excludesNonSportTypesEvenWhenOfficial(String type) {
        service.accept(notice("evento.publicado", 1, type), now);
        assertThat(state.isSporting()).isFalse();
        verifyNoInteractions(subscribers, deliveries);
    }

    @Test void unofficialSportIsIncluded() {
        withRecipients(); service.accept(notice("evento.publicado", 1, "TRACKDAY"), now);
        verify(deliveries, times(2)).save(any()); // fixture official=false
    }

    @ParameterizedTest @ValueSource(strings = {"evento.modificado", "evento.cancelado"})
    void changedAndCancelledNoticeHaveTheirOwnDeliveryKey(String type) {
        state.setLatestVersion(1); state.setPublicationHandled(true);
        withRecipients(); service.accept(notice(type, 2, "MATCH"), now);
        var captor = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(deliveries, times(2)).save(captor.capture());
        assertThat(captor.getValue().getSubject()).startsWith(type.endsWith("modificado") ? "Evento deportivo modificado" : "Evento deportivo cancelado");
        assertThat(captor.getValue().getEventNoticeKey()).isEqualTo("VERSION:2");
        service.accept(notice("evento.publicado", 1, "MATCH"), now);
        verify(deliveries, times(2)).save(any());
        assertThat(state.getLatestVersion()).isEqualTo(2);
    }

    @Test void advancesStateWithNoRecipientsAndDoesNotSendHistoryAfterResubscription() {
        service.accept(notice("evento.publicado", 1, "MATCH"), now);
        assertThat(state.getLatestVersion()).isEqualTo(1);
        withRecipients(); service.accept(notice("evento.publicado", 2, "MATCH"), now);
        verifyNoInteractions(deliveries);
    }

    @Test void createsFirstStateAndPropagatesStorageFailureForRabbitRetry() {
        when(states.findLockedByInternalId("EVT-42")).thenReturn(Optional.empty());
        when(states.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("concurrent insert"));
        assertThatThrownBy(() -> service.accept(notice("evento.publicado", 1, "MATCH"), now))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verifyNoInteractions(subscribers, deliveries);
    }

    private void withRecipients() {
        var a = new Subscriber(); a.setId(1L); a.setGeneration(1); a.setEmail("first@example.test"); a.setUnsubscribeToken("token1");
        var b = new Subscriber(); b.setId(2L); b.setGeneration(1); b.setEmail("second@example.test"); b.setUnsubscribeToken("token2");
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now)).thenReturn(List.of(a, b));
    }

}
