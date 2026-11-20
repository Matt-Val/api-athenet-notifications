package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import java.io.StringWriter;
import com.athenet.notifications.model.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

/** Valida el esquema generado incluso cuando el entorno no permite abrir PostgreSQL. */
class PersistenceMappingTest {
    @Test void generatesPostgresSchemaWithDeduplicationConstraintAndDispatchIndex() throws Exception {
        var ddl = new StringWriter();
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", ddl)
                .build();
        try {
            var metadata = new MetadataSources(registry).addAnnotatedClass(Subscriber.class)
                    .addAnnotatedClass(ResultState.class).addAnnotatedClass(EmailDelivery.class)
                    .addAnnotatedClass(EventNoticeState.class)
                    .addAnnotatedClass(SubscriptionConfirmationEvent.class).buildMetadata();
            try (var factory = metadata.buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
                assertThat(ddl.toString()).contains("newsletter_subscriber", "newsletter_result_state", "newsletter_email_delivery",
                        "uq_newsletter_result_recipient", "idx_newsletter_delivery_due", "next_attempt_at", "consent", "consent_accepted_at", "newsletter_confirmation_event",
                        "uq_newsletter_confirmation_generation", "idx_newsletter_confirmation_pending", "idx_newsletter_confirmation_due");
                assertThat(ddl.toString()).contains("publication_status", "publication_attempts", "next_publication_at",
                        "publication_error", "published_at", "created_at", "idx_newsletter_publication_due");
                assertThat(ddl.toString()).contains("newsletter_event_state", "event_internal_id", "event_notice_key",
                        "event_version", "uq_newsletter_event_recipient", "publication_handled");
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/contracts"));
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/contracts/notifications-schema.sql"), ddl.toString());
                var query = com.athenet.notifications.repository.EmailDeliveryRepository.class
                        .getMethod("findPublicationDueIds", EmailDelivery.Status.class, EmailDelivery.PublicationStatus.class,
                                java.time.Instant.class, org.springframework.data.domain.Pageable.class)
                        .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                try (var session = factory.openSession()) {
                    session.createSelectionQuery(query, Long.class)
                            .setParameter("status", EmailDelivery.Status.PENDING)
                            .setParameter("publicationStatus", EmailDelivery.PublicationStatus.PENDING)
                            .setParameter("now", java.time.Instant.parse("2026-10-15T04:00:00Z"));
                    var confirmationQuery = com.athenet.notifications.repository.SubscriptionConfirmationEventRepository.class
                            .getMethod("findPublicationDueIds", SubscriptionConfirmationEvent.Status.class,
                                    java.time.Instant.class, org.springframework.data.domain.Pageable.class)
                            .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                    session.createSelectionQuery(confirmationQuery, java.util.UUID.class)
                            .setParameter("status", SubscriptionConfirmationEvent.Status.PENDING)
                            .setParameter("now", java.time.Instant.parse("2026-10-16T14:00:00Z"));
                }
            }
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
