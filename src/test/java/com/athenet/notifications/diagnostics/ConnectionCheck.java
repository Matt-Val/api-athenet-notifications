package com.athenet.notifications.diagnostics;

import java.io.PrintStream;
import java.util.function.Supplier;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Herramienta manual de pruebas; se empaqueta por separado y nunca en el JAR de producción. */
public final class ConnectionCheck {
    private ConnectionCheck() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
            System.out.println("Uso: ConnectionCheck rabbit|smtp");
            return;
        }
        if (args.length != 1 || !validTarget(args[0])) {
            System.err.println("Uso: ConnectionCheck rabbit|smtp");
            System.exit(2);
            return;
        }
        String target = args[0];
        int status = check(target, () -> isolatedApplication(target).run(
                "--logging.level.root=OFF",
                "--spring.main.banner-mode=off",
                "--spring.rabbitmq.dynamic=false",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.mail.test-connection=false"), System.out);
        if (status != 0) System.exit(status);
    }

    static SpringApplication isolatedApplication(String target) {
        Class<?> source = switch (target) {
            case "rabbit" -> RabbitAutoConfiguration.class;
            case "smtp" -> MailSenderAutoConfiguration.class;
            default -> throw new IllegalArgumentException("Destino de diagnóstico inválido");
        };
        // Solo autoconfiguración de la conexión elegida: sin aplicación, escaneo, JPA ni schedulers.
        var application = new SpringApplication(source);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    static int check(String target, Supplier<ConfigurableApplicationContext> contexts, PrintStream output) {
        if (!validTarget(target)) {
            output.println("Destino inválido: usa rabbit o smtp.");
            return 2;
        }
        try (var context = contexts.get()) {
            if (target.equals("rabbit")) {
                var factory = context.getBean(ConnectionFactory.class);
                try (var connection = factory.createConnection()) {
                    if (!connection.isOpen()) throw new IllegalStateException("Conexión AMQP cerrada");
                }
            } else {
                // Abre y cierra el transporte; autentica si SMTP auth está habilitado. No envía mensajes.
                context.getBean(JavaMailSenderImpl.class).testConnection();
            }
        } catch (Exception failure) {
            Throwable detail = failure;
            for (int depth = 0; depth < 10 && detail.getCause() != null; depth++) detail = detail.getCause();
            // No mostrar getMessage(), stacktrace, configuración o respuesta SMTP con datos privados.
            output.printf("ERROR %s: %s. Revisa conectividad, TLS y credenciales.\n",
                    target.equals("rabbit") ? "RabbitMQ" : "SMTP", detail.getClass().getSimpleName());
            return 1;
        }
        output.println(target.equals("rabbit")
                ? "OK RabbitMQ: conexión y autenticación al vhost completadas; conexión cerrada."
                : "OK SMTP: conexión comprobada con la autenticación configurada; transporte cerrado, sin enviar correo.");
        return 0;
    }

    private static boolean validTarget(String target) {
        return "rabbit".equals(target) || "smtp".equals(target);
    }
}
