package com.multiship.backend.repository;

import com.multiship.backend.model.ShipMethodRuleWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipMethodRuleWarehouseRepository extends JpaRepository<ShipMethodRuleWarehouse, Long> {

    List<ShipMethodRuleWarehouse> findByRuleIdOrderByWarehouseIdAsc(Long ruleId);

    /** Sprint 55 audit #297 — cascade preview support. */
    long countByRuleId(Long ruleId);

    /** Bulk lookup — used by rule resolution to avoid N+1 when scoring
     *  multiple candidate rules for one order ship-method code. */
    List<ShipMethodRuleWarehouse> findByRuleIdIn(java.util.Collection<Long> ruleIds);

    /** Bulk cascade helper — used when a rule is deleted or its warehouse set
     *  is replaced atomically on save. */
    @Modifying
    @Query("delete from ShipMethodRuleWarehouse w where w.ruleId = :ruleId")
    void deleteAllByRuleId(@Param("ruleId") Long ruleId);
}
