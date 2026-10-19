package com.athenet.notifications.diagnostics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class ConnectionCheckTest {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(captured);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

    @Test void rabbitAuthenticatesThenClosesWithoutOpeningChannels() {
        var factory = mock(ConnectionFactory.class);
        var connection = mock(Connection.class);
        when(context.getBean(ConnectionFactory.class)).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        assertThat(ConnectionCheck.check("rabbit", () -> context, output)).isZero();
        verify(factory).createConnection();
        verify(connection).isOpen(); verify(connection).close(); verify(context).close();
        verifyNoMoreInteractions(factory, connection);
        assertThat(captured.toString()).contains("OK RabbitMQ");
    }

    @Test void failedRabbitConnectionIsClosedAndErrorDoesNotExposeCredentials() {
        var factory = mock(ConnectionFactory.class);
        var connection = mock(Connection.class);
        when(context.getBean(ConnectionFactory.class)).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenThrow(new IllegalStateException("private-rabbit-password"));
        assertThat(ConnectionCheck.check("rabbit", () -> context, output)).isEqualTo(1);
        verify(connection).close(); verify(context).close();
        assertThat(captured.toString()).contains("ERROR RabbitMQ", "IllegalStateException")
                .doesNotContain("private-rabbit-password");
    }

    @Test void smtpTestsTransportWithoutSendingMessages() throws Exception {
        var sender = mock(JavaMailSenderImpl.class);
        when(context.getBean(JavaMailSenderImpl.class)).thenReturn(sender);
        assertThat(ConnectionCheck.check("smtp", () -> context, output)).isZero();
        verify(sender).testConnection(); verifyNoMoreInteractions(sender); verify(context).close();
        assertThat(captured.toString()).contains("OK SMTP", "sin enviar correo");
    }

    @Test void smtpAuthenticationFailureDoesNotExposeProviderMessage() throws Exception {
        var sender = mock(JavaMailSenderImpl.class);
        when(context.getBean(JavaMailSenderImpl.class)).thenReturn(sender);
        doThrow(new AuthenticationFailedException("private-smtp-password provider reply")).when(sender).testConnection();
        assertThat(ConnectionCheck.check("smtp", () -> context, output)).isEqualTo(1);
        verify(context).close();
        assertThat(captured.toString()).contains("ERROR SMTP", "AuthenticationFailedException")
                .doesNotContain("private-smtp-password", "provider reply");
    }

    @Test void bootstrapFailureIsReportedWithoutItsPrivateDetails() {
        assertThat(ConnectionCheck.check("rabbit", () -> { throw new IllegalStateException("private-context-data"); }, output))
                .isEqualTo(1);
        assertThat(captured.toString()).contains("ERROR RabbitMQ").doesNotContain("private-context-data");
    }

    @Test void invalidTargetDoesNotCreateContext() {
        assertThat(ConnectionCheck.check("other", () -> { throw new AssertionError("No crear contexto"); }, output))
                .isEqualTo(2);
    }

    @Test void rabbitApplicationOnlyImportsItsConnectionConfiguration() {
        var app = ConnectionCheck.isolatedApplication("rabbit");
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(app.getAllSources()).containsExactly(RabbitAutoConfiguration.class);
    }

    @Test void smtpApplicationOnlyImportsItsConnectionConfiguration() {
        var app = ConnectionCheck.isolatedApplication("smtp");
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(app.getAllSources()).containsExactly(MailSenderAutoConfiguration.class);
    }

    @Test void rabbitAutoConfigurationHasNoAdminOrBusinessComponentsWhenPaused() {
        new ApplicationContextRunner().withUserConfiguration(RabbitAutoConfiguration.class)
                .withPropertyValues("spring.rabbitmq.dynamic=false", "spring.rabbitmq.listener.simple.auto-startup=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ConnectionFactory.class).doesNotHaveBean(RabbitAdmin.class);
                    assertThat(ctx).doesNotHaveBean("resultListener").doesNotHaveBean("emailListener")
                            .doesNotHaveBean("deliveryScheduler").doesNotHaveBean("entityManagerFactory");
                });
    }

    @Test void smtpAutoConfigurationCreatesTransportWithoutConnectingAtStartup() {
        new ApplicationContextRunner().withUserConfiguration(MailSenderAutoConfiguration.class)
                .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=1025",
                        "spring.mail.test-connection=false", "spring.mail.properties.mail.smtp.auth=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JavaMailSenderImpl.class);
                    assertThat(ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties()).containsEntry("mail.smtp.auth", "true");
                    assertThat(ctx).doesNotHaveBean("deliveryScheduler").doesNotHaveBean("entityManagerFactory");
                });
    }
}
