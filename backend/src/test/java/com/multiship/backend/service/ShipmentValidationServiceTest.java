package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.ShipmentValidationResult;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.resolution.PackagingCompatibilityGuard;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 — ShipmentValidationService pre-flight tests. Verifies the
 * server-side pre-flight runs comprehensive missing-field checks
 * (recipient / sender / shipment), integrates PackagingCompatibilityGuard
 * + markup + IntlShipmentValidator + DangerousGoodsValidator, and does
 * NOT call any carrier APIs — this is a strictly local pre-flight per
 * the Sprint 52 design pick "don't apply any fallback".
 */
class ShipmentValidationServiceTest {

    private PackagingCompatibilityGuard packagingCompatibilityGuard;
    private ShipmentResolutionService resolutionService;
    private ShippingServiceRepository shippingServiceRepository;
    private PackagePresetRepository packagePresetRepository;
    private com.multiship.backend.service.CarrierService carrierService;
    private com.multiship.backend.repository.CarrierAccountRefRepository carrierAccountRefRepository;
    private com.multiship.backend.repository.ClientRepository clientRepository;
    private com.multiship.backend.repository.ClientCustomsProfileRepository clientCustomsProfileRepository;

    private ShipmentValidationService service;

    @BeforeEach
    void setUp() {
        packagingCompatibilityGuard = mock(PackagingCompatibilityGuard.class);
        resolutionService = mock(ShipmentResolutionService.class);
        shippingServiceRepository = mock(ShippingServiceRepository.class);
        packagePresetRepository = mock(PackagePresetRepository.class);
        // Sprint 52 PR δ — deps for carrier-native validateShipment.
        // Constructor arg order matches @RequiredArgsConstructor field
        // declaration order in ShipmentValidationService.
        carrierService = mock(com.multiship.backend.service.CarrierService.class);
        carrierAccountRefRepository = mock(com.multiship.backend.repository.CarrierAccountRefRepository.class);
        // Strict-fill sources for intl currency + incoterms (no
        // hardcoded USD/DAP defaults).
        clientRepository = mock(com.multiship.backend.repository.ClientRepository.class);
        clientCustomsProfileRepository = mock(
                com.multiship.backend.repository.ClientCustomsProfileRepository.class);

        service = new ShipmentValidationService(
                packagingCompatibilityGuard, resolutionService,
                shippingServiceRepository, packagePresetRepository,
                carrierService, carrierAccountRefRepository,
                clientRepository, clientCustomsProfileRepository);
    }

    // ─── Happy path ─────────────────────────────────────────────────────

    @Test
    void domesticFullyPopulated_returnsPassWithNoErrors() {
        // Full-form domestic ad-hoc shipment with all required fields set.
        // Every intl / customs / DG / markup / allowlist check is either
        // skipped (ad-hoc, domestic) or passes silently.
        ManualShipmentRequest req = fullDomesticRequest();
        // Preset lookup returns a CUSTOM box so PackagingCompatibilityGuard
        // short-circuits without a mock stub.
        when(shippingServiceRepository.findById(1L)).thenReturn(Optional.of(fedexGround()));
        when(packagePresetRepository.findById(7L)).thenReturn(Optional.of(customBox()));

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        ShipmentValidationResult r = res.getData();
        assertNotNull(r);
        assertEquals("PASS", r.getOverall(),
                "fully populated domestic ad-hoc shipment must pass — got errors: " + r.getLocalErrors());
        assertNull(r.getAddress(),
                "no carrier calls in server-side pre-flight — address subresult must always be null");
    }

    // ─── Missing recipient fields (comprehensive) ──────────────────────

    @Test
    void blankRecipientAddressLine1_errorsWithField() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.getRecipient().setAddressLine1("");
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertEquals("FAIL", res.getData().getOverall());
        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "recipient.addressLine1".equals(e.getField())));
    }

    @Test
    void blankRecipientState_errorsForUS_MX_CA_AU_BR() {
        // Sprint 52 — state is required for these countries; the DTO
        // allows blank but the carrier's label call rejects it.
        for (String country : java.util.List.of("US", "CA", "AU", "MX", "BR")) {
            ManualShipmentRequest req = fullDomesticRequest();
            req.getRecipient().setCountryCode(country);
            req.getRecipient().setState("");
            req.getSender().setCountryCode(country);
            stubServiceAndPreset();

            ApiResponse<ShipmentValidationResult> res = service.validate(req);

            assertTrue(res.getData().getLocalErrors().stream()
                            .anyMatch(e -> "recipient.state".equals(e.getField())),
                    "state must be required for " + country + " but wasn't flagged");
        }
    }

    @Test
    void blankRecipientPhone_warnsButDoesNotError() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.getRecipient().setPhone("");
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .noneMatch(e -> "recipient.phone".equals(e.getField())),
                "phone missing is a suggestion, not an error");
        assertTrue(res.getData().getLocalWarnings().stream()
                .anyMatch(e -> "recipient.phone".equals(e.getField())),
                "phone missing must produce a warning so the operator sees it");
    }

    // ─── Missing sender fields ─────────────────────────────────────────

    @Test
    void nullSender_withoutWarehouseCode_errors() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.setSender(null);
        req.setWarehouseCode(null);
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertEquals("FAIL", res.getData().getOverall());
        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "sender".equals(e.getField())));
    }

    @Test
    void nullSender_withWarehouseCode_ok_becauseWarehouseWillFillIn() {
        // The label pipeline resolves the warehouseCode into a sender
        // block. Pre-flight can't do the resolution itself; when the
        // caller signals a warehouse, we trust that the resolution will
        // fill in the sender (label call will error if it doesn't).
        ManualShipmentRequest req = fullDomesticRequest();
        req.setSender(null);
        req.setWarehouseCode("WH1");
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .noneMatch(e -> "sender".equals(e.getField())),
                "warehouse-code set → sender resolution is deferred; not a pre-flight error");
    }

    // ─── Missing shipment fields ───────────────────────────────────────

    @Test
    void missingWeight_errors() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.setWeight(null);
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "weight".equals(e.getField())));
    }

    @Test
    void missingService_errors() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.setServiceId(null);
        when(packagePresetRepository.findById(7L)).thenReturn(Optional.of(customBox()));

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "serviceId".equals(e.getField())));
    }

    @Test
    void missingPackageAndDims_errors() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.setPackagePresetId(null);
        req.setLength(null);
        req.setWidth(null);
        req.setHeight(null);
        when(shippingServiceRepository.findById(1L)).thenReturn(Optional.of(fedexGround()));

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "packagePresetId".equals(e.getField())
                        && e.getMessage().contains("length")
                        && e.getMessage().contains("width")
                        && e.getMessage().contains("height")),
                "custom-dims path must list every missing dim in one error");
    }

    // ─── International routing + customs ───────────────────────────────

    @Test
    void usToUk_classifiedInternational_customsCheckRuns() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.getRecipient().setCountryCode("GB");
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(res.getData().isInternational());
        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> e.getCode().contains("commodities")),
                "intl shipment without commodities must fail with customs.commodities.empty");
    }

    @Test
    void usToPR_sameFamilyTerritory_classifiedDomestic_customsSkipped() {
        // Sprint 52 design pick — US → PR/VI/GU/AS/MP is domestic.
        ManualShipmentRequest req = fullDomesticRequest();
        req.getRecipient().setCountryCode("PR");
        stubServiceAndPreset();

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertTrue(!res.getData().isInternational(),
                "US → PR must be domestic under the US-family sameTerritory rule");
        assertTrue(res.getData().getSkipped().stream()
                .anyMatch(s -> "customs".equals(s.getName())));
    }

    // ─── Markup required (Sprint 50 Tier 1 finding #11) ───────────────

    @Test
    void clientWithoutMarkup_errorsWithMarkupErrorCode() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.setClientCode("THB000");
        stubServiceAndPreset();
        doThrow(new ShipmentResolutionException(ErrorCode.MARKUP_REQUIRED_FOR_CLIENT,
                "Client THB000 has no billing markup configured."))
                .when(resolutionService).applyMarkup(anyString(), any(), anyString());

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertEquals("FAIL", res.getData().getOverall());
        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> ErrorCode.MARKUP_REQUIRED_FOR_CLIENT.name().equals(e.getCode())));
    }

    // ─── Packaging compatibility (Sprint 52 PR 1 integration) ─────────

    @Test
    void fedexGround_withFedexEnvelope_errorsWithPackageNotAllowedForService() {
        // Regression pin for order-900003-class errors.
        ManualShipmentRequest req = fullDomesticRequest();
        req.setPackagePresetId(9L);
        ShippingService svc = fedexGround();
        PackagePreset envelope = PackagePreset.builder()
                .id(9L).name("FedEx Envelope").kind("CARRIER")
                .carrierPackageCode("FEDEX_ENVELOPE")
                .ownerType(PackagePreset.OWNER_PLATFORM).build();
        when(shippingServiceRepository.findById(1L)).thenReturn(Optional.of(svc));
        when(packagePresetRepository.findById(9L)).thenReturn(Optional.of(envelope));
        doThrow(new ShipmentResolutionException(ErrorCode.PACKAGE_NOT_ALLOWED_FOR_SERVICE,
                "Package FEDEX_ENVELOPE is not allowed for service FEDEX FEDEX_GROUND."))
                .when(packagingCompatibilityGuard).assertCompatible(svc, envelope);

        ApiResponse<ShipmentValidationResult> res = service.validate(req);

        assertEquals("FAIL", res.getData().getOverall());
        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> ErrorCode.PACKAGE_NOT_ALLOWED_FOR_SERVICE.name().equals(e.getCode())));
    }

    // ─── Commodity weight adapter (regression — FedEx intl pre-flight) ─
    //
    // Historical bug: adaptForValidators built CustomsCommodityDTOs
    // without unitWeight, so the FedEx validateShipment pre-flight sent
    // commodities with null weights and FedEx rejected every intl
    // shipment with "Commodity weight is missing or invalid". These
    // tests pin buildValidatorCommodities' spread-fallback behaviour so
    // the pre-flight now mirrors buildManualIntlBlock.

    @Test
    void buildValidatorCommodities_perLineWeight_setDirectlyWhenPresent() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("10.0"));
        req.setWeightUnit("LB");
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription("Widget");
        it.setQuantity(2);
        it.setUnitValue(new BigDecimal("15.00"));
        it.setWeight(new BigDecimal("3.5"));
        req.setItems(java.util.List.of(it));

        var commodities = ShipmentValidationService.buildValidatorCommodities(req);

        assertEquals(1, commodities.size());
        assertEquals(new BigDecimal("3.5"), commodities.get(0).getUnitWeight());
    }

    @Test
    void buildValidatorCommodities_missingLineWeight_spreadsFromPkgWeight() {
        // pkgWeight=10 LB, two items qty=2 + qty=3 → total qty 5. Each
        // line's share is pkgWeight * qty / totalQty = 4.0 and 6.0
        // respectively — a strict share so per-line sum equals pkg wt.
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("10.0"));
        req.setWeightUnit("LB");
        ManualShipmentRequest.Item a = new ManualShipmentRequest.Item();
        a.setDescription("A"); a.setQuantity(2);
        ManualShipmentRequest.Item b = new ManualShipmentRequest.Item();
        b.setDescription("B"); b.setQuantity(3);
        req.setItems(java.util.List.of(a, b));

        var commodities = ShipmentValidationService.buildValidatorCommodities(req);

        assertEquals(new BigDecimal("4.000"), commodities.get(0).getUnitWeight());
        assertEquals(new BigDecimal("6.000"), commodities.get(1).getUnitWeight());
    }

    @Test
    void buildValidatorCommodities_zeroLineWeight_treatedAsMissing_spreads() {
        // Regression pin — the fix now checks signum > 0, so a stored 0
        // weight (from an operator FE edge case) falls through to the
        // pkg-weight spread instead of being sent to FedEx as-is.
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("6.0"));
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription("X"); it.setQuantity(1); it.setWeight(BigDecimal.ZERO);
        req.setItems(java.util.List.of(it));

        var commodities = ShipmentValidationService.buildValidatorCommodities(req);

        assertEquals(new BigDecimal("6.000"), commodities.get(0).getUnitWeight(),
                "0 weight must be treated as missing and replaced by the spread");
    }

    @Test
    void buildValidatorCommodities_noPkgWeightAndNoLineWeight_returnsNullWeight() {
        // Strict — no more 0.10 last-resort. When pkgWeight AND line
        // weight both blank, the shaper returns null weight and the
        // local validator surfaces customs.weight.missing.
        ManualShipmentRequest req = new ManualShipmentRequest();
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription("Y"); it.setQuantity(1);
        req.setItems(java.util.List.of(it));

        var commodities = ShipmentValidationService.buildValidatorCommodities(req);

        assertNull(commodities.get(0).getUnitWeight(),
                "no fallback — undeliverable weight surfaces as an error, not fabricated 0.10");
    }

    @Test
    void buildValidatorCommodities_missingUnitValue_returnsNullPrice() {
        // Strict — no declared-value spread. Missing unitValue stays
        // null; local validator surfaces customs.unitValue.missing.
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("5.0"));
        req.setDeclaredValue(new BigDecimal("100.00"));
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription("Z"); it.setQuantity(2);
        req.setItems(java.util.List.of(it));

        var commodities = ShipmentValidationService.buildValidatorCommodities(req);

        assertNull(commodities.get(0).getUnitValue(),
                "no declared-value spread — undeliverable price surfaces as an error");
    }

    // ─── Customs total + currency roll-up (Bug #2 from deep-dive) ──────

    @Test
    void adaptForValidators_customsTotal_prefersCommoditySumOverDeclaredValue() {
        // Operator entered items with unitValue but no explicit declared
        // value — customs total must be summed from lines, not left null.
        // Pre-fix: intl.customsTotalValue = req.declaredValue = null →
        // FedEx rejected with "Insufficient information for commodity 1".
        ManualShipmentRequest req = intlReqWithItems(
                new BigDecimal("25.00"), 2, new BigDecimal("50.00"), 1);
        req.setDeclaredValue(null);

        var dto = service.adaptForValidators(req, true);

        assertEquals(new BigDecimal("100.00"), dto.getIntl().getCustomsTotalValue(),
                "customs total must sum from commodity lines when declared value blank");
    }

    @Test
    void adaptForValidators_customsTotal_isNullWhenCommoditySumZero() {
        // Strict — no declared-value fallback on customsTotal either.
        // With blank-price items, unitValue stays null so lineTotalValue
        // is null → commodity sum is 0 → customsTotal is null. Local
        // validator will already have flagged customs.unitValue.missing.
        ManualShipmentRequest req = intlReqWithBlankPriceItems(2);
        req.setDeclaredValue(new BigDecimal("40.00"));

        var dto = service.adaptForValidators(req, true);

        assertNull(dto.getIntl().getCustomsTotalValue(),
                "no declared-value fallback — customs total is only summed from real line values");
    }

    @Test
    void adaptForValidators_customsCurrency_isNullWhenAllSourcesBlank() {
        // Precedence: operator → CarrierAccountRef → Client.defaultCurrency.
        // All three blank here (ad-hoc + no client + no matching
        // account) → currency is null and checkIntlRequiredFields flags
        // customs.currency.missing on the local errors.
        ManualShipmentRequest req = intlReqWithItems(
                new BigDecimal("10"), 1, new BigDecimal("10"), 1);
        req.setCurrency(null);
        req.setClientCode(null);
        req.setCarrierCode(null);

        var dto = service.adaptForValidators(req, true);

        assertNull(dto.getIntl().getCustomsCurrency());
    }

    @Test
    void adaptForValidators_incoterms_isNullWhenAllSourcesBlank() {
        // Precedence: operator → ClientCustomsProfile.incoterms. Ad-hoc
        // shipment with no clientCode → no profile → null → local error.
        ManualShipmentRequest req = intlReqWithItems(
                new BigDecimal("10"), 1, new BigDecimal("10"), 1);
        req.setIncoterms(null);
        req.setClientCode(null);

        var dto = service.adaptForValidators(req, true);

        assertNull(dto.getIntl().getIncoterms());
    }

    // ─── Currency + incoterms precedence resolvers ─────────────────────

    @Test
    void resolveCustomsCurrency_operatorSelectionWins() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCurrency("EUR");
        req.setCarrierCode("FEDEX"); req.setAccountNumber("ACC1");
        // Account has different currency — operator choice must still win.
        com.multiship.backend.model.CarrierAccountRef acc =
                com.multiship.backend.model.CarrierAccountRef.builder()
                        .carrierCode("FEDEX").accountNumber("ACC1")
                        .currency("USD").clientId("id").clientSecret("s")
                        .active(true).build();
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("FEDEX"))
                .thenReturn(java.util.List.of(acc));

        assertEquals("EUR", service.resolveCustomsCurrency(req));
    }

    @Test
    void resolveCustomsCurrency_fallsThroughToCarrierAccount() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode("FEDEX"); req.setAccountNumber("ACC1");
        com.multiship.backend.model.CarrierAccountRef acc =
                com.multiship.backend.model.CarrierAccountRef.builder()
                        .carrierCode("FEDEX").accountNumber("ACC1")
                        .currency("gbp").clientId("id").clientSecret("s")
                        .active(true).build();
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("FEDEX"))
                .thenReturn(java.util.List.of(acc));

        assertEquals("GBP", service.resolveCustomsCurrency(req));
    }

    @Test
    void resolveCustomsCurrency_fallsThroughToClientDefault() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode("FEDEX"); req.setAccountNumber("ACC1");
        req.setClientCode("THB001");
        // Account has no currency; client has one.
        com.multiship.backend.model.CarrierAccountRef acc =
                com.multiship.backend.model.CarrierAccountRef.builder()
                        .carrierCode("FEDEX").accountNumber("ACC1")
                        .clientId("id").clientSecret("s").active(true).build();
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("FEDEX"))
                .thenReturn(java.util.List.of(acc));
        com.multiship.backend.model.Client client =
                com.multiship.backend.model.Client.builder()
                        .clientCode("THB001").defaultCurrency("CAD").build();
        when(clientRepository.findByClientCodeIgnoreCase("THB001"))
                .thenReturn(java.util.Optional.of(client));

        assertEquals("CAD", service.resolveCustomsCurrency(req));
    }

    @Test
    void resolveCustomsCurrency_returnsNullWhenAllBlank() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        assertNull(service.resolveCustomsCurrency(req));
    }

    @Test
    void resolveIncoterms_operatorSelectionWins() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setIncoterms("ddp");
        req.setClientCode("THB001");
        // Profile has DAP but operator wins.
        com.multiship.backend.model.ClientCustomsProfile profile =
                com.multiship.backend.model.ClientCustomsProfile.builder()
                        .clientCode("THB001").incoterms("DAP").build();
        when(clientCustomsProfileRepository.findByClientAndCountry("THB001", "GB"))
                .thenReturn(java.util.Optional.of(profile));

        assertEquals("DDP", service.resolveIncoterms(req, "GB"));
    }

    @Test
    void resolveIncoterms_fallsThroughToCustomsProfile() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setClientCode("THB001");
        com.multiship.backend.model.ClientCustomsProfile profile =
                com.multiship.backend.model.ClientCustomsProfile.builder()
                        .clientCode("THB001").incoterms("DAP").build();
        when(clientCustomsProfileRepository.findByClientAndCountry("THB001", "GB"))
                .thenReturn(java.util.Optional.of(profile));

        assertEquals("DAP", service.resolveIncoterms(req, "GB"));
    }

    @Test
    void resolveIncoterms_returnsNullForAdhocWithNoOperatorChoice() {
        // Ad-hoc (no clientCode) + blank operator incoterms → null →
        // local error 'Incoterms is required'.
        ManualShipmentRequest req = new ManualShipmentRequest();
        assertNull(service.resolveIncoterms(req, "GB"));
    }

    // ─── Strict per-commodity checks (end-to-end via validate()) ───────

    @Test
    void intlShipment_missingHsCode_flagsLocalError() {
        ManualShipmentRequest req = fullIntlRequest();
        req.getItems().get(0).setHsCode(null);
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> e.getField() != null && e.getField().endsWith("hsCode")
                        && e.getMessage().contains("HS")),
                "missing hsCode must fail with row-scoped error");
    }

    @Test
    void intlShipment_missingCountryOfOrigin_flagsLocalError() {
        ManualShipmentRequest req = fullIntlRequest();
        req.getItems().get(0).setCountryOfOrigin(null);
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> e.getField() != null && e.getField().endsWith("countryOfOrigin")),
                "missing countryOfOrigin must fail with row-scoped error");
    }

    @Test
    void intlShipment_zeroUnitValue_flagsLocalError() {
        ManualShipmentRequest req = fullIntlRequest();
        req.getItems().get(0).setUnitValue(BigDecimal.ZERO);
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> e.getField() != null && e.getField().endsWith("unitValue")),
                "0 unitValue must fail — no silent declared-value spread");
    }

    @Test
    void intlShipment_missingCurrencyAndAllSources_flagsLocalError() {
        ManualShipmentRequest req = fullIntlRequest();
        req.setCurrency(null); req.setClientCode(null); req.setCarrierCode(null);
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "currency".equals(e.getField())),
                "unresolvable currency must fail — no USD default");
    }

    @Test
    void intlShipment_missingIncotermsAndAllSources_flagsLocalError() {
        ManualShipmentRequest req = fullIntlRequest();
        req.setIncoterms(null); req.setClientCode(null);
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalErrors().stream()
                .anyMatch(e -> "incoterms".equals(e.getField())),
                "unresolvable incoterms must fail — no DAP default");
    }

    // ─── Generic high-value export-declaration advisory (warning-level) ─

    @Test
    void highValueIntl_noExportRef_emitsExportDeclarationWarning() {
        // $3,000 US→GB with none of ftr/aes/exportDeclarationReference set.
        // Warning-only (not error) so it doesn't false-block intra-EU /
        // US→CA / other corridors that legitimately need no reference.
        ManualShipmentRequest req = fullIntlRequest();
        req.setDeclaredValue(new BigDecimal("3000.00"));
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalWarnings().stream()
                .anyMatch(w -> ShipmentValidationService.CODE_EXPORT_DECLARATION_RECOMMENDED
                        .equals(w.getCode())),
                "high-value intl without any export ref must WARN — got warnings: "
                        + res.getData().getLocalWarnings());
    }

    @Test
    void highValueIntl_withExportDeclarationReference_noWarning() {
        ManualShipmentRequest req = fullIntlRequest();
        req.setDeclaredValue(new BigDecimal("3000.00"));
        req.setExportDeclarationReference("CA-B13A-2026-99999");
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalWarnings().stream()
                .noneMatch(w -> ShipmentValidationService.CODE_EXPORT_DECLARATION_RECOMMENDED
                        .equals(w.getCode())),
                "populated exportDeclarationReference must suppress the advisory");
    }

    @Test
    void highValueIntl_withFtrExemption_noExportWarning() {
        // Reusing FTR from the US flow — same field satisfies the generic
        // advisory too (any of the three refs suppresses the warning).
        ManualShipmentRequest req = fullIntlRequest();
        req.setDeclaredValue(new BigDecimal("3000.00"));
        req.setFtrExemption("NO_EEI_30_37_h");
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalWarnings().stream()
                .noneMatch(w -> ShipmentValidationService.CODE_EXPORT_DECLARATION_RECOMMENDED
                        .equals(w.getCode())),
                "populated ftrExemption must also suppress the generic advisory");
    }

    @Test
    void underThresholdIntl_noExportRef_noWarning() {
        ManualShipmentRequest req = fullIntlRequest();
        req.setDeclaredValue(new BigDecimal("500.00"));
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalWarnings().stream()
                .noneMatch(w -> ShipmentValidationService.CODE_EXPORT_DECLARATION_RECOMMENDED
                        .equals(w.getCode())),
                "$500 under the $2,500 threshold must not raise the advisory");
    }

    @Test
    void highValueIntl_nonUsd_noWarning() {
        // Non-USD skipped for now — matches the current scope of the US
        // FTR rule (both are USD-scoped until FX plumbing lands).
        ManualShipmentRequest req = fullIntlRequest();
        req.setCurrency("EUR");
        req.setDeclaredValue(new BigDecimal("3000.00"));
        stubServiceAndPreset();

        var res = service.validate(req);

        assertTrue(res.getData().getLocalWarnings().stream()
                .noneMatch(w -> ShipmentValidationService.CODE_EXPORT_DECLARATION_RECOMMENDED
                        .equals(w.getCode())),
                "EUR-declared shipments must not raise the USD-scoped advisory");
    }

    private ManualShipmentRequest fullIntlRequest() {
        ManualShipmentRequest req = fullDomesticRequest();
        req.getRecipient().setCountryCode("GB");
        req.getRecipient().setPostalCode("SW1A 2AA");
        req.getRecipient().setState("");
        req.setCurrency("USD");
        req.setIncoterms("DAP");
        req.setReasonForExport("SALE");
        req.setDeclaredValue(new BigDecimal("100.00"));
        ManualShipmentRequest.Item a = new ManualShipmentRequest.Item();
        a.setDescription("Widget"); a.setHsCode("8471.30");
        a.setCountryOfOrigin("US"); a.setQuantity(2);
        a.setUnitValue(new BigDecimal("50.00"));
        req.setItems(new java.util.ArrayList<>(java.util.List.of(a)));
        return req;
    }

    private ManualShipmentRequest intlReqWithItems(BigDecimal p1, int q1, BigDecimal p2, int q2) {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("5.0"));
        req.setCurrency("USD");
        req.setIncoterms("DAP");
        ManualShipmentRequest.Item a = new ManualShipmentRequest.Item();
        a.setDescription("A"); a.setQuantity(q1); a.setUnitValue(p1);
        ManualShipmentRequest.Item b = new ManualShipmentRequest.Item();
        b.setDescription("B"); b.setQuantity(q2); b.setUnitValue(p2);
        req.setItems(java.util.List.of(a, b));
        return req;
    }

    private ManualShipmentRequest intlReqWithBlankPriceItems(int qty) {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("5.0"));
        req.setCurrency("USD");
        ManualShipmentRequest.Item it = new ManualShipmentRequest.Item();
        it.setDescription("Widget"); it.setQuantity(qty);
        req.setItems(java.util.List.of(it));
        return req;
    }

    @Test
    void buildValidatorCommodities_emptyOrNullItems_returnsEmpty() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setWeight(new BigDecimal("5.0"));
        assertTrue(ShipmentValidationService.buildValidatorCommodities(req).isEmpty());

        req.setItems(java.util.List.of());
        assertTrue(ShipmentValidationService.buildValidatorCommodities(req).isEmpty());
    }

    // ─── Null-body handling ────────────────────────────────────────────

    @Test
    void nullRequest_returnsBadRequest() {
        ApiResponse<ShipmentValidationResult> res = service.validate(null);
        assertEquals(400, res.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), res.getErrorCode());
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private ManualShipmentRequest fullDomesticRequest() {
        ManualShipmentRequest r = new ManualShipmentRequest();
        r.setCarrierCode("FEDEX");
        r.setAccountNumber("ACC1");
        r.setServiceId(1L);
        r.setPackagePresetId(7L);
        r.setWeight(new BigDecimal("2.5"));
        r.setWeightUnit("LB");

        ManualShipmentRequest.Address sender = new ManualShipmentRequest.Address();
        sender.setName("Acme Warehouse");
        sender.setAddressLine1("100 Main St");
        sender.setCity("Denver");
        sender.setState("CO");
        sender.setPostalCode("80202");
        sender.setCountryCode("US");
        sender.setPhone("+15551234567");
        r.setSender(sender);

        ManualShipmentRequest.Address recipient = new ManualShipmentRequest.Address();
        recipient.setName("Jane Doe");
        recipient.setAddressLine1("1 Market St");
        recipient.setCity("San Francisco");
        recipient.setState("CA");
        recipient.setPostalCode("94105");
        recipient.setCountryCode("US");
        recipient.setPhone("+15559876543");
        r.setRecipient(recipient);

        return r;
    }

    private void stubServiceAndPreset() {
        when(shippingServiceRepository.findById(1L)).thenReturn(Optional.of(fedexGround()));
        when(packagePresetRepository.findById(7L)).thenReturn(Optional.of(customBox()));
    }

    private ShippingService fedexGround() {
        return ShippingService.builder()
                .id(1L).carrier("FEDEX").serviceCode("FEDEX_GROUND")
                .name("FedEx Ground").scope("DOMESTIC").originCountry("US").build();
    }

    private PackagePreset customBox() {
        return PackagePreset.builder()
                .id(7L).name("My Box").kind("CUSTOM")
                .ownerType(PackagePreset.OWNER_PLATFORM).build();
    }
}
