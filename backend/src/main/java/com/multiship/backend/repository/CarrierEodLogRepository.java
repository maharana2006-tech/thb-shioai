package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierEodLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarrierEodLogRepository extends JpaRepository<CarrierEodLog, Long> {

    /** Most recent events first — feeds the Settings page's activity table. */
    List<CarrierEodLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
