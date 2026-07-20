package com.multiship.backend.repository;

import com.multiship.backend.model.OrderCarrierDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderCarrierDetailsRepository extends JpaRepository<OrderCarrierDetails, Long> {

    Optional<OrderCarrierDetails> findByOrderNo(Integer orderNo);

    List<OrderCarrierDetails> findByOrderNoIn(List<Integer> orderNos);
}
