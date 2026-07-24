package com.multiship.backend.repository;

import com.multiship.backend.model.ClientDestinationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientDestinationRuleRepository extends JpaRepository<ClientDestinationRule, Long> {

    List<ClientDestinationRule> findByClientCodeIgnoreCaseOrderByCountryAsc(String clientCode);

    /** Bulk clear before a replace-PUT rebuilds the row set. */
    @Modifying
    @Query("delete from ClientDestinationRule r where upper(r.clientCode) = upper(:clientCode)")
    void deleteAllByClientCode(@Param("clientCode") String clientCode);
}
