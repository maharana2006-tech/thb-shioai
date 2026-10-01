package com.multiship.backend.repository;

import com.multiship.backend.model.ShipVia;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShipViaRepository extends JpaRepository<ShipVia, Integer> {

    Optional<ShipVia> findByShipviaCdIgnoreCase(String shipviaCd);

    /** G-7.1 — carrier-choice-vs-shipx audit. Returns up to :pageable
     *  rows whose code starts with the given prefix, case-insensitively.
     *  Drives the "did you mean" hint on resolveShipVia misses. */
    @Query("""
        SELECT s FROM ShipVia s
         WHERE LOWER(s.shipviaCd) LIKE LOWER(CONCAT(:prefix, '%'))
         ORDER BY s.shipviaCd ASC
    """)
    List<ShipVia> findByCodePrefixIgnoreCase(@Param("prefix") String prefix, Pageable pageable);

    /** G-7.1 fallback — substring match when the prefix lookup is empty
     *  (operator typed middle characters). */
    @Query("""
        SELECT s FROM ShipVia s
         WHERE LOWER(s.shipviaCd) LIKE LOWER(CONCAT('%', :needle, '%'))
         ORDER BY s.shipviaCd ASC
    """)
    List<ShipVia> findByCodeSubstringIgnoreCase(@Param("needle") String needle, Pageable pageable);
}
