package com.multiship.backend.repository;

import com.multiship.backend.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** Fast lookup used on every authenticated external request. */
    Optional<ApiKey> findByKeyPrefixAndActiveTrue(String keyPrefix);

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    List<ApiKey> findAllByOrderByCreatedAtDesc();

    List<ApiKey> findByClientCodeIgnoreCaseOrderByCreatedAtDesc(String clientCode);
}
