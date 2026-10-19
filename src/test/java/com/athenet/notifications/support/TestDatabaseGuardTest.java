package com.athenet.notifications.support;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TestDatabaseGuardTest {
    @Test void allowsOnlyDedicatedTestDatabaseIncludingConnectionParameters() {
        assertThatCode(() -> TestDatabaseGuard.validate(
                "jdbc:postgresql://localhost:5434/notifications_test?sslmode=require")).doesNotThrowAnyException();
    }

    @Test void rejectsServiceDatabaseAndOtherAthenetDatabases() {
        for (String database : new String[]{"notifications_db", "competition_db", "events_db", "postgres"}) {
            assertThatThrownBy(() -> TestDatabaseGuard.validate("jdbc:postgresql://localhost:5432/" + database))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsMalformedUrlsWithoutRevealingCredentials() {
        for (String url : new String[]{"jdbc:postgresql://localhost:5432", "jdbc:h2:mem:test", "invalid", null,
                "jdbc:postgresql://localhost:5432/notifications_db?password=private-value"}) {
            assertThatThrownBy(() -> TestDatabaseGuard.validate(url)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-value");
        }
    }
}
