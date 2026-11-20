package com.athenet.notifications.support;

import java.util.Set;

/** Esta prueba debe usar un broker local y un vhost exclusivo, nunca el compartido del equipo. */
public final class TestRabbitGuard {
    private TestRabbitGuard() { }

    public static void validate(String host, int port, String virtualHost) {
        if (host == null || !Set.of("127.0.0.1", "localhost", "::1").contains(host)
                || port < 1 || port > 65535 || virtualHost == null
                || !virtualHost.matches("/?[A-Za-z0-9][A-Za-z0-9_-]*_test")) {
            throw new IllegalArgumentException("La prueba RabbitMQ requiere loopback, puerto válido y vhost exclusivo terminado en _test.");
        }
    }
}
