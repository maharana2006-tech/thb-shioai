package com.multiship.backend.repository;

import com.multiship.backend.model.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /** Newest imports first for the Data History list. */
    List<ImportBatch> findAllByOrderByIdDesc();
}
