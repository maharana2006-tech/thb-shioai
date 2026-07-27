package com.multiship.backend.repository;

import com.multiship.backend.model.OrderCustomFieldValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderCustomFieldValueRepository extends JpaRepository<OrderCustomFieldValue, Long> {

    List<OrderCustomFieldValue> findByOrderNo(Integer orderNo);

    @Query("""
        SELECT v FROM OrderCustomFieldValue v
        WHERE v.orderNo = :orderNo AND v.fieldKey = :fieldKey
    """)
    Optional<OrderCustomFieldValue> findByOrderAndKey(
            @Param("orderNo") Integer orderNo,
            @Param("fieldKey") String fieldKey);

    void deleteByOrderNo(Integer orderNo);
}
