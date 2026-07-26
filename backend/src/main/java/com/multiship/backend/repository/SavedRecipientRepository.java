package com.multiship.backend.repository;

import com.multiship.backend.model.SavedRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, Long> {

    /**
     * Deduplication lookup — matches an existing row with the same
     * owner + hash. Used before insert to short-circuit duplicates.
     * Platform-wide rows (ownerCustomerNo=null) match against
     * ownerCustomerNo IS NULL.
     */
    @Query("""
        SELECT r FROM SavedRecipient r
        WHERE r.dedupHash = :hash
          AND ((:ownerCustomerNo IS NULL AND r.ownerCustomerNo IS NULL)
               OR (:ownerCustomerNo IS NOT NULL AND r.ownerCustomerNo = :ownerCustomerNo))
    """)
    Optional<SavedRecipient> findExisting(
            @Param("hash") String hash,
            @Param("ownerCustomerNo") String ownerCustomerNo);

    /**
     * Fuzzy search — matches {@code q} as a substring against name,
     * company, city, or postalCode (all case-insensitive). Ordered by
     * updatedAt DESC so recently-touched entries surface first.
     *
     * <p>Owner scoping: when {@code ownerCustomerNo} is supplied, matches
     * both owner-scoped AND platform-wide entries (so a 3PL sees their
     * own book plus the platform's shared entries). When null, matches
     * only platform-wide entries.
     */
    @Query("""
        SELECT r FROM SavedRecipient r
        WHERE ((:ownerCustomerNo IS NULL AND r.ownerCustomerNo IS NULL)
               OR (:ownerCustomerNo IS NOT NULL
                   AND (r.ownerCustomerNo = :ownerCustomerNo OR r.ownerCustomerNo IS NULL)))
          AND (:q IS NULL OR :q = ''
               OR LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(r.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(r.city) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(r.postalCode) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY r.updatedAt DESC
    """)
    List<SavedRecipient> search(
            @Param("ownerCustomerNo") String ownerCustomerNo,
            @Param("q") String q);
}
