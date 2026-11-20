package com.athenet.notifications.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class EventRabbitConfigTest {
    @Test void noEventQueuesAreDeclaredWhenFeatureIsDisabled() {
        try (var context = new AnnotationConfigApplicationContext(EventRabbitConfig.class)) {
            assertThat(context.getBeansOfType(Declarables.class)).isEmpty();
        }
    }

    @Test void enabledFeatureHasOwnQueueDlqAndThreeExactBindings() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    java.util.Map.of("app.newsletter.events-enabled", "true")));
            context.register(EventRabbitConfig.class); context.refresh();
            var declarations = context.getBean(Declarables.class);
            var queues = declarations.getDeclarablesByType(Queue.class);
            assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder("notificaciones.eventos", "notificaciones.eventos.dlq");
            assertThat(queues).allSatisfy(queue -> assertThat(queue.isDurable()).isTrue());
            assertThat(queues.stream().filter(queue -> queue.getName().equals(EventRabbitConfig.QUEUE)).findFirst().orElseThrow().getArguments())
                    .containsEntry("x-dead-letter-exchange", RabbitConfig.DLX).containsEntry("x-dead-letter-routing-key", EventRabbitConfig.DLQ);
            assertThat(declarations.getDeclarablesByType(Binding.class)).extracting(Binding::getRoutingKey)
                    .containsExactlyInAnyOrder("evento.publicado", "evento.modificado", "evento.cancelado", "notificaciones.eventos.dlq");
        }
    }
}
