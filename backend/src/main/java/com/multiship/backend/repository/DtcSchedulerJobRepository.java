package com.multiship.backend.repository;

import com.multiship.backend.model.DtcSchedulerJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DtcSchedulerJobRepository extends JpaRepository<DtcSchedulerJob, Long> {

    Optional<DtcSchedulerJob> findByJobKey(String jobKey);

    List<DtcSchedulerJob> findAllByOrderBySortOrderAscIdAsc();

    /**
     * Cluster-safe "is anyone running this job?" — takes the lease only when
     * it is free or expired. 1 = acquired, 0 = another run holds it. The
     * expiry frees a lease left behind by a node that died mid-run.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE dtc_scheduler_job "
            + "SET lease_until = CURRENT_TIMESTAMP + make_interval(mins => :minutes), lease_owner = :owner "
            + "WHERE job_key = :jobKey AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)",
            nativeQuery = true)
    int tryAcquireLease(@Param("jobKey") String jobKey, @Param("owner") String owner, @Param("minutes") int minutes);

    @Transactional
    @Modifying
    @Query(value = "UPDATE dtc_scheduler_job SET lease_until = NULL, lease_owner = NULL "
            + "WHERE job_key = :jobKey AND lease_owner = :owner", nativeQuery = true)
    int releaseLease(@Param("jobKey") String jobKey, @Param("owner") String owner);

    @Transactional
    @Modifying
    @Query(value = "UPDATE dtc_scheduler_job SET last_run_at = :at, last_status = :status, "
            + "last_message = :message, last_duration_ms = :durationMs WHERE job_key = :jobKey",
            nativeQuery = true)
    int recordLastRun(@Param("jobKey") String jobKey, @Param("at") LocalDateTime at,
                      @Param("status") String status, @Param("message") String message,
                      @Param("durationMs") long durationMs);
}
