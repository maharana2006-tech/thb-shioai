package com.multiship.backend.repository;

import com.multiship.backend.model.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    /** Rules for a client ordered by evaluation order (priority ASC, id ASC). */
    List<RoutingRule> findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc(String clientCode);

    /** Active rules for the evaluator hot path. */
    List<RoutingRule> findByClientCodeIgnoreCaseAndActiveTrueOrderByPriorityAscIdAsc(String clientCode);
}
