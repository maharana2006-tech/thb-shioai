package com.multiship.backend.repository;

import com.multiship.backend.model.RateShopHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;


@Repository
public interface RateShopHistoryRepository extends JpaRepository<RateShopHistory, Long>,
        JpaSpecificationExecutor<RateShopHistory> {

}
