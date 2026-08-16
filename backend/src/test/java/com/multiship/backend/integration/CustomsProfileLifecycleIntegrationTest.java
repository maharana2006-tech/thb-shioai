package com.multiship.backend.integration;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.Client;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.service.ClientCustomsProfileService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 importerbroker-be-integration — full customs-profile lifecycle
 * against real Postgres via Testcontainers. Covers
 * `/settings/importer-broker`'s write + read + export paths end-to-end.
 *
 * <p>Anti-fallback: reuses {@link MockCarrierConnectorsTestConfig} +
 * {@link ForbidOutboundHttpTestConfig} from PR #215 so no carrier IO is
 * possible. Customs-profile CRUD is pure DB (no HTTP path), but importing
 * the guard belts + suspenders means a future refactor that adds outbound
 * calls fails loudly.
 *
 * <p>Rows namespaced with {@link #CODE} + {@link #PREFIX} so the shared
 * Testcontainers DB stays clean across re-runs and sibling ITs.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class CustomsProfileLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String CODE = "CBPIT";
    private static final String PREFIX = "CBPIT_";

    @Autowired
    private ClientCustomsProfileService service;
    @Autowired
    private ClientCustomsProfileRepository repo;
    @Autowired
    private ClientRepository clientRepo;

    @BeforeEach
    void setUp() {
        // Wipe this class' profiles + client so re-runs start fresh.
        repo.findAll().stream()
                .filter(p -> p.getClientCode() != null && p.getClientCode().startsWith(CODE))
                .forEach(p -> service.delete(p.getClientCode(), p.getId()));
        clientRepo.findAll().stream()
                .filter(c -> c.getClientCode() != null && c.getClientCode().startsWith(CODE))
                .forEach(c -> clientRepo.deleteById(c.getId()));

        // Seed the client that all this class's profiles belong to.
        Client client = new Client();
        client.setClientCode(CODE);
        client.setName("Customs BP IT");
        clientRepo.save(client);

        // Platform ADMIN so TenantScopeEnforcer is pass-through.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ================ helpers ================

    private ClientCustomsProfileDTO receiverProfile(List<String> countries) {
        return ClientCustomsProfileDTO.builder()
                .clientCode(CODE).countries(countries)
                .importerType("RECEIVER") // no importer name required for DAP
                .build();
    }

    private ClientCustomsProfileDTO businessProfile(List<String> countries, String name) {
        return ClientCustomsProfileDTO.builder()
                .clientCode(CODE).countries(countries)
                .importerType("BUSINESS")
                .importerName(name)
                .importerAddress1("1 Main St")
                .importerCity("Springfield")
                .importerCountry("US")
                .build();
    }

    // ================ 1. CREATE ================

    @Test
    void create_persistsAndListReturnsIt() {
        ApiResponse<ClientCustomsProfileDTO> saved = service.upsert(CODE, receiverProfile(List.of("US", "GB")));

        assertEquals(200, saved.getCode());
        assertNotNull(saved.getData().getId());

        ApiResponse<List<ClientCustomsProfileDTO>> list = service.list(CODE);
        assertEquals(200, list.getCode());
        assertTrue(list.getData().stream().anyMatch(p -> saved.getData().getId().equals(p.getId())),
                "list must include the newly-created profile.");
    }

    // ================ 2. UPDATE ================

    @Test
    void update_persistsChangesAndPreservesId() {
        Long id = service.upsert(CODE, receiverProfile(List.of("US"))).getData().getId();

        ClientCustomsProfileDTO editReq = businessProfile(List.of("US"), "Acme LLC");
        editReq.setId(id);
        ApiResponse<ClientCustomsProfileDTO> updated = service.upsert(CODE, editReq);

        assertEquals(200, updated.getCode());
        assertEquals(id, updated.getData().getId(), "Update must reuse the same id.");
        assertEquals("Acme LLC", repo.findById(id).orElseThrow().getImporterName());
    }

    // ================ 3. VALIDATION ================

    @Test
    void create_emptyCountries_returns422() {
        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert(CODE, receiverProfile(List.of()));
        assertEquals(422, resp.getCode());
    }

    @Test
    void create_businessType_missingName_returns422() {
        // BUSINESS type requires name + address1 + city.
        ClientCustomsProfileDTO req = ClientCustomsProfileDTO.builder()
                .clientCode(CODE).countries(List.of("US"))
                .importerType("BUSINESS")
                // Missing importerName / address1 / city
                .build();

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert(CODE, req);

        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("business importer"));
    }

    // ================ 4. CONFLICT ================

    @Test
    void create_countryAlreadyCovered_returns409() {
        service.upsert(CODE, receiverProfile(List.of("US"))); // first profile owns US
        ApiResponse<ClientCustomsProfileDTO> second = service.upsert(CODE, receiverProfile(List.of("US")));

        assertEquals(409, second.getCode());
        assertTrue(second.getMessage().contains("already covered"));
    }

    // ================ 5. DELETE ================

    @Test
    void delete_removesRowFromDb() {
        Long id = service.upsert(CODE, receiverProfile(List.of("US"))).getData().getId();
        assertTrue(repo.existsById(id));

        ApiResponse<Void> resp = service.delete(CODE, id);

        assertEquals(200, resp.getCode());
        assertFalse(repo.existsById(id), "Row must be hard-deleted.");
    }

    @Test
    void delete_unknownId_returnsSuccess_noop() {
        ApiResponse<Void> resp = service.delete(CODE, 9999999L);
        assertEquals(200, resp.getCode());
    }

    // ================ 6. UNKNOWN CLIENT ================

    @Test
    void upsert_unknownClient_returns404() {
        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("NOSUCH",
                receiverProfile(List.of("US")));
        assertEquals(404, resp.getCode());
    }

    @Test
    void list_unknownClient_returns404() {
        ApiResponse<List<ClientCustomsProfileDTO>> resp = service.list("NOSUCH");
        assertEquals(404, resp.getCode());
    }

    // ================ 7. STATS ================

    @Test
    void stats_platformOperator_returnsCounts() {
        service.upsert(CODE, receiverProfile(List.of("US", "GB")));
        service.upsert(CODE, receiverProfile(List.of("DE")));

        ApiResponse<Map<String, Long>> resp = service.getStats();

        assertEquals(200, resp.getCode());
        // Global stats: this class's 2 profiles + 3 destinations contribute
        // to the platform-wide count (sibling ITs may have added rows too,
        // so >= 2 / >= 3 rather than exact).
        assertTrue(resp.getData().get("profiles") >= 2L);
        assertTrue(resp.getData().get("destinationsCovered") >= 3L);
        assertTrue(resp.getData().get("clientsConfigured") >= 1L);
    }

    // ================ 8. LIST PAGINATED + FILTERS ================

    @Test
    void listPaginated_narrowsByClientCode() {
        service.upsert(CODE, receiverProfile(List.of("US", "GB")));

        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .clientCode(CODE).page(0).size(50).sortBy("client").sortDirection("ASC").build();

        ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> resp = service.listPaginated(filters);

        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().getContent().size());
        assertEquals(CODE, resp.getData().getContent().get(0).getClientCode());
    }

    // ================ 9. CSV EXPORT ================

    @Test
    void exportProfilesCsv_includesTheProfileRow() {
        service.upsert(CODE, businessProfile(List.of("US"), "Acme LLC"));

        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .clientCode(CODE).page(0).size(200).sortBy("client").sortDirection("ASC").build();

        String csv = service.exportProfilesCsv(filters);

        assertTrue(csv.startsWith("Client code,Client name,Countries"),
                "CSV must start with the pinned header. Got: " + csv.substring(0, Math.min(80, csv.length())));
        assertTrue(csv.contains(CODE), "CSV must include the CODE row.");
        assertTrue(csv.contains("Acme LLC"), "CSV must include the importer name.");
        assertTrue(csv.contains("US"), "CSV must include the country.");
    }
}
