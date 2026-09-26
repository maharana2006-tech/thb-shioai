package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalSystemConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalSystemConnectionRepository extends JpaRepository<ExternalSystemConnection, Long> {

    /** V91 — returns the row currently active for this name (PROD unless the
     *  PROD row's use_dev toggle is TRUE, in which case DEV). Implemented in
     *  the service layer via findAllByName; kept here for backwards-compat. */
    Optional<ExternalSystemConnection> findByName(String name);

    /** V91 — env-scoped lookup for admin CRUD (edit the PROD row, edit the DEV row). */
    Optional<ExternalSystemConnection> findByNameAndEnvironment(String name, String environment);

    /** V91 — both env rows for a given name (0, 1, or 2 rows). */
    List<ExternalSystemConnection> findAllByName(String name);

    List<ExternalSystemConnection> findBySystemType(String systemType);

    List<ExternalSystemConnection> findByActiveTrue();
}
