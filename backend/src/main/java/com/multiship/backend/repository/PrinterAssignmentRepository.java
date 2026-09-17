package com.multiship.backend.repository;

import com.multiship.backend.model.PrinterAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrinterAssignmentRepository extends JpaRepository<PrinterAssignment, Long> {

    List<PrinterAssignment> findAllByOrderByClientCodeAscDocTypeAsc();

    Optional<PrinterAssignment> findFirstByClientCodeIgnoreCaseAndDocType(String clientCode, String docType);

    Optional<PrinterAssignment> findFirstByClientCodeIsNullAndDocType(String docType);
}
