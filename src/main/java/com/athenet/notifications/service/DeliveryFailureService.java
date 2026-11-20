package com.athenet.notifications.service;

import com.athenet.notifications.model.EmailDelivery;
import com.athenet.notifications.repository.EmailDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryFailureService {
    private final EmailDeliveryRepository deliveries;

    public DeliveryFailureService(EmailDeliveryRepository deliveries) {
        super();
        this.deliveries = deliveries;
    }

    @Transactional
    public void exhausted(Long deliveryId) {
        var delivery = deliveries.findLockedById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != EmailDelivery.Status.PENDING) return;
        delivery.setStatus(EmailDelivery.Status.FAILED);
        if (delivery.getLastError() == null) delivery.setLastError("ConsumerRetriesExhausted");
    }
}
