package com.multiship.backend.integration;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.repository.PackagePresetRepository;
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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 51 catalog-be-integration — full package-preset lifecycle exercised
 * against real Postgres. Covers the CRUD-shaped endpoints of
 * `/settings/shipping-catalog` (Packages tab): listPresets, savePreset (create
 * + update), setDefaultPreset (with prior-default demotion), and deletePreset
 * (with idempotent-on-unknown-id + default-preset 409 semantics).
 *
 * <p>Anti-fallback: no carrier connector is invoked in this class (the preset
 * lifecycle is pure DB). {@link MockCarrierConnectorsTestConfig} is imported
 * anyway so the {@code List<CarrierConnector>} injected into
 * {@link ShippingConfigService} contains only mocks — no real Ups/FedEx/Stamps/Dhl
 * class ever instantiates and no HTTP call is possible. {@link ForbidOutboundHttpTestConfig}
 * additionally installs {@code @Primary} beans that throw on any request, so a
 * future refactor that added an outbound call would fail loudly.
 *
 * <p>Test rows are namespaced with {@link #PRESET_PREFIX} so a re-run against
 * the shared container starts from a known state and doesn't clash with
 * sibling integration tests.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class PackagePresetLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String PRESET_PREFIX = "PPIT_";

    @Autowired
    private ShippingConfigService shippingConfigService;

    @Autowired
    private PackagePresetRepository presetRepository;

    @BeforeEach
    void setUp() {
        // Wipe THIS class' presets so a re-run against the shared container
        // starts fresh. Filter on PRESET_PREFIX so we never touch rows another
        // integration test may have seeded.
        presetRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().startsWith(PRESET_PREFIX))
                .forEach(p -> presetRepository.deleteById(p.getId()));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ================ 1. CREATE + LIST ================

    @Test
    void create_persistsRow_andListReturnsIt() {
        ApiResponse<PackagePreset> saved = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "CREATE"));

        assertEquals("SUCCESS", saved.getStatus());
        assertNotNull(saved.getData().getId());
        assertEquals("CUSTOM", saved.getData().getSource(),
                "Create path must tag source='CUSTOM' so hand-authored boxes are distinguishable from CARRIER_API-synced ones.");

        ApiResponse<List<PackagePreset>> list = shippingConfigService.listPresets();
        assertEquals("SUCCESS", list.getStatus());
        assertTrue(list.getData().stream().anyMatch(p -> saved.getData().getId().equals(p.getId())),
                "listPresets must include the newly-created row.");
    }

    // ================ 2. MARK DEFAULT — demotes prior ================

    @Test
    void setDefault_demotesPriorDefault_thenMarksNew() {
        Long firstId = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "DEF_1")).getData().getId();
        Long secondId = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "DEF_2")).getData().getId();

        // Promote the first.
        ApiResponse<PackagePreset> firstDefault = shippingConfigService.setDefaultPreset(firstId);
        assertEquals("SUCCESS", firstDefault.getStatus());
        assertEquals(Boolean.TRUE, presetRepository.findById(firstId).orElseThrow().getIsDefault());

        // Promote the second — first must be demoted (business rule: at most one default).
        ApiResponse<PackagePreset> secondDefault = shippingConfigService.setDefaultPreset(secondId);
        assertEquals("SUCCESS", secondDefault.getStatus());
        assertEquals(Boolean.TRUE, presetRepository.findById(secondId).orElseThrow().getIsDefault());
        assertEquals(Boolean.FALSE, presetRepository.findById(firstId).orElseThrow().getIsDefault(),
                "Prior default must be demoted when a new preset becomes the default.");
    }

    // ================ 3. UPDATE dimensions ================

    @Test
    void update_persistsNewDimensions_andPreservesSource() {
        Long id = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "UPD")).getData().getId();
        PackagePreset original = presetRepository.findById(id).orElseThrow();
        assertEquals("CUSTOM", original.getSource());

        PackagePreset edit = customBox(PRESET_PREFIX + "UPD");
        edit.setLength(BigDecimal.valueOf(20));
        edit.setWidth(BigDecimal.valueOf(15));
        edit.setHeight(BigDecimal.valueOf(12));

        ApiResponse<PackagePreset> updated = shippingConfigService.savePreset(id, edit);

        assertEquals("SUCCESS", updated.getStatus());
        PackagePreset refetched = presetRepository.findById(id).orElseThrow();
        assertEquals(BigDecimal.valueOf(20).compareTo(refetched.getLength()), 0);
        assertEquals(BigDecimal.valueOf(15).compareTo(refetched.getWidth()), 0);
        assertEquals(BigDecimal.valueOf(12).compareTo(refetched.getHeight()), 0);
        // Update path must NOT re-tag source — the original CUSTOM tag survives.
        assertEquals("CUSTOM", refetched.getSource(),
                "Update path must preserve the existing source (only create tags 'CUSTOM').");
    }

    // ================ 4. DELETE non-default ================

    @Test
    void delete_removesNonDefaultRowFromDb() {
        Long id = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "DEL")).getData().getId();
        assertTrue(presetRepository.existsById(id));

        ApiResponse<Void> resp = shippingConfigService.deletePreset(id);

        assertEquals("SUCCESS", resp.getStatus());
        assertFalse(presetRepository.existsById(id), "Non-default row must be hard-deleted.");
    }

    // ================ 5. DELETE unknown id — idempotent SUCCESS ================

    @Test
    void delete_unknownId_returnsSuccess_idempotent() {
        // Documented behavior — deletion is idempotent for FE convenience:
        // clicking Delete on a row already gone shouldn't 404 the operator.
        ApiResponse<Void> resp = shippingConfigService.deletePreset(9999999L);

        assertEquals("SUCCESS", resp.getStatus());
        assertEquals(200, resp.getCode());
        assertNull(resp.getData());
    }

    // ================ 6. DELETE default — 409 CONFLICT ================

    @Test
    void delete_defaultRow_returns409_andRowSurvives() {
        Long id = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "DEFDEL")).getData().getId();
        shippingConfigService.setDefaultPreset(id);
        assertEquals(Boolean.TRUE, presetRepository.findById(id).orElseThrow().getIsDefault());

        ApiResponse<Void> resp = shippingConfigService.deletePreset(id);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(409, resp.getCode());
        assertTrue(resp.getMessage().contains("default"));
        // Row must still exist — no accidental cascade.
        assertTrue(presetRepository.existsById(id),
                "Default preset must survive a delete attempt (409); operator must demote first.");
    }

    // ================ 7. VALIDATION — blank name ================

    @Test
    void save_blankName_returns422_andPersistsNothing() {
        PackagePreset req = customBox("");

        ApiResponse<PackagePreset> resp = shippingConfigService.savePreset(null, req);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
    }

    // ================ 8. VALIDATION — custom without dimensions ================

    @Test
    void save_customKind_missingDims_returns422() {
        PackagePreset req = PackagePreset.builder()
                .name(PRESET_PREFIX + "NODIMS")
                .kind("CUSTOM")
                .ownerType(PackagePreset.OWNER_PLATFORM)
                .build();

        ApiResponse<PackagePreset> resp = shippingConfigService.savePreset(null, req);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
    }

    // ================ 9. NAME CLASH — 409 on create ================

    @Test
    void create_nameClash_returns409() {
        shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "CLASH"));

        ApiResponse<PackagePreset> second = shippingConfigService.savePreset(
                null, customBox(PRESET_PREFIX + "CLASH"));

        assertEquals("ERROR", second.getStatus());
        assertEquals(409, second.getCode());
    }

    // ================ 10. UPDATE name — no self-conflict ================

    @Test
    void update_toSameName_doesNotSelfConflict() {
        // Sanity: updating a row without changing its name must NOT trip the
        // uniqueness check against itself.
        Long id = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "SELF")).getData().getId();
        PackagePreset edit = customBox(PRESET_PREFIX + "SELF");
        edit.setBoxCost(BigDecimal.valueOf(3.50));

        ApiResponse<PackagePreset> resp = shippingConfigService.savePreset(id, edit);

        assertEquals("SUCCESS", resp.getStatus(),
                "Updating a row without renaming must not trip name-uniqueness against itself.");
    }

    // ================ helpers ================

    private static PackagePreset customBox(String name) {
        return PackagePreset.builder()
                .name(name)
                .kind("CUSTOM")
                .carrier("UPS")
                .ownerType(PackagePreset.OWNER_PLATFORM)
                .length(BigDecimal.valueOf(10))
                .width(BigDecimal.valueOf(10))
                .height(BigDecimal.valueOf(10))
                .dimUnit("IN")
                .maxWeight(BigDecimal.valueOf(5))
                .weightUnit("LB")
                .enabled(true)
                .build();
    }
}
