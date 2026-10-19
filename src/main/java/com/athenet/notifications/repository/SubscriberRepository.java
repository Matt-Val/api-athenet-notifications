package com.athenet.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import com.athenet.notifications.model.Subscriber;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<Subscriber> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<Subscriber> findByConfirmationHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<Subscriber> findByUnsubscribeToken(String token);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscriber s where s.id = :id") Optional<Subscriber> findLockedById(Long id);
    List<Subscriber> findByStatusAndConfirmedAtLessThanEqual(Subscriber.Status status, Instant occurredAt);
}
