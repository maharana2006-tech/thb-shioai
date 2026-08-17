package com.multiship.backend.integration;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ShipMethodRulePackageRepository;
import com.multiship.backend.repository.ShipMethodRuleWarehouseRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.ShippingConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 mapping-be-integration — ship-method-rule lifecycle exercised
 * against real Postgres. Covers `/settings/shipping-service-mapping`'s
 * write path (upsertRule + deleteRule) AND read path (resolveRule
 * specificity scoring) end-to-end.
 *
 * <p>Anti-fallback: no carrier connector is invoked here — ship-method-rule
 * CRUD + resolution is pure DB. {@link MockCarrierConnectorsTestConfig} is
 * imported so the {@code List<CarrierConnector>} injected into
 * {@link ShippingConfigService} contains only mocks. {@link
 * ForbidOutboundHttpTestConfig} installs @Primary beans that throw on any
 * request — future outbound calls fail loudly.
 *
 * <p>Rows are namespaced with {@link #PREFIX} so the shared Testcontainers
 * database stays clean across re-runs and sibling ITs.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ShipMethodRuleLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String PREFIX = "SMRIT_";

    @Autowired
    private ShippingConfigService shippingConfigService;

    @Autowired
    private ShippingServiceRepository serviceRepository;
    @Autowired
    private ShipViaMappingRepository ruleRepository;
    @Autowired
    private ShipMethodRulePackageRepository rulePackageRepository;
    @Autowired
    private ShipMethodRuleWarehouseRepository ruleWarehouseRepository;

    /** Two services this class seeds; ids captured in setUp. */
    private Long serviceIdUpsGround;
    private Long serviceIdUpsExpress;

    @BeforeEach
    void setUp() {
        // Wipe this class' rows so re-runs against the shared container start
        // fresh. Filter on our prefix so sibling ITs' rows aren't touched.
        // Use deleteRule() (wraps @Transactional) so the cascade to the join
        // tables happens in an active transaction — @Modifying bulk queries
        // (deleteAllByRuleId) can't run outside one.
        ruleRepository.findAll().stream()
                .filter(r -> r.getShipviaCd() != null && r.getShipviaCd().startsWith(PREFIX))
                .map(ShipViaMapping::getId)
                .forEach(shippingConfigService::deleteRule);
        serviceRepository.findAll().stream()
                .filter(s -> s.getServiceCode() != null && s.getServiceCode().startsWith(PREFIX))
                .forEach(s -> serviceRepository.deleteById(s.getId()));

        // Seed two services this class' rules point at.
        ShippingService ground = serviceRepository.save(ShippingService.builder()
                .carrier("UPS").serviceCode(PREFIX + "GROUND").name("UPS Ground (IT)")
                .originCountry("US").scope("DOMESTIC").enabled(true).sortOrder(0).build());
        ShippingService express = serviceRepository.save(ShippingService.builder()
                .carrier("UPS").serviceCode(PREFIX + "EXPRESS").name("UPS Express (IT)")
                .originCountry("US").scope("BOTH").enabled(true).sortOrder(1).build());
        serviceIdUpsGround = ground.getId();
        serviceIdUpsExpress = express.getId();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ================ 1. CREATE ================

    @Test
    void create_persistsRuleWithNormalizedFields() {
        ApiResponse<ShipViaMapping> saved = shippingConfigService.upsertRule(
                null, PREFIX + "gnd", "c001", "COUNTRY", "us",
                serviceIdUpsGround, List.of(), List.of());

        assertEquals("SUCCESS", saved.getStatus());
        assertNotNull(saved.getData().getId());
        // shipviaCd is normalized to UPPER (via norm()); clientCode too;
        // COUNTRY destValue uppercased.
        ShipViaMapping row = ruleRepository.findById(saved.getData().getId()).orElseThrow();
        assertEquals((PREFIX + "gnd").trim().toUpperCase(), row.getShipviaCd());
        assertEquals("C001", row.getClientCode());
        assertEquals("US", row.getDestValue());
        assertEquals("COUNTRY", row.getDestType());
        assertEquals(serviceIdUpsGround, row.getServiceId());
    }

    // ================ 2. RESOLVE (specificity) ================

    @Test
    void resolve_picksHigherSpecificityRule() {
        // Two rules on the same shipviaCd: global (score 0) vs client+country (score 10).
        shippingConfigService.upsertRule(null, PREFIX + "SPEC", null, "ANY", null,
                serviceIdUpsGround, List.of(), List.of());
        shippingConfigService.upsertRule(null, PREFIX + "SPEC", "C001", "COUNTRY", "US",
                serviceIdUpsExpress, List.of(), List.of());

        Optional<ShippingService> resolved = shippingConfigService.resolveRule("C001", PREFIX + "SPEC", "US");

        assertTrue(resolved.isPresent());
        // Client+country wins → resolves to EXPRESS service.
        assertEquals(serviceIdUpsExpress, resolved.get().getId(),
                "client+country (10) must beat global (0) at resolution time.");
    }

    @Test
    void resolve_disabledServiceOnWinningRule_returnsEmpty() {
        // Rule resolves to Ground, then Ground is disabled → resolve() returns empty.
        shippingConfigService.upsertRule(null, PREFIX + "DISABLED", null, "ANY", null,
                serviceIdUpsGround, List.of(), List.of());
        // Disable via the service (persists to DB).
        shippingConfigService.setServiceEnabled(serviceIdUpsGround, false);

        Optional<ShippingService> resolved = shippingConfigService.resolveRule(null, PREFIX + "DISABLED", "US");

        assertTrue(resolved.isEmpty(),
                "Disabled winning-service must not be returned even if the rule matched.");
    }

    // ================ 3. UPDATE ================

    @Test
    void update_persistsChangedDestination_andPreservesId() {
        ApiResponse<ShipViaMapping> saved = shippingConfigService.upsertRule(
                null, PREFIX + "UPDATE", "C001", "COUNTRY", "US",
                serviceIdUpsGround, List.of(), List.of());
        Long id = saved.getData().getId();

        // Update: same id, dest flips to CA.
        ApiResponse<ShipViaMapping> updated = shippingConfigService.upsertRule(
                id, PREFIX + "UPDATE", "C001", "COUNTRY", "CA",
                serviceIdUpsGround, List.of(), List.of());

        assertEquals("SUCCESS", updated.getStatus());
        assertEquals(id, updated.getData().getId(),
                "Update path must reuse the same id, not allocate a new one.");
        ShipViaMapping refetched = ruleRepository.findById(id).orElseThrow();
        assertEquals("CA", refetched.getDestValue());
    }

    // ================ 4. DELETE ================

    @Test
    void delete_removesRuleFromDb() {
        ApiResponse<ShipViaMapping> saved = shippingConfigService.upsertRule(
                null, PREFIX + "DELETE", null, "ANY", null,
                serviceIdUpsGround, List.of(), List.of());
        Long id = saved.getData().getId();
        assertTrue(ruleRepository.existsById(id));

        ApiResponse<Void> resp = shippingConfigService.deleteRule(id);

        assertEquals("SUCCESS", resp.getStatus());
        assertFalse(ruleRepository.existsById(id), "Rule row must be hard-deleted.");
    }

    @Test
    void delete_unknownId_returnsSuccess_idempotent() {
        // Documented: deleteRule on unknown id is a no-op that still returns SUCCESS
        // (matches deletePreset semantics for FE convenience).
        ApiResponse<Void> resp = shippingConfigService.deleteRule(9999999L);

        assertEquals("SUCCESS", resp.getStatus());
    }

    // ================ 5. CONFLICT / UNIQUENESS ================

    @Test
    void create_duplicateCodeClientDest_returns409() {
        // First rule: OK.
        shippingConfigService.upsertRule(null, PREFIX + "DUP", "C001", "COUNTRY", "US",
                serviceIdUpsGround, List.of(), List.of());

        // Duplicate (same code + client + destination) → 409.
        ApiResponse<ShipViaMapping> dup = shippingConfigService.upsertRule(
                null, PREFIX + "DUP", "C001", "COUNTRY", "US",
                serviceIdUpsExpress, List.of(), List.of());

        assertEquals("ERROR", dup.getStatus());
        assertEquals(409, dup.getCode());
        assertTrue(dup.getMessage().contains("already exists"),
                "409 message must explain the collision to the operator.");
    }

    // ================ 6. VALIDATION ================

    @Test
    void create_blankShipviaCd_returns422() {
        ApiResponse<ShipViaMapping> resp = shippingConfigService.upsertRule(
                null, "", "C001", "COUNTRY", "US",
                serviceIdUpsGround, List.of(), List.of());

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
    }

    @Test
    void create_unknownService_returns422() {
        ApiResponse<ShipViaMapping> resp = shippingConfigService.upsertRule(
                null, PREFIX + "BADSVC", null, "ANY", null,
                9999999L, List.of(), List.of());

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("carrier service"));
    }

    // ================ 7. ZONE (COUNTRIES) ================

    @Test
    void create_countriesZone_normalizesToUppercaseSortedDedupedSpaceSeparated() {
        // Input: "gb, us, de us fr" (lower, comma+space, dupe US).
        // Expected normalized: "DE FR GB US" (uppercase, deduped, sorted, space-sep).
        ApiResponse<ShipViaMapping> saved = shippingConfigService.upsertRule(
                null, PREFIX + "ZONE", null, "COUNTRIES", "gb, us, de us fr",
                serviceIdUpsGround, List.of(), List.of());

        assertEquals("SUCCESS", saved.getStatus());
        ShipViaMapping row = ruleRepository.findById(saved.getData().getId()).orElseThrow();
        assertEquals("DE FR GB US", row.getDestValue(),
                "COUNTRIES value must be uppercased, deduped, sorted, space-separated.");
    }

    // ================ 8. LIST via catalog ================

    @Test
    void catalog_includesTheNewRule() {
        shippingConfigService.upsertRule(null, PREFIX + "INCAT", null, "ANY", null,
                serviceIdUpsGround, List.of(), List.of());

        ApiResponse<java.util.Map<String, Object>> catalog = shippingConfigService.catalog(null);

        assertEquals("SUCCESS", catalog.getStatus());
        @SuppressWarnings("unchecked")
        List<ShipViaMapping> rules = (List<ShipViaMapping>) catalog.getData().get("rules");
        assertTrue(rules.stream().anyMatch(r -> (PREFIX + "INCAT").equals(r.getShipviaCd())),
                "catalog.rules must include the newly-saved rule.");
    }
}
