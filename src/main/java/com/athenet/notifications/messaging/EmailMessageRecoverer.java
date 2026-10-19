package com.athenet.notifications.messaging;

import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.service.DeliveryFailureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

@Component
public class EmailMessageRecoverer implements MessageRecoverer {
    private static final Logger log = LoggerFactory.getLogger(EmailMessageRecoverer.class);
    private final DeliveryFailureService failures;

    public EmailMessageRecoverer(DeliveryFailureService failures) {
        super();
        this.failures = failures;
    }

    @Override public void recover(Message message, Throwable cause) {
        var retry = EmailDeliveryRetryException.findIn(cause);
        if (retry != null) {
            try { failures.exhausted(retry.getDeliveryId()); }
            catch (RuntimeException ex) {
                log.warn("No se pudo registrar agotamiento de entrega {} (tipo {})", retry.getDeliveryId(), ex.getClass().getSimpleName());
            }
        }
        // La transacción del registro anterior termina antes de rechazar. El broker decide el DLX.
        throw new AmqpRejectAndDontRequeueException("No se pudo completar la tarea de correo; revisar la cola de errores.");
    }
}
