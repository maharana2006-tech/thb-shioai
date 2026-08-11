package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientBillingMarkup;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientAllowedServiceDestinationRepository;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientAllowedServiceWarehouseRepository;
import com.multiship.backend.repository.ClientBillingMarkupRepository;
import com.multiship.backend.repository.ClientDestinationRuleRepository;
import com.multiship.backend.repository.ClientShippingPolicyRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 1 finding #11 regression guard — {@code applyMarkup} used
 * to silently return a 0% markup when a named client had no configured
 * markup row, causing under-billing. It now throws
 * {@link ErrorCode#MARKUP_REQUIRED_FOR_CLIENT}. Manual / ad-hoc shipments
 * (blank clientCode) legitimately have no markup owner and must still
 * pass through unchanged.
 */
class ShipmentResolutionServiceImplApplyMarkupTest {

    private ClientBillingMarkupRepository markupRepository;
    private ShipmentResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        markupRepository = mock(ClientBillingMarkupRepository.class);
        service = new ShipmentResolutionServiceImpl(
                mock(WarehouseRepository.class),
                mock(ClientWarehouseRepository.class),
                mock(ClientAllowedServiceRepository.class),
                mock(ClientAllowedServiceDestinationRepository.class),
                mock(ClientAllowedServiceWarehouseRepository.class),
                mock(ClientAllowedPackageRepository.class),
                mock(ClientDestinationRuleRepository.class),
                mock(ClientShippingPolicyRepository.class),
                markupRepository,
                mock(PackagePresetRepository.class));
    }

    @Test
    void applyMarkup_throwsWhenClientNamedWithoutMarkupRow() {
        when(markupRepository.findByClientCodeIgnoreCase(anyString())).thenReturn(Optional.empty());

        ShipmentResolutionException ex = assertThrows(ShipmentResolutionException.class,
                () -> service.applyMarkup("ACME", new BigDecimal("12.50"), "USD"));

        assertEquals(ErrorCode.MARKUP_REQUIRED_FOR_CLIENT, ex.getErrorCode());
        assertNotNull(ex.getMessage());
    }

    @Test
    void applyMarkup_passesThroughForBlankClientCode() {
        // No stubbing needed — normalize("") returns "" and the impl calls
        // markupRepository regardless, so keep the default empty answer.
        when(markupRepository.findByClientCodeIgnoreCase(anyString())).thenReturn(Optional.empty());

        // Null clientCode → normalize returns "" → falls through to zero-markup
        // pass-through path, must NOT throw.
        MarkupApplied nullResult = service.applyMarkup(null, new BigDecimal("12.50"), "USD");
        assertEquals(0, nullResult.value().compareTo(BigDecimal.ZERO));
        assertEquals(ClientBillingMarkup.KIND_PERCENT, nullResult.kind());
        assertEquals("USD", nullResult.currency());
        assertEquals(0, nullResult.billable().compareTo(new BigDecimal("12.5000")));

        // Blank clientCode → same pass-through.
        MarkupApplied blankResult = service.applyMarkup("", new BigDecimal("12.50"), "USD");
        assertEquals(0, blankResult.value().compareTo(BigDecimal.ZERO));
        assertEquals(0, blankResult.billable().compareTo(new BigDecimal("12.5000")));
    }
}
