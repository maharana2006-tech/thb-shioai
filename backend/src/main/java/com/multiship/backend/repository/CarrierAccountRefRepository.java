package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierAccountRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CarrierAccountRefRepository extends JpaRepository<CarrierAccountRef, Long> {

    /** Complete, active platform accounts (no customer) for a carrier — newest first. */
    @Query("""
        SELECT r FROM CarrierAccountRef r
        WHERE UPPER(r.carrierCode) = UPPER(:carrier)
          AND (r.customerNo IS NULL OR TRIM(r.customerNo) = '')
          AND r.active = true
          AND r.clientId IS NOT NULL AND TRIM(r.clientId) <> ''
          AND r.clientSecret IS NOT NULL AND TRIM(r.clientSecret) <> ''
        ORDER BY r.updatedAt DESC
    """)
    List<CarrierAccountRef> findPlatformAccountsByCarrier(@Param("carrier") String carrier);

    Optional<CarrierAccountRef> findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(String accountNumber);

    Optional<CarrierAccountRef> findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(String accountNumber, String carrierCode);

    Optional<CarrierAccountRef> findFirstByIsDefaultTrueAndActiveTrue();

    List<CarrierAccountRef> findAllByOrderByIsDefaultDescUpdatedAtDesc();

    List<CarrierAccountRef> findByIsDefaultTrue();

    Optional<CarrierAccountRef> findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue(String customerNo);

    List<CarrierAccountRef> findByCustomerNoIgnoreCaseAndClientDefaultTrue(String customerNo);

    List<CarrierAccountRef> findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(String customerNo);
}
