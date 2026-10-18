package com.multiship.backend.repository;

import com.multiship.backend.model.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Sprint 50 Tier 0.5 PR A — invite lookup by token for the accept flow (PR D).
 */
@Repository
public interface UserInviteRepository extends JpaRepository<UserInvite, Long> {

    Optional<UserInvite> findByToken(String token);

    /** BUG-084 — look for a still-pending invite (not consumed, not expired)
     *  for an email so a duplicate mint can refuse instead of issuing a
     *  second live token. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT i FROM UserInvite i WHERE LOWER(i.email) = LOWER(:email)"
        + " AND i.consumedAt IS NULL AND i.expiresAt > CURRENT_TIMESTAMP"
        + " ORDER BY i.id DESC")
    java.util.List<UserInvite> findLivePendingByEmail(@org.springframework.data.repository.query.Param("email") String email);
}
