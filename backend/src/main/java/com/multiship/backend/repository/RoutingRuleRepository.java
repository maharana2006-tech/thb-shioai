package com.multiship.backend.repository;

import com.multiship.backend.model.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    /** Rules for a client ordered by evaluation order (priority ASC, id ASC). */
    List<RoutingRule> findByClientCodeIgnoreCaseOrderByPriorityAscIdAsc(String clientCode);

    /** Active rules for the evaluator hot path. */
    List<RoutingRule> findByClientCodeIgnoreCaseAndActiveTrueOrderByPriorityAscIdAsc(String clientCode);

    /** Audit B7 (#353) — any OTHER rule at the same priority? Powers the
     *  post-save collision warning. {@code excludingId} filters out the
     *  rule we just saved (which obviously matches itself). Returns the
     *  first collider sorted by id so the warning cites a stable row. */
    Optional<RoutingRule> findFirstByClientCodeIgnoreCaseAndPriorityAndIdNotOrderByIdAsc(
            String clientCode, Integer priority, Long excludingId);
}
