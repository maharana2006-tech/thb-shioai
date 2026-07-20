package com.multiship.backend.repository;

import com.multiship.backend.model.ServicePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServicePackageRepository extends JpaRepository<ServicePackage, Long> {

    List<ServicePackage> findByServiceId(Long serviceId);

    void deleteByServiceId(Long serviceId);
}
