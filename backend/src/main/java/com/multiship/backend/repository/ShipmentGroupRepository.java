package com.multiship.backend.repository;

import com.multiship.backend.model.ShipmentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipmentGroupRepository extends JpaRepository<ShipmentGroup, Long> {

    List<ShipmentGroup> findByClientCodeIgnoreCaseOrderByCreatedAtDesc(String clientCode);

    List<ShipmentGroup> findByOrderNo(Integer orderNo);
}
