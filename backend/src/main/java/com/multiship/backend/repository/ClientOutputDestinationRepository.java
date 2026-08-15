package com.multiship.backend.repository;

import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.service.output.DocType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Sprint 52 — CRUD + dispatch-time lookup for
 * {@link ClientOutputDestination}. The hot path is
 * {@link #findByClientCodeAndDocTypeAndActiveTrueOrderById(String, DocType)}
 * which returns 0-3 rows per client per doc-type and is called once per
 * generated document, so it's cache-friendly. Callers order deterministically
 * by id so re-running a job hits the same destinations in the same sequence.
 */
public interface ClientOutputDestinationRepository
        extends JpaRepository<ClientOutputDestination, Long> {

    /**
     * Dispatch-time lookup. Ordered by id so repeated dispatches
     * traverse destinations in a stable sequence — helps debugging
     * "which one failed?" and lets a customer say "the second one
     * is my SFTP drop".
     */
    List<ClientOutputDestination> findByClientCodeAndDocTypeAndActiveTrueOrderById(
            String clientCode, DocType docType);

    /** Admin-page filter: every row for one client (both doc-types, active + inactive). */
    List<ClientOutputDestination> findByClientCodeOrderByDocTypeAscIdAsc(String clientCode);

    /** Admin-page filter: every row across the platform. */
    List<ClientOutputDestination> findAllByOrderByClientCodeAscDocTypeAscIdAsc();
}
