package com.multiship.backend.repository;

import com.multiship.backend.model.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    List<OrderLine> findByOrderNo(Integer orderNo);

    List<OrderLine> findByTenantId(String tenantId);

    @Query("SELECT ol FROM OrderLine ol WHERE ol.orderNo = :orderNo ORDER BY ol.lineNo ASC")
    List<OrderLine> findOrderLinesByOrderNo(@Param("orderNo") Integer orderNo);
}
