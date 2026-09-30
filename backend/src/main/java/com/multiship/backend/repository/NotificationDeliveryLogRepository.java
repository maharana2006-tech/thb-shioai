package com.multiship.backend.repository;

import com.multiship.backend.model.NotificationDeliveryLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryLogRepository
        extends JpaRepository<NotificationDeliveryLogEntity, Long> {

    /**
     * Filtered page. Null filters are ignored so the same query handles
     * "all" and every combination.
     */
    @Query("SELECT l FROM NotificationDeliveryLogEntity l "
            + "WHERE (:templateKey IS NULL OR l.templateKey = :templateKey) "
            + "AND (:status IS NULL OR l.status = :status) "
            + "AND (:recipientLike IS NULL OR LOWER(l.recipient) LIKE LOWER(:recipientLike))")
    Page<NotificationDeliveryLogEntity> search(
            @Param("templateKey") String templateKey,
            @Param("status") String status,
            @Param("recipientLike") String recipientLike,
            Pageable pageable);
}
