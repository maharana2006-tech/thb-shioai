package com.multiship.backend.repository;

import com.multiship.backend.model.ClientCustomsProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientCustomsProfileRepository extends JpaRepository<ClientCustomsProfile, Long> {

    /**
     * Sprint 48 N+1 fix — JOIN FETCH the country links so bulk listing
     * doesn't LAZY-load one collection per profile (was N+1 after
     * switching countryLinks to LAZY). DISTINCT collapses cartesian rows.
     */
    @Query("select distinct p from ClientCustomsProfile p left join fetch p.countryLinks "
            + "where upper(p.clientCode) = upper(:clientCode)")
    List<ClientCustomsProfile> findByClientCodeIgnoreCase(@Param("clientCode") String clientCode);

    /**
     * The profile a client uses for a given destination country (country is
     * a member of its set). Sprint 48 N+1 fix — JOIN FETCH the country
     * links in the same SELECT so downstream reads of {@code getCountryLinks()}
     * don't trigger a second query after the switch to LAZY. The filter is
     * via EXISTS subquery so the fetched countryLinks collection retains
     * ALL links (not just the matching one), preserving legacy behaviour
     * for callers that iterate the full list.
     */
    @Query("select distinct p from ClientCustomsProfile p "
            + "left join fetch p.countryLinks "
            + "where upper(p.clientCode) = upper(:clientCode) "
            + "and exists (select 1 from CustomsProfileCountry c "
            + "            where c.profile = p and c.country = upper(:country))")
    Optional<ClientCustomsProfile> findByClientAndCountry(@Param("clientCode") String clientCode,
                                                          @Param("country") String country);
}
