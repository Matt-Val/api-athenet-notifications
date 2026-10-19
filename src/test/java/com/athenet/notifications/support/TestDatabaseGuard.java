package com.athenet.notifications.support;

import java.net.URI;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Se ejecuta antes de crear el DataSource o inicializar Hibernate con create-drop. */
public class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validate(context.getEnvironment().getRequiredProperty("spring.datasource.url"));
    }

    public static void validate(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            var uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String path = uri.getPath();
            if (uri.getHost() == null || path == null || !path.matches("/[A-Za-z0-9_]+_test")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            // No incluir la URL: podría contener credenciales en sus parámetros.
            throw new IllegalArgumentException("Las pruebas create-drop requieren una BD PostgreSQL exclusiva cuyo nombre termine en _test.");
        }
    }
}
