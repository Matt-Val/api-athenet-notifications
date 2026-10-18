package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.athenet.notifications.messaging.EventNoticeEnvelope;
import com.athenet.notifications.messaging.EventNoticeListener;
import com.athenet.notifications.service.EventNotificationService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Productor real local -> mensaje AMQP capturado -> listener real; sin broker ni credenciales. */
@EnabledIfSystemProperty(named = "notifications.contract.events-source", matches = ".+")
class EventProducerContractTest {
    @TempDir static Path classes;

    @BeforeAll static void compileProducer() throws Exception {
        var root = Path.of(System.getProperty("notifications.contract.events-source"))
                .toAbsolutePath().resolve("src/main/java/com/athenet/events");
        var args = new ArrayList<>(List.of("javac", "--release", "25", "-encoding", "UTF-8", "-classpath",
                System.getProperty("java.class.path"), "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                "-d", classes.toString()));
        for (String name : List.of("config/RabbitConfig.java", "messaging/EventNotificationData.java",
                "messaging/EventNotificationEnvelope.java", "messaging/EventNotificationPublisher.java",
                "model/Event.java", "model/EventType.java", "model/EventStatus.java")) {
            var file = root.resolve(name); assertThat(file).isRegularFile(); args.add(file.toString());
        }
        var compiler = new ProcessBuilder(args).inheritIO().start();
        try {
            assertThat(compiler.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(compiler.exitValue()).isZero();
        } finally { if (compiler.isAlive()) compiler.destroyForcibly(); }
    }

    @ParameterizedTest @ValueSource(strings = {"evento.publicado", "evento.modificado", "evento.cancelado"})
    void realPublisherProducesAcceptedNotice(String type) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
             var validators = Validation.buildDefaultValidatorFactory()) {
            var eventType = loader.loadClass("com.athenet.events.model.EventType");
            var statusType = loader.loadClass("com.athenet.events.model.EventStatus");
            var entityType = loader.loadClass("com.athenet.events.model.Event");
            var event = entityType.getConstructor().newInstance();
            set(event, "InternalId", String.class, "EVT-CONTRACT-1"); set(event, "Title", String.class, "Jornada de pista");
            set(event, "Description", String.class, "Actividad deportiva");
            set(event, "Type", eventType, eventType.getField("TRACKDAY").get(null));
            set(event, "Status", statusType, statusType.getField("PUBLISHED").get(null));
            set(event, "EventDate", LocalDate.class, LocalDate.of(2026, 10, 24));
            set(event, "StartTime", LocalTime.class, LocalTime.of(22, 0));
            set(event, "EndDate", LocalDate.class, LocalDate.of(2026, 10, 25));
            set(event, "EndTime", LocalTime.class, LocalTime.of(0, 30));
            set(event, "Location", String.class, "Ñuñoa");
            var dataType = loader.loadClass("com.athenet.events.messaging.EventNotificationData");
            Object before = null;
            long expectedVersion = 1;
            if (!type.endsWith("publicado")) {
                before = dataType.getMethod("from", entityType).invoke(null, event);
                if (type.endsWith("modificado")) {
                    set(event, "NotificationVersion", long.class, 1L); expectedVersion = 2;
                    set(event, "Location", String.class, "Otro estadio");
                } else {
                    set(event, "NotificationVersion", long.class, 2L); expectedVersion = 3;
                    set(event, "Status", statusType, statusType.getField("CANCELLED").get(null));
                }
            }
            Object config = loader.loadClass("com.athenet.events.config.RabbitConfig").getConstructor().newInstance();
            var mapperMethod = config.getClass().getDeclaredMethod("rabbitObjectMapper"); mapperMethod.setAccessible(true);
            var producerMapper = (com.fasterxml.jackson.databind.ObjectMapper) mapperMethod.invoke(config);
            var rabbit = mock(RabbitTemplate.class);
            var publisherType = loader.loadClass("com.athenet.events.messaging.EventNotificationPublisher");
            Object publisher = publisherType.getConstructor(RabbitTemplate.class, com.fasterxml.jackson.databind.ObjectMapper.class,
                    boolean.class).newInstance(rabbit, producerMapper, true);
            publisherType.getMethod("changed", dataType, entityType, boolean.class).invoke(publisher, before, event, false);
            var message = ArgumentCaptor.forClass(Message.class);
            verify(rabbit).send(eq(RabbitConfig.EXCHANGE), eq(type), message.capture());
            var service = mock(EventNotificationService.class);
            new EventNoticeListener(new ApplicationConfig().rabbitObjectMapper(), validators.getValidator(), service).receive(message.getValue());
            var accepted = ArgumentCaptor.forClass(EventNoticeEnvelope.class);
            verify(service).accept(accepted.capture(), any(Instant.class));
            assertThat(accepted.getValue().type()).isEqualTo(type);
            assertThat(accepted.getValue().noticeVersion()).isEqualTo(expectedVersion);
            assertThat(accepted.getValue().data().sporting()).isTrue();
            assertThat(accepted.getValue().data().title()).isEqualTo("Jornada de pista");
            assertThat(accepted.getValue().data().official()).isFalse();
            assertThat(message.getValue().getMessageProperties().getHeaders()).containsEntry("__TypeId__",
                    "com.athenet.events.messaging.EventNotificationEnvelope");
            var output = Path.of("target/contracts/events"); Files.createDirectories(output);
            Files.write(output.resolve(type + ".json"), message.getValue().getBody());
        }
    }

    private static void set(Object event, String field, Class<?> type, Object value) throws Exception {
        event.getClass().getMethod("set" + field, type).invoke(event, value);
    }
}
