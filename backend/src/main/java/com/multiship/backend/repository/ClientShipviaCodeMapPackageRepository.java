package com.multiship.backend.repository;

import com.multiship.backend.model.ClientShipviaCodeMapPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientShipviaCodeMapPackageRepository
        extends JpaRepository<ClientShipviaCodeMapPackage, ClientShipviaCodeMapPackage.PK> {

    List<ClientShipviaCodeMapPackage> findByMapId(Long mapId);

    @Modifying
    @Query("DELETE FROM ClientShipviaCodeMapPackage p WHERE p.mapId = :mapId")
    void deleteByMapId(@Param("mapId") Long mapId);
}
