package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.MessageProperties;

/** Compila una selección explícita del productor local, sin arrancar Spring ni usar su configuración privada. */
@EnabledIfSystemProperty(named = "notifications.contract.competition-source", matches = ".+")
class CompetitionProducerContractTest {
    @TempDir static Path classes;
    private static final List<String> SOURCES = List.of(
            "config/RabbitConfig.java", "messaging/ResultRegisteredEnvelope.java",
            "messaging/ResultRegisteredMessage.java", "messaging/ResultEventPublisher.java",
            "messaging/OutboxEventWriter.java", "model/OutboxMessage.java",
            "repository/OutboxMessageRepository.java", "model/Match.java", "model/Competition.java",
            "model/Enrollment.java", "model/enums/MatchRound.java", "model/enums/MatchStatus.java",
            "model/enums/CompetitionStatus.java", "model/enums/CompetitionType.java");

    @BeforeAll static void compileActualProducer() throws Exception {
        Path root = Path.of(System.getProperty("notifications.contract.competition-source"))
                .toAbsolutePath().resolve("src/main/java/com/athenet/competition");
        // Como Maven con fork=true: javac en PATH puede diferir de la JVM que ejecuta los tests.
        var args = new ArrayList<>(List.of("javac", "--release", "25", "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"), "-processor",
                "lombok.launch.AnnotationProcessorHider$AnnotationProcessor", "-d", classes.toString()));
        for (String source : SOURCES) {
            Path file = root.resolve(source);
            assertThat(file).isRegularFile();
            args.add(file.toString());
        }
        var compiler = new ProcessBuilder(args).inheritIO().start();
        try {
            assertThat(compiler.waitFor(60, TimeUnit.SECONDS)).as("Compilación limitada a 60 segundos").isTrue();
            assertThat(compiler.exitValue())
                    .as("Compilar código real de Competencia sin copiar DTOs ni modificar ese proyecto").isZero();
        } finally {
            if (compiler.isAlive()) compiler.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void publisherAndOutboxProduceAnAcceptedMessage(int version) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Object match = instance(loader, "model.Match");
            set(match, "Id", Long.class, 900101L);
            set(match, "CompetitionId", Long.class, 900100L);
            Class<?> round = loader.loadClass("com.athenet.competition.model.enums.MatchRound");
            set(match, "Round", round, round.getField("QUARTER_FINAL").get(null));
            set(match, "Version", int.class, version);
            set(match, "HomeTeamId", Long.class, 12L);
            set(match, "AwayTeamId", Long.class, 15L);
            set(match, "HomeScore", Integer.class, version == 1 ? 2 : 0);
            set(match, "AwayScore", Integer.class, version == 1 ? 1 : 2);
            Object competition = instance(loader, "model.Competition");
            set(competition, "Name", String.class, "Copa Instituciones de Chile 2026");
            set(competition, "SportId", Long.class, 1L);
            set(competition, "WinPoints", int.class, 5);
            set(competition, "DrawPoints", int.class, 2);
            set(competition, "LossPoints", int.class, 1);
            Object home = instance(loader, "model.Enrollment");
            set(home, "TeamName", String.class, "Halcones de Ñuñoa");
            set(home, "InstitutionId", Long.class, 3L);
            Object away = instance(loader, "model.Enrollment");
            set(away, "TeamName", String.class, "Pumas");
            set(away, "InstitutionId", Long.class, 7L);

            Class<?> senderType = loader.loadClass("com.athenet.competition.messaging.OutboxEventWriter");
            Class<?> repositoryType = loader.loadClass("com.athenet.competition.repository.OutboxMessageRepository");
            Object repository = mock(repositoryType);
            Object config = instance(loader, "config.RabbitConfig");
            var mapperMethod = config.getClass().getDeclaredMethod("rabbitObjectMapper");
            mapperMethod.setAccessible(true);
            var producerMapper = (com.fasterxml.jackson.databind.ObjectMapper) mapperMethod.invoke(config);
            Object sender = senderType.getConstructor(repositoryType,com.fasterxml.jackson.databind.ObjectMapper.class)
                    .newInstance(repository,producerMapper);
            Class<?> publisherType = loader.loadClass("com.athenet.competition.messaging.ResultEventPublisher");
            Object publisher = publisherType.getConstructor(senderType).newInstance(sender);
            Instant before = Instant.now();
            publisherType.getMethod("publish", match.getClass(), competition.getClass(), home.getClass(), away.getClass())
                    .invoke(publisher, match, competition, home, away);
            var calls = mockingDetails(repository).getInvocations();
            assertThat(calls).hasSize(1);
            var call = calls.iterator().next();
            assertThat(call.getMethod().getName()).isEqualTo("save");
            Object stored = call.getArgument(0);
            String payload = (String) stored.getClass().getMethod("getPayload").invoke(stored);
            assertThat(stored.getClass().getMethod("getRoutingKey").invoke(stored)).isEqualTo(RabbitConfig.ROUTING_KEY);
            var properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
            var message = new org.springframework.amqp.core.Message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),properties);
            assertThat(message.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
            CompetitionResultContractTest.assertAccepted(message, version);
            var tree = new ApplicationConfig().rabbitObjectMapper().readTree(message.getBody());
            assertThat(Instant.parse(tree.path("occurredAt").asText())).isBetween(before, Instant.now());

            // Captura solo en target; actualizar las muestras versionadas exige revisión explícita.
            Path output = Path.of("target/contracts/competition");
            Files.createDirectories(output);
            Files.write(output.resolve("result-v" + version + ".json"), message.getBody());
            var capturedProperties = message.getMessageProperties();
            new ApplicationConfig().rabbitObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    output.resolve("result-v" + version + "-properties.json").toFile(),
                    Map.of("contentType", capturedProperties.getContentType(), "contentEncoding", capturedProperties.getContentEncoding(),
                            "headers", capturedProperties.getHeaders()));
        }
    }

    private static Object instance(ClassLoader loader, String name) throws Exception {
        return loader.loadClass("com.athenet.competition." + name).getConstructor().newInstance();
    }

    private static void set(Object target, String field, Class<?> type, Object value) throws Exception {
        target.getClass().getMethod("set" + field, type).invoke(target, value);
    }
}
