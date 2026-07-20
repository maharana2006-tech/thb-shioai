package com.multiship.backend.repository;

import com.multiship.backend.model.OrderCustoms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderCustomsRepository extends JpaRepository<OrderCustoms, Long> {

    Optional<OrderCustoms> findByOrderNoIgnoreCase(String orderNo);

    boolean existsByOrderNoIgnoreCase(String orderNo);
}
