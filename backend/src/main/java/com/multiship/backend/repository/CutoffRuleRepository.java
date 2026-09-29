package com.multiship.backend.repository;

import com.multiship.backend.model.CutoffRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CutoffRuleRepository extends JpaRepository<CutoffRule, Long> {

    List<CutoffRule> findByActiveTrue();
}
