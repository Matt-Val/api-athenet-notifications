package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.athenet.notifications.service.ResultNotificationService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;

class ResultListenerTest {
    private final ResultNotificationService service = mock(ResultNotificationService.class);
    private ValidatorFactory validators;
    private ResultListener listener;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String JSON = """
            {"messageId":"cab356c5-3ccb-4c35-926b-a978f547071d",
             "occurredAt":"2026-11-02T23:05:41.512Z","type":"resultado.registrado",
             "data":{"matchId":1,"competitionId":1,"competitionName":"Copa Instituciones de Chile 2026",
              "sportId":5,"round":"Cuartos de final","version":1,
              "homeTeamId":202,"awayTeamId":203,"homeTeamName":"Equipo A","awayTeamName":"Equipo B",
              "homeInstitutionId":10,"awayInstitutionId":11,
              "homeScore":2,"awayScore":1,"points":{"win":3,"draw":1,"loss":0}}}
            """;

    @BeforeEach void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        listener = new ResultListener(mapper, validators.getValidator(), service);
    }
    @AfterEach void close() { validators.close(); }

    @Test void consumesTicketJsonWithoutEventIdOrSimulatedDespiteForeignJavaClassHeader() throws Exception {
        var properties = new MessageProperties();
        properties.setHeader("__TypeId__", "com.athenet.competition.messaging.ResultRegisteredEnvelope");
        listener.receive(new Message(JSON.getBytes(StandardCharsets.UTF_8), properties));
        verify(service).accept(argThat(r -> r.matchId() == 1 && r.homeScore() == 2 && r.homePoints() == 3
                        && r.eventId() == null && !r.simulated()
                        && r.competitionName().equals("Copa Instituciones de Chile 2026")
                        && r.homeTeamName().equals("Equipo A") && r.awayTeamName().equals("Equipo B")
                        && r.round().equals("Cuartos de final")),
                eq(Instant.parse("2026-11-02T23:05:41.512Z")));
    }

    @Test void unknownFieldsAreIgnoredAtEnvelopeResultAndPointsLevels() throws Exception {
        var tree = mapper.readTree(JSON);
        ((ObjectNode) tree).put("producerVersion", "next");
        ((ObjectNode) tree.path("data")).putObject("futureField").put("nested", true);
        ((ObjectNode) tree.at("/data/points")).put("bonus", 5);
        listener.receive(message(tree.toString()));
        verify(service).accept(argThat(r -> r.homePoints() == 3), any(Instant.class));
    }

    @Test void optionalLegacyFieldsAreStillAcceptedWhenPresent() throws Exception {
        var tree = mapper.readTree(JSON);
        ((ObjectNode) tree.path("data")).put("eventId", 57).put("simulated", true);
        listener.receive(message(tree.toString()));
        verify(service).accept(argThat(r -> r.eventId() == 57 && r.simulated()), any(Instant.class));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidValuesDoNotReachBusinessLogic(String path, Object value) throws Exception {
        var tree = mapper.readTree(JSON);
        set(tree, path, value);
        assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("/messageId", " "),
                Arguments.of("/type", "evento.creado"),
                Arguments.of("/type", ""),
                Arguments.of("/occurredAt", " "),
                Arguments.of("/data/matchId", 0),
                Arguments.of("/data/matchId", -1),
                Arguments.of("/data/competitionId", 0),
                Arguments.of("/data/sportId", 0),
                Arguments.of("/data/eventId", 0),
                Arguments.of("/data/homeTeamId", 0),
                Arguments.of("/data/awayTeamId", -1),
                Arguments.of("/data/version", 0),
                Arguments.of("/data/version", -1),
                Arguments.of("/data/version", null),
                Arguments.of("/data/homeScore", -1),
                Arguments.of("/data/awayScore", -1),
                Arguments.of("/data/points/win", -1),
                Arguments.of("/data/points/draw", -1),
                Arguments.of("/data/points/loss", -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/messageId", "/occurredAt", "/type", "/data", "/data/matchId",
            "/data/competitionId", "/data/sportId", "/data/version", "/data/homeTeamId", "/data/awayTeamId",
            "/data/homeScore", "/data/awayScore", "/data/points", "/data/points/win",
            "/data/points/draw", "/data/points/loss"})
    void missingRequiredFieldsAreRejected(String path) throws Exception {
        var tree = mapper.readTree(JSON);
        var parent = (ObjectNode) tree.at(path.substring(0, path.lastIndexOf('/')));
        parent.remove(path.substring(path.lastIndexOf('/') + 1));
        assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"competitionName", "homeTeamName", "awayTeamName", "round"})
    void labelsMustBePresentNonBlankAndWithinBounds(String field) throws Exception {
        // Incluye el caso en que la normalización deja el texto vacío.
        for (String value : new String[] {null, "", " ", "\r\n\t\u2028\u202e\u0000", "A".repeat(151)}) {
            var tree = mapper.readTree(JSON);
            set(tree, "/data/" + field, value);
            assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                    .as("%s debe rechazar valor nulo, vacío o fuera del límite", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var tree = mapper.readTree(JSON);
        ((ObjectNode) tree.path("data")).remove(field);
        assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test void labelsAreNormalizedBeforeReachingBusinessLogic() throws Exception {
        var tree = mapper.readTree(JSON);
        set(tree, "/data/competitionName", "\r\n Copa\t2026\u202e ");
        set(tree, "/data/homeTeamName", " \tEquipo\u2028Ñ\r\n");
        set(tree, "/data/awayTeamName", " Equipo\u0000B\u2029 ");
        set(tree, "/data/round", "\tCuartos\u00a0de final\r\n");
        listener.receive(message(tree.toString()));
        verify(service).accept(argThat(r -> r.competitionName().equals("Copa 2026")
                && r.homeTeamName().equals("Equipo Ñ") && r.awayTeamName().equals("Equipo B")
                && r.round().equals("Cuartos de final")), any(Instant.class));
    }

    @Test void maximumLengthLabelsRemainValidWithMaximumScores() throws Exception {
        var tree = mapper.readTree(JSON);
        for (String field : new String[] {"competitionName", "homeTeamName", "awayTeamName", "round"}) {
            set(tree, "/data/" + field, "A".repeat(150));
        }
        set(tree, "/data/homeScore", Integer.MAX_VALUE);
        set(tree, "/data/awayScore", Integer.MAX_VALUE - 1);
        listener.receive(message(tree.toString()));
        verify(service).accept(argThat(r -> r.homeTeamName().length() == 150), any(Instant.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 200})
    void tiedCupResultsAreRejected(int score) throws Exception {
        var tree = mapper.readTree(JSON);
        set(tree, "/data/homeScore", score);
        set(tree, "/data/awayScore", score);
        assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test void awayWinnerAndFirstObservedHigherVersionAreValidContracts() throws Exception {
        var tree = mapper.readTree(JSON);
        set(tree, "/data/version", 3);
        set(tree, "/data/homeScore", 0);
        set(tree, "/data/awayScore", 2);
        listener.receive(message(tree.toString()));
        verify(service).accept(argThat(r -> r.version() == 3 && r.homePoints() == 0 && r.awayPoints() == 3),
                any(Instant.class));
    }

    @Test void invalidTimestampDoesNotReachBusinessLogic() throws Exception {
        var tree = mapper.readTree(JSON);
        set(tree, "/occurredAt", "2026-11-02");
        assertThatThrownBy(() -> listener.receive(message(tree.toString())))
                .isInstanceOf(DateTimeParseException.class);
        verifyNoInteractions(service);
    }

    @Test void malformedJsonAndNullAreRejected() {
        assertThatThrownBy(() -> listener.receive(message("{"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> listener.receive(message("null"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private void set(com.fasterxml.jackson.databind.JsonNode tree, String path, Object value) {
        var parent = (ObjectNode) tree.at(path.substring(0, path.lastIndexOf('/')));
        parent.set(path.substring(path.lastIndexOf('/') + 1), mapper.valueToTree(value));
    }

    private Message message(String json) { return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties()); }
}
