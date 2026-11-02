package com.multiship.backend.repository;

import com.multiship.backend.model.ShipmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ShipmentAttemptRepository extends JpaRepository<ShipmentAttempt, UUID> {

    /**
     * Claim the key: 1 when this call inserted the PURCHASING row, 0 when the
     * key already existed. The primary key makes two concurrent claims of one
     * key resolve to exactly one winner, with no Redis lock.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO shipment_attempt (attempt_key, order_no, state, fingerprint, actor, request_json, created_at, updated_at) "
            + "VALUES (:key, :orderNo, 'PURCHASING', :fingerprint, :actor, :request, :now, :now) "
            + "ON CONFLICT (attempt_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("key") UUID key, @Param("orderNo") Integer orderNo, @Param("fingerprint") String fingerprint,
              @Param("actor") String actor, @Param("request") String request, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(value = "UPDATE shipment_attempt SET state = :state, order_no = COALESCE(:orderNo, order_no), "
            + "response_json = :response, error_code = :errorCode, error_message = :errorMessage, updated_at = :now "
            + "WHERE attempt_key = :key", nativeQuery = true)
    int finish(@Param("key") UUID key, @Param("state") String state, @Param("orderNo") Integer orderNo,
               @Param("response") String response, @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query(value = "UPDATE label_batch SET last_attempt_key = :key WHERE order_no = :orderNo", nativeQuery = true)
    int stampOrder(@Param("orderNo") Integer orderNo, @Param("key") UUID key);

    /** Re-claim a FAILED attempt for a retry under the same key: 1 if this caller won. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE shipment_attempt SET state = 'PURCHASING', updated_at = :now "
            + "WHERE attempt_key = :key AND state = 'FAILED'", nativeQuery = true)
    int reclaim(@Param("key") UUID key, @Param("now") LocalDateTime now);

    List<ShipmentAttempt> findByStateAndUpdatedAtBefore(String state, LocalDateTime before);
}
