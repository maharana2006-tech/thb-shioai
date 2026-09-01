package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierPackageCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CarrierPackageCatalogRepository extends JpaRepository<CarrierPackageCatalog, Long> {

    List<CarrierPackageCatalog> findByCarrierCodeIgnoreCaseAndActiveTrueOrderBySortOrderAsc(String carrierCode);
}
