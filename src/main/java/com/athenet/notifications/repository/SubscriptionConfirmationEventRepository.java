package com.athenet.notifications.repository;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import com.athenet.notifications.model.SubscriptionConfirmationEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SubscriptionConfirmationEventRepository extends JpaRepository<SubscriptionConfirmationEvent, UUID> {
    @Query("""
            select e.id from SubscriptionConfirmationEvent e
            where e.status = :status and coalesce(e.nextPublicationAt, e.occurredAt) <= :now
            order by e.occurredAt, e.id
            """)
    List<UUID> findPublicationDueIds(SubscriptionConfirmationEvent.Status status, Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SubscriptionConfirmationEvent e where e.id = :id")
    Optional<SubscriptionConfirmationEvent> findLockedById(UUID id);
}
