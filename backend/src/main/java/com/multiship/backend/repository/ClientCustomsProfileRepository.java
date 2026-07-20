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

    List<ClientCustomsProfile> findByClientCodeIgnoreCase(String clientCode);

    /** The profile a client uses for a given destination country (country is a member of its set). */
    @Query("select p from ClientCustomsProfile p join p.countryLinks c "
            + "where upper(p.clientCode) = upper(:clientCode) and c.country = upper(:country)")
    Optional<ClientCustomsProfile> findByClientAndCountry(@Param("clientCode") String clientCode,
                                                          @Param("country") String country);
}
