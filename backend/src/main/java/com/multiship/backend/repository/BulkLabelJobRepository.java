package com.multiship.backend.repository;

import com.multiship.backend.model.BulkLabelJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkLabelJobRepository extends JpaRepository<BulkLabelJob, Long> {

    List<BulkLabelJob> findByRequestedByOrderByCreatedAtDesc(String requestedBy);
}
