package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CarrierConfigRepository extends JpaRepository<CarrierConfig, Long> {

    Optional<CarrierConfig> findByUserUsernameAndCarrierCode(String username, String carrierCode);

    Optional<CarrierConfig> findFirstByUserUsernameAndCarrierCodeAndTenantId(String username, String carrierCode, String tenantId);

    Optional<CarrierConfig> findFirstByUserUsernameAndCarrierCodeAndTenantIdIsNull(String username, String carrierCode);

    List<CarrierConfig> findByTenantId(String tenantId);

    Optional<CarrierConfig> findByTenantIdAndIsDefaultTrue(String tenantId);

    List<CarrierConfig> findByTenantIdAndIsActiveTrue(String tenantId);

    Optional<CarrierConfig> findByTenantIdAndCarrierCode(String tenantId, String carrierCode);

    boolean existsByTenantIdAndIsDefaultTrue(String tenantId);

    Optional<CarrierConfig> findFirstByTenantIdAndCarrierCodeAndIsDefaultTrue(String tenantId, String carrierCode);

    Optional<CarrierConfig> findFirstByTenantIdAndCarrierCodeOrderByUpdatedAtDesc(String tenantId, String carrierCode);

    Optional<CarrierConfig> findFirstByTenantIdAndIsDefaultTrueOrderByUpdatedAtDesc(String tenantId);

    Optional<CarrierConfig> findFirstByTenantIdOrderByUpdatedAtDesc(String tenantId);

    List<CarrierConfig> findByTenantIdOrderByIsDefaultDescUpdatedAtDesc(String tenantId);

    List<CarrierConfig> findByTenantIdIsNotNullOrderByTenantIdAscIsDefaultDescUpdatedAtDesc();

    Optional<CarrierConfig> findFirstByUserUsernameOrderByUpdatedAtDesc(String username);

    void deleteByUserUsernameAndCarrierCode(String username, String carrierCode);
}
