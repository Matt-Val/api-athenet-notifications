package com.athenet.notifications.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import com.athenet.notifications.model.EventNoticeState;

public interface EventNoticeStateRepository extends JpaRepository<EventNoticeState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EventNoticeState e where e.internalId = :internalId")
    Optional<EventNoticeState> findLockedByInternalId(String internalId);
}
