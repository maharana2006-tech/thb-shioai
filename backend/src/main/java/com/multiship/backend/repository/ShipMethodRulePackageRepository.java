package com.multiship.backend.repository;

import com.multiship.backend.model.ShipMethodRulePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipMethodRulePackageRepository extends JpaRepository<ShipMethodRulePackage, Long> {

    List<ShipMethodRulePackage> findByRuleIdOrderByPresetIdAsc(Long ruleId);

    /** Sprint 55 audit #297 — cascade preview support. */
    long countByRuleId(Long ruleId);

    /** Bulk cascade helper — used when a rule is deleted or its package set
     *  is replaced atomically on save. */
    @Modifying
    @Query("delete from ShipMethodRulePackage p where p.ruleId = :ruleId")
    void deleteAllByRuleId(@Param("ruleId") Long ruleId);
}
