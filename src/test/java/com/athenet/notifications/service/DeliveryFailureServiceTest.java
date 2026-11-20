package com.athenet.notifications.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryFailureServiceTest {
    private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
    private final DeliveryFailureService service = new DeliveryFailureService(deliveries);

    @Test void exhaustionMarksFailedWithoutChangingAttemptsOrLastSmtpFailure() {
        var delivery = new EmailDelivery(); delivery.setAttempts(3); delivery.setLastError("MailSendException");
        when(deliveries.findLockedById(42L)).thenReturn(Optional.of(delivery));
        service.exhausted(42L);
        assertThat(delivery.getStatus()).isEqualTo(EmailDelivery.Status.FAILED);
        assertThat(delivery.getAttempts()).isEqualTo(3);
        assertThat(delivery.getLastError()).isEqualTo("MailSendException");
    }

    @ParameterizedTest @EnumSource(value = EmailDelivery.Status.class, names = {"SENT", "SKIPPED", "FAILED"})
    void recoveryCannotOverwriteAFinalizedDelivery(EmailDelivery.Status status) {
        var delivery = new EmailDelivery(); delivery.setStatus(status);
        when(deliveries.findLockedById(42L)).thenReturn(Optional.of(delivery));
        service.exhausted(42L);
        assertThat(delivery.getStatus()).isEqualTo(status);
    }
}
