package com.multiship.backend.repository;

import com.multiship.backend.model.DtcSchedulerRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DtcSchedulerRunRepository extends JpaRepository<DtcSchedulerRun, Long> {

    List<DtcSchedulerRun> findByJobKeyOrderByStartedAtDesc(String jobKey, Pageable page);

    @Transactional
    @Modifying
    @Query("DELETE FROM DtcSchedulerRun r WHERE r.startedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
