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
}
