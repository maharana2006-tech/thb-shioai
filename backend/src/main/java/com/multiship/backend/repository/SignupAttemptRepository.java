package com.multiship.backend.repository;

import com.multiship.backend.model.SignupAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR A — moving-window counters for the signup rate
 * limiter (PR D). {@code countBy...After} queries hit the indexes defined
 * on {@link SignupAttempt} so the window scan stays O(log n) as the table
 * grows.
 */
@Repository
public interface SignupAttemptRepository extends JpaRepository<SignupAttempt, Long> {

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime after);

    long countByIpAndCreatedAtAfter(String ip, LocalDateTime after);
}
