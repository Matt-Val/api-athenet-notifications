package com.athenet.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import com.athenet.notifications.model.EmailDelivery;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {
    @Query("select d.id from EmailDelivery d where d.status = :status and d.nextAttemptAt <= :now order by d.id")
    List<Long> findDueIds(EmailDelivery.Status status, Instant now, Pageable page);
    @Query("""
            select d.id from EmailDelivery d
            where d.status = :status
              and (d.publicationStatus = :publicationStatus or d.publicationStatus is null)
              and coalesce(d.nextPublicationAt, d.nextAttemptAt) <= :now
            order by d.id
            """)
    List<Long> findPublicationDueIds(EmailDelivery.Status status, EmailDelivery.PublicationStatus publicationStatus,
                                    Instant now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from EmailDelivery d where d.id = :id") Optional<EmailDelivery> findLockedById(Long id);
}
