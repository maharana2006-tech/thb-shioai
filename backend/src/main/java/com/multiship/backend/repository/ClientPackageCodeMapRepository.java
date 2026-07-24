package com.multiship.backend.repository;

import com.multiship.backend.model.ClientPackageCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientPackageCodeMapRepository extends JpaRepository<ClientPackageCodeMap, Long> {

    List<ClientPackageCodeMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientPackageCodeMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);
}
