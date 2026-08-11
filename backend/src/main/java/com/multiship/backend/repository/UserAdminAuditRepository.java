package com.multiship.backend.repository;

import com.multiship.backend.model.UserAdminAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAdminAuditRepository extends JpaRepository<UserAdminAudit, Long> {

    /** Recent-first audit entries for the admin activity feed. */
    List<UserAdminAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Audit trail for one specific user (shown on the row-detail drawer). */
    List<UserAdminAudit> findBySubjectUserIdOrderByCreatedAtDesc(Long subjectUserId);
}
