package com.multiship.backend.repository;

import com.multiship.backend.model.OrderRawCodes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRawCodesRepository extends JpaRepository<OrderRawCodes, Integer> {
}
