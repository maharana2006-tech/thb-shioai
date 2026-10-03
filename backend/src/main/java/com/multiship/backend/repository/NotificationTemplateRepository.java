package com.multiship.backend.repository;

import com.multiship.backend.model.NotificationTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository
        extends JpaRepository<NotificationTemplateEntity, String> {
}
