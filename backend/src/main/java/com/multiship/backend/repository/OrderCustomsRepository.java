package com.multiship.backend.repository;

import com.multiship.backend.model.OrderCustoms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderCustomsRepository extends JpaRepository<OrderCustoms, Long> {

    Optional<OrderCustoms> findByOrderNoIgnoreCase(String orderNo);

    /** Batched existence check for the Documents table — which of these orders
     *  have customs data (⇒ a commercial invoice can be rendered). */
    java.util.List<OrderCustoms> findByOrderNoIn(java.util.Collection<String> orderNos);

    boolean existsByOrderNoIgnoreCase(String orderNo);
}
