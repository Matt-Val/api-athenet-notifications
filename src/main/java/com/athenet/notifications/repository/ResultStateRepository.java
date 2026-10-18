package com.athenet.notifications.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import com.athenet.notifications.model.ResultState;

public interface ResultStateRepository extends JpaRepository<ResultState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ResultState r where r.matchId = :matchId")
    Optional<ResultState> findLockedByMatchId(Long matchId);
}
