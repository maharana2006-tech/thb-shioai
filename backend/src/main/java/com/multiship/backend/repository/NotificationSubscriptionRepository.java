package com.multiship.backend.repository;

import com.multiship.backend.model.NotificationSubscriptionEntity;
import com.multiship.backend.model.NotificationSubscriptionEntity.NotificationSubscriptionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationSubscriptionRepository
        extends JpaRepository<NotificationSubscriptionEntity, NotificationSubscriptionId> {

    List<NotificationSubscriptionEntity> findAllByIdUserId(Long userId);
}
