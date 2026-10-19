package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import com.athenet.notifications.service.EventNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;

class EventNoticeListenerTest {
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final EventNotificationService service = mock(EventNotificationService.class);
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final EventNoticeListener listener = new EventNoticeListener(mapper, validators.getValidator(), service);
    private static final String JSON = """
        {"messageId":"event-1","occurredAt":"2026-10-17T21:00:00Z","type":"evento.publicado","version":1,
         "data":{"internalId":"EVT-42","title":"Jornada deportiva","description":"Texto",
          "type":"TRACKDAY","official":false,"status":"PUBLISHED",
          "eventDate":"2026-10-24","startTime":"22:00","endDate":"2026-10-25","endTime":"00:30",
          "location":null,"address":null,"sportId":null}}
        """;

    @AfterEach void close() { validators.close(); }

    @Test void ignoresProducerJavaHeaderAndUnknownFieldsAndAllowsLegacyPublicationWithoutVersion() throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON); json.remove("version");
        var properties = new MessageProperties();
        properties.setHeader("__TypeId__", "foreign.publisher.NotOnClasspath");
        listener.receive(new Message(mapper.writeValueAsBytes(json), properties));
        verify(service).accept(argThat(event -> event.noticeVersion() == 1 && event.data().sporting()),
                eq(Instant.parse("2026-10-17T21:00:00Z")));
    }

    @ParameterizedTest @ValueSource(strings = {"evento.modificado", "evento.cancelado"})
    void changesAndCancellationsRequireAnExplicitPositiveVersion(String type) throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON); json.put("type", type); json.remove("version");
        if (type.endsWith("cancelado")) ((ObjectNode) json.path("data")).put("status", "CANCELLED");
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"type", "messageId", "occurredAt", "data"})
    void missingEnvelopeFieldIsRejected(String field) throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON); json.remove(field);
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"internalId", "title", "type", "eventDate", "startTime", "endDate", "endTime"})
    void missingEventFieldIsRejected(String field) throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON); ((ObjectNode) json.path("data")).remove(field);
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"DATE", "INTERVAL", "STATUS", "VERSION", "TYPE", "LONG_TITLE", "TIME"})
    void invalidContractDoesNotReachBusinessLogic(String reason) throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON);
        var data = (ObjectNode) json.path("data");
        switch (reason) {
            case "DATE" -> data.put("eventDate", "2026-02-30");
            case "TIME" -> data.put("startTime", "25:00");
            case "INTERVAL" -> data.put("endDate", "2026-10-23");
            case "STATUS" -> data.put("status", "DRAFT");
            case "VERSION" -> json.put("version", 0);
            case "TYPE" -> json.put("type", "resultado.registrado");
            default -> data.put("title", "x".repeat(256));
        }
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(service);
    }

    @Test void normalizesTitleAndDescriptionToPreventHeaderAndLayoutInjection() throws Exception {
        var json = (ObjectNode) mapper.readTree(JSON);
        ((ObjectNode) json.path("data")).put("title", " \r\nJornada\u2028deportiva\u202e ");
        listener.receive(message(json));
        verify(service).accept(argThat(event -> event.data().title().equals("Jornada deportiva")), any());
    }

    @Test void malformedJsonAndNullAreRejected() {
        for (String json : new String[] {"{", "null"}) {
            assertThatThrownBy(() -> listener.receive(new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties())))
                    .isInstanceOf(Exception.class);
        }
        verifyNoInteractions(service);
    }

    private Message message(ObjectNode json) throws Exception { return new Message(mapper.writeValueAsBytes(json), new MessageProperties()); }
}
