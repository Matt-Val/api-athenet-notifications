package com.athenet.notifications;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Comprueba RDS con SELECT; no inicia la aplicación web, no crea tablas ni publica mensajes. */
@EnabledIfEnvironmentVariable(named = "NOTIFICATIONS_RDS_CHECK", matches = "true")
class RdsConnectionTest {
    @Test void serviceUserConnectsToOwnDatabaseWithSchemaPermissions() throws Exception {
        // Carga application.properties sin auto-configuración, JPA, servidor web ni listeners.
        try (var context = new AnnotationConfigApplicationContext()) {
            new ConfigDataApplicationContextInitializer().initialize(context);
            context.refresh();
            var environment = context.getEnvironment();
            checkConnection(environment.getRequiredProperty("spring.datasource.url"),
                    environment.getRequiredProperty("spring.datasource.username"),
                    environment.getRequiredProperty("spring.datasource.password"));
        }
    }

    private void checkConnection(String url, String username, String password) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (var result = statement.executeQuery("""
                        select current_database(), current_user,
                               has_database_privilege(current_user, current_database(), 'CONNECT'),
                               has_schema_privilege(current_user, 'public', 'USAGE'),
                               has_schema_privilege(current_user, 'public', 'CREATE')
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("notifications_db");
                    assertThat(result.getString(2)).isEqualTo("notifications_user");
                    assertThat(result.getBoolean(3)).isTrue();
                    assertThat(result.getBoolean(4)).isTrue();
                    assertThat(result.getBoolean(5)).isTrue();
                }
            } finally { connection.rollback(); }
        }
    }

}
