package com.athenet.notifications.support;

import static org.assertj.core.api.Assertions.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TestRabbitGuardTest {
    @Test void acceptsOnlyLocalBrokerWithExplicitTestVirtualHost() {
        assertThatCode(() -> TestRabbitGuard.validate("127.0.0.1", 5673, "notifications_rabbit_test")).doesNotThrowAnyException();
        assertThatCode(() -> TestRabbitGuard.validate("localhost", 5672, "/notifications_rabbit_test")).doesNotThrowAnyException();
        assertThatCode(() -> TestRabbitGuard.validate("::1", 5673, "notifications_rabbit_test")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("unsafeTargets")
    void rejectsSharedBrokerDefaultVirtualHostAndInvalidConfiguration(String host, int port, String vhost) {
        assertThatThrownBy(() -> TestRabbitGuard.validate(host, port, vhost)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> unsafeTargets() {
        return Stream.of(Arguments.of("98.91.71.40", 5672, "notifications_test"),
                Arguments.of("0.0.0.0", 5672, "notifications_test"),
                Arguments.of("192.168.1.10", 5672, "notifications_test"),
                Arguments.of(null, 5672, "notifications_test"),
                Arguments.of("localhost", 0, "notifications_test"),
                Arguments.of("localhost", 65536, "notifications_test"),
                Arguments.of("localhost", 5672, "/"),
                Arguments.of("localhost", 5672, "notifications"),
                Arguments.of("localhost", 5672, ""),
                Arguments.of("localhost", 5672, null));
    }
}
