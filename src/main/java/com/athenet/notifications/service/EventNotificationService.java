package com.athenet.notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import com.athenet.notifications.config.NewsletterProperties;
import com.athenet.notifications.messaging.EventNoticeEnvelope;
import com.athenet.notifications.model.*;
import com.athenet.notifications.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventNotificationService {
    private final EventNoticeStateRepository states;
    private final SubscriberRepository subscribers;
    private final EmailDeliveryRepository deliveries;
    private final NewsletterProperties properties;
    private final Clock clock;

    public EventNotificationService(EventNoticeStateRepository states, SubscriberRepository subscribers,
            EmailDeliveryRepository deliveries, NewsletterProperties properties, Clock clock) {
        this.states = states; this.subscribers = subscribers; this.deliveries = deliveries;
        this.properties = properties; this.clock = clock;
    }

    @Transactional
    public void accept(EventNoticeEnvelope envelope, Instant occurredAt) {
        var data = envelope.data();
        var state = states.findLockedByInternalId(data.internalId()).orElseGet(() -> {
            var created = new EventNoticeState(); created.setInternalId(data.internalId());
            // La PK resuelve primeras recepciones concurrentes; el perdedor revierte y RabbitMQ reintenta.
            return states.saveAndFlush(created);
        });
        if (envelope.noticeVersion() <= state.getLatestVersion()) return;
        boolean initial = envelope.type().equals("evento.publicado");
        boolean prepare = data.sporting() && (!initial || !state.isPublicationHandled());
        // Avance y entregas forman una única transacción, incluso si no hay destinatarios.
        state.setLatestVersion(envelope.noticeVersion());
        state.setLatestType(envelope.type());
        state.setSporting(data.sporting());
        if (initial && data.sporting()) state.setPublicationHandled(true);
        if (!prepare) return;
        String heading = switch (envelope.type()) {
            case "evento.publicado" -> "Nuevo evento deportivo";
            case "evento.modificado" -> "Evento deportivo modificado";
            default -> "DRAFT".equals(data.status()) ? "Evento deportivo retirado" : "Evento deportivo cancelado";
        };
        String body = "%s: %s\n\n%s\nInicio: %s %s\nTérmino: %s %s\nHorario de Chile (America/Santiago).\n%s%s".formatted(
                heading, data.title(), data.description() == null ? "" : data.description(),
                date(data.eventDate()), time(data.startTime()), date(data.endDate()), time(data.endTime()),
                line("Lugar", data.location()), line("Dirección", data.address()));
        for (var subscriber : subscribers.findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status.ACTIVE, occurredAt)) {
            String unsubscribe = SubscriptionService.base(properties.getApiBaseUrl())
                    + "/api/public/newsletter/unsubscribe?token=" + subscriber.getUnsubscribeToken();
            var delivery = DeliveryFactory.pending(subscriber, EmailDelivery.Kind.EVENT,
                    heading + ": " + data.title(), body + "\nCancelar suscripción: " + unsubscribe + "\n", clock.instant());
            delivery.setEventInternalId(data.internalId());
            delivery.setEventVersion(envelope.noticeVersion());
            delivery.setEventNoticeKey(initial ? "INITIAL" : "VERSION:" + envelope.noticeVersion());
            deliveries.save(delivery);
        }
    }

    private static String line(String label, String value) {
        return value == null || value.isBlank() ? "" : label + ": " + value + "\n";
    }
    private static String date(String value) { return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/uuuu")); }
    private static String time(String value) { return LocalTime.parse(value).format(DateTimeFormatter.ofPattern("HH:mm")); }
}
