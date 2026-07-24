package com.multiship.backend.repository;

import com.multiship.backend.model.ClientDestCountryMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientDestCountryMapRepository extends JpaRepository<ClientDestCountryMap, Long> {

    List<ClientDestCountryMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientDestCountryMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);
}
