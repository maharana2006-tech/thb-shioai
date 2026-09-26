package com.multiship.backend.repository;

import com.multiship.backend.model.TenantSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantSettingRepository extends JpaRepository<TenantSetting, Long> {

    Optional<TenantSetting> findByTenantCodeAndSettingKey(String tenantCode, String settingKey);

    List<TenantSetting> findByTenantCode(String tenantCode);

    /** Reverse lookup — every tenant whose {@code settingKey} points at
     *  {@code settingValue}. Used by ExternalSystemsAdminController to
     *  list clients routed to a given writeback connection. */
    List<TenantSetting> findBySettingKeyAndSettingValue(String settingKey, String settingValue);
}
