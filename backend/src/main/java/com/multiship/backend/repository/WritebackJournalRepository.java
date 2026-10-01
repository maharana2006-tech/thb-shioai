package com.multiship.backend.repository;

import com.multiship.backend.model.WritebackJournalEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WritebackJournalRepository extends JpaRepository<WritebackJournalEntity, Long> {

    /**
     * Filtered page for the admin journal list. Null filters are ignored
     * so the same query handles every combination — CAST guards against
     * the pgjdbc bytea-null bug (see feedback_hibernate_null_string_bytea).
     */
    @Query("SELECT j FROM WritebackJournalEntity j "
            + "WHERE (CAST(:connectionName AS string) IS NULL OR j.connectionName = :connectionName) "
            + "AND (CAST(:status AS string) IS NULL OR j.status = :status) "
            + "AND (CAST(:mode AS string) IS NULL OR j.mode = :mode) "
            + "AND (:orderNo IS NULL OR j.orderNo = :orderNo)")
    Page<WritebackJournalEntity> search(
            @Param("connectionName") String connectionName,
            @Param("status") String status,
            @Param("mode") String mode,
            @Param("orderNo") Integer orderNo,
            Pageable pageable);
}
