package com.multiship.backend.repository;

import com.multiship.backend.model.Printer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PrinterRepository extends JpaRepository<Printer, Long> {
    List<Printer> findAllByOrderByNameAscIdAsc();
}
