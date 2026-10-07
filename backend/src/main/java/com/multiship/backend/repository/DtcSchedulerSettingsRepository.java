package com.multiship.backend.repository;

import com.multiship.backend.model.DtcSchedulerSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DtcSchedulerSettingsRepository extends JpaRepository<DtcSchedulerSettings, Short> {
}
