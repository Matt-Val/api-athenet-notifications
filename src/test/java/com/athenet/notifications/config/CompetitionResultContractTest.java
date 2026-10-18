package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.athenet.notifications.messaging.ResultEnvelope;
import com.athenet.notifications.messaging.ResultListener;
import com.athenet.notifications.service.ResultNotificationService;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** Muestras capturadas del publicador y conversor de Competencia, no del DTO consumidor. */
class CompetitionResultContractTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void acceptsCapturedProducerMessage(int version) throws Exception {
        String resource = "/contracts/competition/result-v" + version;
        try (var body = getClass().getResourceAsStream(resource + ".json");
             var metadata = getClass().getResourceAsStream(resource + "-properties.json")) {
            assertThat(body).isNotNull();
            assertThat(metadata).isNotNull();
            var captured = new ApplicationConfig().rabbitObjectMapper().readTree(metadata);
            var properties = new MessageProperties();
            properties.setContentType(captured.path("contentType").asText());
            properties.setContentEncoding(captured.path("contentEncoding").asText());
            captured.path("headers").properties().forEach(entry -> properties.setHeader(entry.getKey(), entry.getValue().asText()));
            assertAccepted(new Message(body.readAllBytes(), properties), version);
        }
    }

    static void assertAccepted(Message message, int version) throws Exception {
        var mapper = new ApplicationConfig().rabbitObjectMapper();
        var envelope = mapper.readTree(message.getBody());
        assertThat(UUID.fromString(envelope.path("messageId").asText())).isNotNull();
        Instant occurredAt = Instant.parse(envelope.path("occurredAt").asText());
        assertThat(envelope.path("type").asText()).isEqualTo(RabbitConfig.ROUTING_KEY);
        assertThat(envelope.path("data").has("eventId")).isFalse();
        assertThat(envelope.path("data").has("simulated")).isFalse();
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getContentEncoding()).isEqualToIgnoringCase(StandardCharsets.UTF_8.name());
        // Las muestras históricas llevan __TypeId__; la outbox actual envía JSON sin ese header.
        // El listener debe aceptar ambos sin resolver clases del productor.

        var service = mock(ResultNotificationService.class);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            new ResultListener(mapper, validators.getValidator(), service).receive(message);
        }
        var data = ArgumentCaptor.forClass(ResultEnvelope.ResultData.class);
        verify(service).accept(data.capture(), eq(occurredAt));
        verifyNoMoreInteractions(service);
        var result = data.getValue();
        assertThat(result.matchId()).isEqualTo(900101L);
        assertThat(result.competitionId()).isEqualTo(900100L);
        assertThat(result.sportId()).isEqualTo(1L);
        assertThat(result.version()).isEqualTo(version);
        assertThat(result.competitionName()).isEqualTo("Copa Instituciones de Chile 2026");
        assertThat(result.round()).isEqualTo("Cuartos de final");
        assertThat(result.homeTeamId()).isEqualTo(12L);
        assertThat(result.awayTeamId()).isEqualTo(15L);
        assertThat(result.homeTeamName()).isEqualTo("Halcones de Ñuñoa");
        assertThat(result.awayTeamName()).isEqualTo("Pumas");
        assertThat(result.homeInstitutionId()).isEqualTo(3L);
        assertThat(result.awayInstitutionId()).isEqualTo(7L);
        assertThat(result.homeScore()).isEqualTo(version == 1 ? 2 : 0);
        assertThat(result.awayScore()).isEqualTo(version == 1 ? 1 : 2);
        assertThat(result.points()).isEqualTo(new ResultEnvelope.Points(5, 2, 1));
        assertThat(result.homePoints()).isEqualTo(version == 1 ? 5 : 1);
        assertThat(result.awayPoints()).isEqualTo(version == 1 ? 1 : 5);
        assertThat(result.eventId()).isNull();
        assertThat(result.simulated()).isFalse();
    }
}
