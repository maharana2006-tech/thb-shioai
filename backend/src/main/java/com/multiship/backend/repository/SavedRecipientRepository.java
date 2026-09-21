package com.multiship.backend.repository;

import com.multiship.backend.model.SavedRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<SavedRecipient> {

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


}
