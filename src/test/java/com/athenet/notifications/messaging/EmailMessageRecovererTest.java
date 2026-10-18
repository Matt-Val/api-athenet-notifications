package com.athenet.notifications.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.athenet.notifications.exception.EmailDeliveryRetryException;
import com.athenet.notifications.service.DeliveryFailureService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;

class EmailMessageRecovererTest {
    private final DeliveryFailureService failures = mock(DeliveryFailureService.class);
    private final EmailMessageRecoverer recoverer = new EmailMessageRecoverer(failures);
    private final Message message = new Message(new byte[0], new MessageProperties());

    @Test void exhaustionIsRecordedBeforeRejectingForDeadLetter() {
        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException(new EmailDeliveryRetryException(42L))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(failures).exhausted(42L);
    }

    @Test void databaseOutageStillRejectsInsteadOfAcknowledging() {
        doThrow(new IllegalStateException("private details")).when(failures).exhausted(42L);
        assertThatThrownBy(() -> recoverer.recover(message, new EmailDeliveryRetryException(42L)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class).hasMessageNotContaining("private details");
    }

    @Test void invalidMessageDoesNotTouchAnyDelivery() {
        assertThatThrownBy(() -> recoverer.recover(message, new AmqpRejectAndDontRequeueException("invalid")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(failures);
    }
}
