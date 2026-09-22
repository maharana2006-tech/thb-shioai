package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalSystemSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalSystemSecretRepository extends JpaRepository<ExternalSystemSecret, Long> {

    Optional<ExternalSystemSecret> findByConnectionIdAndSecretKey(Long connectionId, String secretKey);

    List<ExternalSystemSecret> findByConnectionId(Long connectionId);
}
