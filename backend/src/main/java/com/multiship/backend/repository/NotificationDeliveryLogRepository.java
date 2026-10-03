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
     *
     * <p>{@code CAST(:param AS string)} is a Hibernate 6 + pgjdbc workaround:
     * without the cast, a null String parameter is bound as {@code bytea NULL}
     * and Postgres errors on {@code LOWER(bytea)} — see
     * [[feedback-hibernate-null-string-bytea]].
     */
    @Query("SELECT l FROM NotificationDeliveryLogEntity l "
            + "WHERE (CAST(:templateKey AS string) IS NULL OR l.templateKey = :templateKey) "
            + "AND (CAST(:status AS string) IS NULL OR l.status = :status) "
            + "AND (CAST(:recipientLike AS string) IS NULL OR LOWER(l.recipient) LIKE LOWER(CAST(:recipientLike AS string)))")
    Page<NotificationDeliveryLogEntity> search(
            @Param("templateKey") String templateKey,
            @Param("status") String status,
            @Param("recipientLike") String recipientLike,
            Pageable pageable);
}
