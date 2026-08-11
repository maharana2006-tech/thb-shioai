package com.multiship.backend.service.external;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalParcel;
import com.multiship.backend.dto.external.ExternalShipmentRequest;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRawCodesRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.intake.ClientCodeTranslationService;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 1 finding #16 regression guards — the public "create
 * shipment" API used to silently default {@code parcel.weightUnit} to
 * {@code lb} and {@code currency} to {@code USD}. A KG parcel labeled as
 * LB under-declares by ~2.2×; EUR declared as USD is a customs
 * misdeclaration. Both now throw with a machine-readable error code.
 *
 * <p>The test setup uses a client-bound API key with a carrier + account +
 * shipFrom override so the request reaches the weight-unit / currency
 * guards without needing to mock the entire shipping-config / warehouse
 * resolver chain.
 */
class ExternalApiServiceTest {

    private ExternalApiService service;
    private ClientCodeTranslationService translationService;
    private ShipmentResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        translationService = mock(ClientCodeTranslationService.class);
        resolutionService = mock(ShipmentResolutionService.class);

        // Translations no-op — Optional.empty means "no client alias".
        // any() (not anyString()) so null rawPackageCode / rawShipvia matches too.
        when(translationService.translateDestCountry(anyString(), any())).thenReturn(Optional.empty());
        when(translationService.translateShipvia(anyString(), any())).thenReturn(Optional.empty());
        when(translationService.translatePackage(anyString(), any())).thenReturn(Optional.empty());
        // Warehouse resolver: no attached warehouse → request-supplied shipFrom wins.
        // any() matches null (baseRequest() does not set warehouseCode).
        when(resolutionService.resolveWarehouse(anyString(), any())).thenReturn(Optional.empty());

        service = new ExternalApiService(
                mock(ShippingConfigService.class),
                mock(ShippingServiceRepository.class),
                mock(CarrierAccountRefRepository.class),
                mock(ClientRepository.class),
                mock(OrderRepository.class),
                mock(OrderTrackingRepository.class),
                mock(CarrierService.class),
                new CarrierProperties(),
                resolutionService,
                translationService,
                mock(OrderRawCodesRepository.class),
                List.of());
    }

    private ApiKeyPrincipal clientBoundKey() {
        return new ApiKeyPrincipal(1L, "test-key", "ACME", Set.of("shipments:write"));
    }

    private ExternalShipmentRequest baseRequest() {
        ExternalShipmentRequest r = new ExternalShipmentRequest();
        ExternalAddress to = new ExternalAddress();
        to.setName("Jane Doe");
        to.setAddressLine1("1 Test Street");
        to.setCity("Louisville");
        to.setState("KY");
        to.setPostalCode("40209");
        to.setCountryCode("US");
        r.setShipTo(to);

        ExternalAddress from = new ExternalAddress();
        from.setName("Acme Warehouse");
        from.setAddressLine1("1 Warehouse Way");
        from.setCity("Louisville");
        from.setState("KY");
        from.setPostalCode("40209");
        from.setCountryCode("US");
        r.setShipFrom(from);

        // Bypass warehouse resolver / ship-method rule / account resolution
        // by supplying overrides — none of these matter for the weight-unit
        // and currency guards under test.
        r.setCarrierCode("UPS");
        r.setAccountNumber("12345");
        r.setShipMethod("F77");

        ExternalParcel p = new ExternalParcel();
        p.setWeight(new BigDecimal("1.5"));
        r.setParcel(p);
        return r;
    }

    @Test
    void createShipment_rejectsWeightWithoutUnit() {
        ExternalShipmentRequest req = baseRequest();
        req.getParcel().setWeightUnit(null); // silently defaulted to LB pre-fix

        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> service.createShipment(clientBoundKey(), req));

        assertEquals(422, ex.getStatus());
        assertEquals(ErrorCode.UNIT_REQUIRED, ex.getErrorCode());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("silent defaulting to 'lb'"),
                "Expected message to explicitly call out the silent default; got: " + ex.getMessage());
    }

    @Test
    void createShipment_rejectsDeclaredValueWithoutCurrency() {
        ExternalShipmentRequest req = baseRequest();
        // Supply a valid weightUnit so the earlier guard passes; the
        // currency guard is the target of this test.
        req.getParcel().setWeightUnit("kg");
        req.setDeclaredValue(new BigDecimal("100.00"));
        req.setCurrency(null); // silently defaulted to USD pre-fix

        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> service.createShipment(clientBoundKey(), req));

        assertEquals(422, ex.getStatus());
        assertEquals(ErrorCode.CURRENCY_REQUIRED, ex.getErrorCode());
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("silent defaulting to 'USD'"),
                "Expected message to explicitly call out the silent default; got: " + ex.getMessage());
    }
}
