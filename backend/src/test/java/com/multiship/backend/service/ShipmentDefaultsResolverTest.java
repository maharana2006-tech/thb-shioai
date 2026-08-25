package com.multiship.backend.service;

import com.multiship.backend.model.Address;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.CountryCurrency;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.repository.CountryCurrencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link ShipmentDefaultsResolver} — the centralised
 * per-shipment defaults resolver.
 *
 * <p>Each field's precedence chain gets a dedicated set of tests so a
 * future refactor that accidentally reorders sources fails loudly. The
 * hardcode fallbacks + throw-on-required cases are also pinned.
 */
class ShipmentDefaultsResolverTest {

    private CountryCurrencyRepository countryCurrencyRepository;
    private ShipmentDefaultsResolver resolver;

    @BeforeEach
    void setUp() {
        countryCurrencyRepository = mock(CountryCurrencyRepository.class);
        // Default: no country match. Individual tests override for the
        // country_currency-fallback case.
        when(countryCurrencyRepository.findByCountryCodeIgnoreCase(anyString()))
                .thenReturn(Optional.empty());
        resolver = new ShipmentDefaultsResolver(countryCurrencyRepository);
    }

    // ===== helpers =====

    private Client clientWith(String currency, String weightUnit, String dimUnit,
                               String timezone, String shipFromCountry) {
        Client c = Client.builder()
                .id(1L).clientCode("ACME").name("Acme")
                .defaultCurrency(currency)
                .defaultWeightUnit(weightUnit)
                .defaultDimUnit(dimUnit)
                .timezone(timezone)
                .shipFrom(Address.builder().country(shipFromCountry).build())
                .build();
        return c;
    }

    private CarrierAccountRef accountWith(String purpose, String clearance) {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setId(42L);
        a.setCarrierCode("UPS");
        a.setAccountNumber("A12345");
        a.setShippingPurpose(purpose);
        a.setClearanceOption(clearance);
        return a;
    }

    private OrderCustoms customsWith(String currency, String reason, String weightUnit, String dimUnit) {
        OrderCustoms cu = OrderCustoms.builder()
                .orderNo("100").currency(currency)
                .reasonForExport(reason).weightUnit(weightUnit).dimUnit(dimUnit)
                .build();
        return cu;
    }

    private ShipmentDefaultsResolver.ResolveInputs inputsFor(OrderCustoms customs, Client client,
                                                              CarrierAccountRef account, boolean intl) {
        return new ShipmentDefaultsResolver.ResolveInputs(
                null, null, null, null, null, null,
                null,   // FDX-H2 requestPickupType
                customs, client, account, intl);
    }

    // ===== currency =====

    @Test
    void currency_customsWinsOverClientAndCountry() {
        when(countryCurrencyRepository.findByCountryCodeIgnoreCase("US"))
                .thenReturn(Optional.of(CountryCurrency.builder()
                        .countryCode("US").currencyCode("USD").build()));
        Client c = clientWith("GBP", null, null, null, "US");
        OrderCustoms cu = customsWith("EUR", null, null, null);
        var out = resolver.resolve(inputsFor(cu, c, null, false));
        assertEquals("EUR", out.currency(), "customs.currency wins over client + country_currency");
    }

    @Test
    void currency_clientWinsOverCountry() {
        when(countryCurrencyRepository.findByCountryCodeIgnoreCase("US"))
                .thenReturn(Optional.of(CountryCurrency.builder()
                        .countryCode("US").currencyCode("USD").build()));
        Client c = clientWith("GBP", null, null, null, "US");
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("GBP", out.currency(), "client.defaultCurrency wins over country_currency");
    }

    @Test
    void currency_countryFallbackFires_whenClientDefaultBlank() {
        when(countryCurrencyRepository.findByCountryCodeIgnoreCase("DE"))
                .thenReturn(Optional.of(CountryCurrency.builder()
                        .countryCode("DE").currencyCode("EUR").build()));
        Client c = clientWith(null, null, null, null, "DE");
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("EUR", out.currency(), "German client with no explicit currency → EUR from country_currency");
    }

    @Test
    void currency_domesticDefaultsToUsd_whenNothingElseResolves() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("USD", out.currency(),
                "domestic shipment with no client + no customs + no country → USD hardcode");
    }

    @Test
    void currency_internationalThrows_whenNothingElseResolves() {
        ShipmentDefaultsResolver.ShipmentDefaultsException ex = assertThrows(
                ShipmentDefaultsResolver.ShipmentDefaultsException.class,
                () -> resolver.resolve(inputsFor(null, null, null, true)));
        assertTrue(ex.getMessage().contains("international"),
                "message must name the international-blank case; got: " + ex.getMessage());
    }

    // ===== weightUnit =====

    @Test
    void weightUnit_customsWinsOverClient() {
        Client c = clientWith(null, "KG", null, null, null);
        OrderCustoms cu = customsWith(null, null, "OZ", null);
        var out = resolver.resolve(inputsFor(cu, c, null, false));
        assertEquals("OZ", out.weightUnit());
    }

    @Test
    void weightUnit_clientWinsOverHardcode() {
        Client c = clientWith(null, "KG", null, null, null);
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("KG", out.weightUnit());
    }

    @Test
    void weightUnit_lbHardcode_lastResort() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("LB", out.weightUnit());
    }

    // ===== dimUnit =====

    @Test
    void dimUnit_customsWinsOverClient() {
        Client c = clientWith(null, null, "CM", null, null);
        OrderCustoms cu = customsWith(null, null, null, "IN");
        var out = resolver.resolve(inputsFor(cu, c, null, false));
        assertEquals("IN", out.dimUnit(),
                "customs.dimUnit wins over client default (Gap 1 from F5-A: customs source used to be ignored for dim)");
    }

    @Test
    void dimUnit_clientWinsOverHardcode() {
        Client c = clientWith(null, null, "CM", null, null);
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("CM", out.dimUnit());
    }

    @Test
    void dimUnit_inHardcode_lastResort() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("IN", out.dimUnit());
    }

    // ===== timezone =====

    @Test
    void timezone_clientDefault_wins() {
        Client c = clientWith(null, null, null, "America/Denver", null);
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("America/Denver", out.timezone());
    }

    @Test
    void timezone_blankAllPaths_returnsNull() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertNull(out.timezone(), "timezone is optional — connectors handle null");
    }

    @Test
    void timezone_preservesCase_becauseIanaIdsAreCaseSensitive() {
        Client c = clientWith(null, null, null, "Europe/London", null);
        var out = resolver.resolve(inputsFor(null, c, null, false));
        assertEquals("Europe/London", out.timezone(),
                "IANA zones like Europe/London must not be uppercased");
    }

    // ===== shippingPurpose =====

    @Test
    void shippingPurpose_customsReasonWins() {
        // API contract: the CALLER (CarrierServiceImpl) extracts
        // customs.reasonForExport into ResolveInputs.requestShippingPurpose
        // — the resolver itself doesn't read customs for purpose (the
        // per-shipment override lives on that field, not in the customs
        // object). This test mirrors the CarrierServiceImpl call shape.
        CarrierAccountRef a = accountWith("MERCHANDISE", null);
        OrderCustoms cu = customsWith(null, "GIFT", null, null);
        var inputs = new ShipmentDefaultsResolver.ResolveInputs(
                null, null, null, null,
                cu.getReasonForExport(),   // ← caller threads customs.reasonForExport here
                null,
                null,   // FDX-H2 requestPickupType
                cu, null, a, false);
        var out = resolver.resolve(inputs);
        assertEquals("GIFT", out.shippingPurpose(),
                "customs.reasonForExport (via requestShippingPurpose) wins over account default");
    }

    @Test
    void shippingPurpose_accountDefault_whenCustomsBlank() {
        CarrierAccountRef a = accountWith("MERCHANDISE", null);
        var out = resolver.resolve(inputsFor(null, null, a, false));
        assertEquals("MERCHANDISE", out.shippingPurpose());
    }

    @Test
    void shippingPurpose_saleHardcode_lastResort() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("SALE", out.shippingPurpose(),
                "no request + no account → default SALE (matches pre-fix connector-hardcode)");
    }

    @Test
    void shippingPurpose_unknownEnum_throws() {
        // A typo like "GFT" (missing I) used to silently reach the connector
        // and produce a cryptic downstream error. Post-fix: throw at the
        // resolver boundary with a listing of accepted values.
        CarrierAccountRef a = accountWith("GFT", null);
        ShipmentDefaultsResolver.ShipmentDefaultsException ex = assertThrows(
                ShipmentDefaultsResolver.ShipmentDefaultsException.class,
                () -> resolver.resolve(inputsFor(null, null, a, false)));
        assertTrue(ex.getMessage().contains("GFT"),
                "must name the offending value; got: " + ex.getMessage());
    }

    // ===== clearanceOption =====

    @Test
    void clearanceOption_accountDefault_pathThrough() {
        CarrierAccountRef a = accountWith(null, "THIRD_PARTY");
        var out = resolver.resolve(inputsFor(null, null, a, false));
        assertEquals("THIRD_PARTY", out.clearanceOption());
    }

    @Test
    void clearanceOption_blankAllPaths_returnsNull() {
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertNull(out.clearanceOption(), "clearance is optional — connectors apply their own default");
    }

    @Test
    void clearanceOption_perCarrierVocabularyPassesThroughVerbatim() {
        // F6-A decision: no normalization layer. Whatever the account carries
        // (UPS: SENDER, FedEx: RECIPIENT, Stamps: DDU/DDP) passes through
        // to the connector which maps its own.
        CarrierAccountRef a = accountWith(null, "DDP");
        var out = resolver.resolve(inputsFor(null, null, a, false));
        assertEquals("DDP", out.clearanceOption(),
                "Stamps-style DDP passes through without translation");
    }

    // ===== pickupType (FDX-H2) =====

    @Test
    void pickupType_defaultUseScheduledPickup_whenNoRequestNoAccount() {
        // Matches pre-FDX-H2 FedEx hardcode; keeps back-compat for callers
        // that don't populate the field.
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("USE_SCHEDULED_PICKUP", out.pickupType());
    }

    @Test
    void pickupType_accountDefault_passesThrough() {
        // Operator set DROP_BOX on the account (drop-off shipper without a
        // standing pickup). Resolver picks it up so FedEx labels don't send
        // USE_SCHEDULED_PICKUP and get rejected.
        CarrierAccountRef a = new CarrierAccountRef();
        a.setId(42L);
        a.setCarrierCode("FEDEX");
        a.setAccountNumber("A12345");
        a.setPickupType("DROP_BOX");
        var out = resolver.resolve(inputsFor(null, null, a, false));
        assertEquals("DROP_BOX", out.pickupType(),
                "account default must survive to the resolved output");
    }

    @Test
    void pickupType_requestOverridesAccount() {
        // Per-shipment override wins over the account default (per-shipment
        // is more specific than per-account).
        CarrierAccountRef a = new CarrierAccountRef();
        a.setPickupType("DROP_BOX");
        var inputs = new ShipmentDefaultsResolver.ResolveInputs(
                null, null, null, null, null, null,
                "REQUEST_COURIER",      // request-level override
                null, null, a, false);
        assertEquals("REQUEST_COURIER", resolver.resolve(inputs).pickupType());
    }

    @Test
    void pickupType_upperCasedOnResolve() {
        // The DTO field is @Pattern-validated to the enum values in upper
        // case but the resolver still normalises defensively in case a
        // programmatic caller passes lower-case.
        CarrierAccountRef a = new CarrierAccountRef();
        a.setPickupType("drop_box");
        assertEquals("DROP_BOX", resolver.resolve(inputsFor(null, null, a, false)).pickupType());
    }

    // ===== defensive =====

    @Test
    void nullInputs_throws() {
        assertThrows(ShipmentDefaultsResolver.ShipmentDefaultsException.class,
                () -> resolver.resolve(null));
    }

    @Test
    void allNullDependencies_domesticStillResolves() {
        // Verifies the resolver copes with the most-minimal case (no client,
        // no account, no customs, domestic) without NPE.
        var out = resolver.resolve(inputsFor(null, null, null, false));
        assertEquals("USD", out.currency());
        assertEquals("LB", out.weightUnit());
        assertEquals("IN", out.dimUnit());
        assertEquals("SALE", out.shippingPurpose());
        assertNull(out.timezone());
        assertNull(out.clearanceOption());
    }
}
