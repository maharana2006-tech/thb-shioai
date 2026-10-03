package com.multiship.backend.repository;

import com.multiship.backend.model.MailConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailConfigRepository extends JpaRepository<MailConfigEntity, Long> {

    List<MailConfigEntity> findAllByProviderId(Long providerId);

    Optional<MailConfigEntity> findByProviderIdAndConfigKey(Long providerId, String configKey);

    void deleteAllByProviderId(Long providerId);
}
