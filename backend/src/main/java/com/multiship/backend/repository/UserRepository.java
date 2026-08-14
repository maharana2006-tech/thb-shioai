package com.multiship.backend.repository;

import com.multiship.backend.model.User;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Boolean existsByUsername(String username);
    Boolean existsByEmail(String email);

    /** Sprint 50 Tier 0.5 PR D — click-through email verification lookup. */
    Optional<User> findByEmailVerifyToken(String token);

    /** Sprint 51 BS-M4 — forgot-password lookup. Case-insensitive so the
     *  caller can't be defeated by an email with different casing. */
    Optional<User> findByEmailIgnoreCase(String email);

    @Modifying
    @Query(value = """
        update users
           set preferred_carrier = left(btrim(cast(:preferredCarrier as text)), 50),
               carrier_client_id = left(btrim(cast(:carrierClientId as text)), 255),
               carrier_client_secret = left(btrim(cast(:carrierClientSecret as text)), 255),
               carrier_account_number = left(btrim(cast(:carrierAccountNumber as text)), 100),
               carrier_access_token = cast(:carrierAccessToken as text),
               carrier_token_expires_at = :carrierTokenExpiresAt,
               carrier_connected = :carrierConnected,
               carrier_connected_at = :carrierConnectedAt,
               carrier_environment = left(btrim(cast(:carrierEnvironment as text)), 20)
         where id = :id
    """, nativeQuery = true)
    int updateCarrierDetails(
            @Param("id") Long id,
            @Param("preferredCarrier") String preferredCarrier,
            @Param("carrierClientId") String carrierClientId,
            @Param("carrierClientSecret") String carrierClientSecret,
            @Param("carrierAccountNumber") String carrierAccountNumber,
            @Param("carrierAccessToken") String carrierAccessToken,
            @Param("carrierTokenExpiresAt") LocalDateTime carrierTokenExpiresAt,
            @Param("carrierConnected") Boolean carrierConnected,
            @Param("carrierConnectedAt") LocalDateTime carrierConnectedAt,
            @Param("carrierEnvironment") String carrierEnvironment
    );
}
