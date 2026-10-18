package com.multiship.backend.repository;

import com.multiship.backend.model.ShippingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * BUG-017 — {@code findByCarrierIgnoreCaseAndServiceCodeIgnoreCase} must tolerate
 * 2+ rows (same carrier + service_code, different origin_country) without
 * blowing up with {@link org.springframework.dao.IncorrectResultSizeDataAccessException}
 * (which 500'd {@code GET /manifests/open} on busy close-out days).
 *
 * <p>The repo now delegates to a List-returning finder + picks the first (lowest id).
 * This exercises the default-method body in isolation; the DB-side query is covered
 * by Hibernate's own JPQL compilation at app startup.
 */
class ShippingServiceRepositoryTest {

    @Test
    void duplicate_origin_country_rows_do_not_throw_and_return_first_by_id() {
        ShippingServiceRepository repo = mock(ShippingServiceRepository.class, CALLS_REAL_METHODS);

        ShippingService us = ShippingService.builder()
                .id(10L).carrier("FEDEX").serviceCode("GROUND_HOME_DELIVERY")
                .name("US GHD").scope("D2C").originCountry("US").express(false).build();
        ShippingService ca = ShippingService.builder()
                .id(20L).carrier("FEDEX").serviceCode("GROUND_HOME_DELIVERY")
                .name("CA GHD").scope("D2C").originCountry("CA").express(false).build();
        when(repo.findAllByCarrierAndServiceCode(anyString(), anyString()))
                .thenReturn(List.of(us, ca));

        Optional<ShippingService> picked = repo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(
                "FEDEX", "GROUND_HOME_DELIVERY");

        assertTrue(picked.isPresent(), "duplicate-row case must not throw IncorrectResultSizeDataAccessException");
        assertEquals(10L, picked.get().getId(),
                "default method must pick the lowest-id sibling (query orders ASC by id)");
    }

    @Test
    void no_match_returns_empty() {
        ShippingServiceRepository repo = mock(ShippingServiceRepository.class, CALLS_REAL_METHODS);
        when(repo.findAllByCarrierAndServiceCode(anyString(), anyString())).thenReturn(List.of());

        assertTrue(repo.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase("x", "y").isEmpty());
    }
}
