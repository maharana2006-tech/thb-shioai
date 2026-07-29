package com.multiship.backend.repository;

import com.multiship.backend.model.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByGroupIdOrderByIdAsc(Long groupId);

    List<Shipment> findByOrderNoOrderByIdAsc(Integer orderNo);

    List<Shipment> findByClientCodeIgnoreCaseOrderByCreatedAtDesc(String clientCode);
}
