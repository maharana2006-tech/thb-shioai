package com.multiship.backend.repository;

import com.multiship.backend.model.BulkLabelJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BulkLabelJobRepository extends JpaRepository<BulkLabelJob, Long> {

    List<BulkLabelJob> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    /**
     * Used by the startup housekeeper (see
     * {@link com.multiship.backend.service.BulkLabelServiceImpl#reapStaleRunningJobs})
     * to find jobs left behind by a JVM crash: status=RUNNING and
     * startedAt older than the given cutoff. Ordered by id so the
     * housekeeper's log lines are stable.
     */
    List<BulkLabelJob> findByStatusAndStartedAtBeforeOrderByIdAsc(String status, LocalDateTime cutoff);
}
