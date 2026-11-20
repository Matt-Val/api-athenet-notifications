package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.messaging.ResultEnvelope.*;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;

class ResultNotificationServiceTest {
    private final Instant now = Instant.parse("2026-10-13T20:00:00Z");
    private final ResultStateRepository states = mock(ResultStateRepository.class);
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final NewsletterProperties properties = new NewsletterProperties();
    private final ResultNotificationService service = new ResultNotificationService(states, subscribers, deliveries,
            properties, Clock.fixed(now, ZoneOffset.UTC));
    private final ResultState state = new ResultState();

    @BeforeEach void setup() {
        state.setMatchId(1L);
        when(states.findLockedByMatchId(1L)).thenReturn(Optional.of(state));
        properties.setApiBaseUrl("https://api.athenet.test/notifications/");
        properties.setPublicBaseUrl("https://athenet.test/competencias");
    }

    @Test void duplicateOrOlderVersionDoesNotQueueMail() {
        state.setLatestVersion(3);
        service.accept(result(3, 2, 1), now);
        service.accept(result(2, 2, 0), now);
        verifyNoInteractions(subscribers, deliveries);
        assertThat(state.getLatestVersion()).isEqualTo(3);
    }

    @Test void newResultQueuesOnePersonalizedDeliveryPerActiveSubscriber() {
        var a = subscriber(1L); var b = subscriber(2L);
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now)).thenReturn(List.of(a, b));
        service.accept(result(1, 2, 1), now);
        var emails = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(deliveries, times(2)).save(emails.capture());
        var queued = emails.getAllValues();
        assertThat(queued).extracting(EmailDelivery::getRecipient).containsExactly(a.getEmail(), b.getEmail());
        for (int i = 0; i < queued.size(); i++) {
            var delivery = queued.get(i);
            assertThat(delivery.getSubject()).isEqualTo("Resultado: Equipo A 2-1 Equipo B");
            assertThat(delivery.getBody()).isEqualTo("""
                    Equipo A venció 2-1 a Equipo B en Cuartos de final de la Copa 2026.

                    Equipo A obtuvo 3 puntos en este partido.
                    Equipo B obtuvo 0 puntos en este partido.
                    Ver competencias y resultados: https://athenet.test/competencias

                    Cancelar suscripción: https://api.athenet.test/notifications/api/public/newsletter/unsubscribe?token=token%d
                    """.formatted(i + 1));
            assertThat(delivery.getKind()).isEqualTo(EmailDelivery.Kind.RESULT);
            assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.PENDING);
            assertThat(delivery.getMatchId()).isEqualTo(1L);
            assertThat(delivery.getResultVersion()).isEqualTo(1);
            assertThat(delivery.getSubscriberId()).isEqualTo((long) i + 1);
            assertThat(delivery.getSubscriberGeneration()).isEqualTo(1);
            assertThat(delivery.getCreatedAt()).isEqualTo(now);
        }
        assertThat(state.getLatestVersion()).isEqualTo(1);
    }

    @Test void awayVictoryOrdersWinnerAndScoresInBodyButKeepsHomeOrderInSubject() {
        withRecipient();
        service.accept(result(1, 1, 3), now);
        var email = savedDelivery();
        assertThat(email.getSubject()).isEqualTo("Resultado: Equipo A 1-3 Equipo B");
        assertThat(email.getBody()).startsWith("Equipo B venció 3-1 a Equipo A en Cuartos de final de la Copa 2026.")
                .contains("Equipo A obtuvo 0 puntos", "Equipo B obtuvo 3 puntos");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void correctionUsesPreviouslyProcessedHistoryAndCurrentMessage(int version) {
        state.setLatestVersion(1);
        withRecipient();
        service.accept(result(version, 0, 2), now);
        var email = savedDelivery();
        assertThat(email.getSubject()).isEqualTo("Resultado corregido: Equipo A 0-2 Equipo B");
        assertThat(email.getBody()).startsWith("Se corrigió el resultado de Equipo A vs Equipo B "
                + "(Cuartos de final, Copa 2026). Resultado final: Equipo A 0-2 Equipo B.")
                .contains("Equipo A obtuvo 0 puntos", "Equipo B obtuvo 3 puntos", "/unsubscribe?token=token1")
                .doesNotContain("venció");
        assertThat(email.getResultVersion()).isEqualTo(version);
        assertThat(state.getLatestVersion()).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void firstObservedHigherVersionWithExistingEmptyStateIsInitial(int version) {
        withRecipient();
        service.accept(result(version, 2, 1), now);
        assertInitial(savedDelivery());
        assertThat(state.getLatestVersion()).isEqualTo(version);
    }

    @Test void firstObservedHigherVersionWithNoStateCreatesInitialResult() {
        when(states.findLockedByMatchId(1L)).thenReturn(Optional.empty());
        when(states.saveAndFlush(any(ResultState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        withRecipient();
        service.accept(result(4, 2, 1), now);
        assertInitial(savedDelivery());
        var created = ArgumentCaptor.forClass(ResultState.class);
        verify(states).saveAndFlush(created.capture());
        assertThat(created.getValue().getMatchId()).isEqualTo(1L);
        assertThat(created.getValue().getLatestVersion()).isEqualTo(4);
    }

    @Test void resultProcessedWithoutRecipientsStillCountsAsHistoryForCorrection() {
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now)).thenReturn(List.of());
        service.accept(result(1, 2, 1), now);
        verifyNoInteractions(deliveries);
        withRecipient();
        service.accept(result(2, 3, 1), now);
        assertThat(savedDelivery().getSubject()).isEqualTo("Resultado corregido: Equipo A 3-1 Equipo B");
    }

    @Test void noSubscribersAdvancesVersionWithoutCreatingDeliveries() {
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now)).thenReturn(List.of());
        service.accept(result(1, 2, 1), now);
        assertThat(state.getLatestVersion()).isEqualTo(1);
        verifyNoInteractions(deliveries);
    }

    @Test void deliveryPersistenceFailureDoesNotAdvanceVersion() {
        state.setLatestVersion(1);
        withRecipient();
        when(deliveries.save(any(EmailDelivery.class))).thenThrow(new IllegalStateException("storage failure"));
        assertThatThrownBy(() -> service.accept(result(2, 2, 1), now)).isInstanceOf(IllegalStateException.class);
        assertThat(state.getLatestVersion()).isEqualTo(1);
    }

    @Test void customPointsAndSingularAreRenderedFromMessage() {
        withRecipient();
        var custom = new ResultData(1L, 2L, 3L, null, 1, 10L, 11L, 20L, 21L,
                2, 1, false, new Points(5, 2, 1), "Copa 2026", "Equipo A", "Equipo B", "Final");
        service.accept(custom, now);
        assertThat(savedDelivery().getBody()).contains("Equipo A obtuvo 5 puntos en este partido.",
                "Equipo B obtuvo 1 punto en este partido.").doesNotContain("1 puntos");
    }

    @Test void simulationNoticeIsPreservedWhenProvided() {
        withRecipient();
        var simulated = new ResultData(1L, 2L, 3L, 57L, 1, 10L, 11L, 20L, 21L,
                2, 1, true, new Points(3, 1, 0), "Copa 2026", "Equipo A", "Equipo B", "Final");
        service.accept(simulated, now);
        assertThat(savedDelivery().getBody()).contains("Este resultado fue generado por simulación.\nVer competencias y resultados:");
    }

    @Test void largestAllowedNamesAndScoresFitSubjectColumnAndStayOnOneLine() {
        state.setLatestVersion(1);
        withRecipient();
        var longest = new ResultData(1L, 2L, 3L, null, Integer.MAX_VALUE, 10L, 11L, null, null,
                Integer.MAX_VALUE, Integer.MAX_VALUE - 1, false, new Points(3, 1, 0),
                "C".repeat(150), "\r\n" + "A".repeat(150) + "\u2028", "B".repeat(150), "Final");
        service.accept(longest, now);
        assertThat(savedDelivery().getSubject()).hasSizeLessThanOrEqualTo(500)
                .startsWith("Resultado corregido: ").doesNotContain("\r", "\n", "\u2028");
    }

    private void assertInitial(EmailDelivery email) {
        assertThat(email.getSubject()).isEqualTo("Resultado: Equipo A 2-1 Equipo B");
        assertThat(email.getBody()).startsWith("Equipo A venció 2-1 a Equipo B en Cuartos de final de la Copa 2026.")
                .doesNotContain("Se corrigió");
    }

    private void withRecipient() {
        when(subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, now))
                .thenReturn(List.of(subscriber(1L)));
    }

    private EmailDelivery savedDelivery() {
        var email = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(deliveries).save(email.capture());
        return email.getValue();
    }

    private Subscriber subscriber(Long id) {
        var s = new Subscriber(); s.setId(id); s.setEmail("test" + id + "@example.com");
        s.setStatus(Subscriber.Status.ACTIVE); s.setConfirmedAt(now.minusSeconds(60));
        s.setGeneration(1); s.setUnsubscribeToken("token" + id); return s;
    }

    private ResultData result(int version, int home, int away) {
        return new ResultData(1L, 2L, 3L, null, version, 10L, 11L, 20L, 21L, home, away, false,
                new Points(3, 1, 0), "Copa 2026", "Equipo A", "Equipo B", "Cuartos de final");
    }
}
