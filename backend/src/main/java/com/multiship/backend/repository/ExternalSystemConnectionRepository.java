package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalSystemConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalSystemConnectionRepository extends JpaRepository<ExternalSystemConnection, Long> {

    Optional<ExternalSystemConnection> findByName(String name);

    List<ExternalSystemConnection> findBySystemType(String systemType);

    List<ExternalSystemConnection> findByActiveTrue();
}
