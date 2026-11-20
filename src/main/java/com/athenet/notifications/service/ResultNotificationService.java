package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.messaging.ResultEnvelope.ResultData;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;

@Service
public class ResultNotificationService {
    private final ResultStateRepository states;
    private final SubscriberRepository subscribers;
    private final EmailDeliveryRepository deliveries;
    private final NewsletterProperties properties;
    private final Clock clock;

    public ResultNotificationService(ResultStateRepository states, SubscriberRepository subscribers,
                                     EmailDeliveryRepository deliveries, NewsletterProperties properties, Clock clock) {
        super();
        this.states = states;
        this.subscribers = subscribers;
        this.deliveries = deliveries;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void accept(ResultData result, Instant occurredAt) {
        var state = states.findLockedByMatchId(result.matchId()).orElseGet(() -> {
            var created = new ResultState();
            created.setMatchId(result.matchId());
            // El índice primario resuelve la carrera de dos primeras entregas; RabbitMQ reintenta al perdedor.
            return states.saveAndFlush(created);
        });
        if (result.version() <= state.getLatestVersion()) return;
        boolean correction = result.version() > 1 && state.getLatestVersion() > 0;

        var recipients = subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, occurredAt);
        if (!recipients.isEmpty()) {
            String score = "%s %d-%d %s".formatted(result.homeTeamName(), result.homeScore(),
                    result.awayScore(), result.awayTeamName());
            String subject = (correction ? "Resultado corregido: " : "Resultado: ") + score;
            String summary = correction
                    ? "Se corrigió el resultado de %s vs %s (%s, %s). Resultado final: %s."
                        .formatted(result.homeTeamName(), result.awayTeamName(), result.round(),
                                result.competitionName(), score)
                    : victoryDescription(result);
            String body = """
                    %s

                    %s obtuvo %d %s en este partido.
                    %s obtuvo %d %s en este partido.
                    %sVer competencias y resultados: %s
                    """.formatted(summary, result.homeTeamName(), result.homePoints(), pointWord(result.homePoints()),
                    result.awayTeamName(), result.awayPoints(), pointWord(result.awayPoints()),
                    result.simulated() ? "Este resultado fue generado por simulación.\n" : "",
                    properties.getPublicBaseUrl());

            for (var subscriber : recipients) {
                String unsubscribe = SubscriptionService.base(properties.getApiBaseUrl())
                        + "/api/public/newsletter/unsubscribe?token=" + subscriber.getUnsubscribeToken();
                var delivery = DeliveryFactory.pending(subscriber, EmailDelivery.Kind.RESULT,
                        subject, body + "\nCancelar suscripción: " + unsubscribe + "\n", clock.instant());
                delivery.setMatchId(result.matchId());
                delivery.setResultVersion(result.version());
                deliveries.save(delivery);
            }
        }
        state.setLatestVersion(result.version());
    }

    private String victoryDescription(ResultData result) {
        boolean homeWon = result.homeScore() > result.awayScore();
        return "%s venció %d-%d a %s en %s de la %s.".formatted(
                homeWon ? result.homeTeamName() : result.awayTeamName(),
                homeWon ? result.homeScore() : result.awayScore(),
                homeWon ? result.awayScore() : result.homeScore(),
                homeWon ? result.awayTeamName() : result.homeTeamName(),
                result.round(), result.competitionName());
    }

    private String pointWord(int points) { return points == 1 ? "punto" : "puntos"; }
}
