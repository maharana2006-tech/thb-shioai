package com.multiship.backend.repository;

import com.multiship.backend.model.MailProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MailProviderRepository extends JpaRepository<MailProviderEntity, Long> {

    Optional<MailProviderEntity> findFirstByIsActiveTrue();
}
