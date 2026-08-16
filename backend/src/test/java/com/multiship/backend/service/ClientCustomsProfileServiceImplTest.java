package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the customs-profile
 * service. Owns per-tenant importer/broker records used on international
 * shipment paperwork. A misassigned profile silently leaks the wrong
 * client's tax ID onto a commercial invoice, hence tenant scope is
 * doubly-guarded.
 */
class ClientCustomsProfileServiceImplTest {

    private ClientCustomsProfileRepository repo;
    private ClientRepository clientRepo;
    private ClientCustomsProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientCustomsProfileRepository.class);
        clientRepo = mock(ClientRepository.class);
        service = new ClientCustomsProfileServiceImpl(repo, clientRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void listReturnsClientProfiles() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of(
                ClientCustomsProfile.builder().id(1L).clientCode("ACME").build()));

        ApiResponse<List<ClientCustomsProfileDTO>> resp = service.list("acme");
        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().size());
    }

    @Test
    void listReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        ApiResponse<List<ClientCustomsProfileDTO>> resp = service.list("GHOST");
        assertEquals(404, resp.getCode());
    }

    @Test
    void scopedUserCannotReachForeignClientProfiles() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.list("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.get("OTHER", 1L));
        assertThrows(AccessDeniedException.class, () -> service.delete("OTHER", 1L));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void getForeignProfileIdReturnsNullData() {
        // Cross-tenant id: profile belongs to OTHER but caller passes
        // ACME. The service filters by clientCode match, so the profile
        // is treated as not-found.
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(99L)).thenReturn(Optional.of(
                ClientCustomsProfile.builder().id(99L).clientCode("OTHER").build()));

        ApiResponse<ClientCustomsProfileDTO> resp = service.get("ACME", 99L);
        assertEquals(200, resp.getCode());
        // "No such profile" — treated as absent for the ACME caller,
        // even though the row exists under OTHER's clientCode.
        assertEquals(null, resp.getData());
    }

    private void putScopedUser() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ==================================================================
    // Sprint 53 mapping+importerbroker gap-fill:
    // upsert (create/update/validation/conflict), delete, listPaginated,
    // getStats, exportProfilesCsv.
    // ==================================================================

    private static ClientCustomsProfileDTO dtoIn(Long id, List<String> countries) {
        return ClientCustomsProfileDTO.builder()
                .id(id).clientCode("ACME").countries(countries)
                .importerType("RECEIVER") // RECEIVER (DAP) doesn't require importer name/address
                .build();
    }

    // ================ upsert — create ================

    @Test
    void upsertCreateHappy_persistsProfileWithNormalizedCountries() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        // findByClientCodeIgnoreCase must return the newly-created profile
        // for the same-client uniqueness check (existing profiles for conflict).
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("acme",
                dtoIn(null, List.of("us", "gb"))); // lowercase + more than 2 chars filter

        assertEquals(200, resp.getCode());
        assertNotNull(resp.getData());
        // Message includes count of destinations.
        assertTrue(resp.getMessage().contains("2 destination"),
                "Save message must report the destination count. Got: " + resp.getMessage());
        verify(repo, times(1)).saveAndFlush(any(ClientCustomsProfile.class));
    }

    @Test
    void upsertCreate_emptyCountries_returns422() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(null, List.of())); // empty
        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("at least one destination"));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void upsertCreate_invalidCountryCodes_filteredOut_returns422IfAllStripped() {
        // Non-2-letter codes are dropped by the normalizer; if the resulting
        // set is empty, we hit the "at least one" validation.
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(null, java.util.Arrays.asList("USA", "united kingdom", null)));
        assertEquals(422, resp.getCode());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void upsertCreate_businessType_missingImporterName_returns422() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of());

        ClientCustomsProfileDTO req = ClientCustomsProfileDTO.builder()
                .clientCode("ACME").countries(List.of("US"))
                .importerType("BUSINESS")
                // Missing importerName / address1 / city → validation should fire.
                .build();

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME", req);

        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("business importer"));
        verify(repo, never()).saveAndFlush(any());
    }

    // ================ upsert — update ================

    @Test
    void upsertUpdateHappy_reusesExistingIdAndSaves() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ClientCustomsProfile existing = ClientCustomsProfile.builder()
                .id(42L).clientCode("ACME").build();
        when(repo.findById(42L)).thenReturn(Optional.of(existing));
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of(existing));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(42L, List.of("US")));

        assertEquals(200, resp.getCode());
        // Same id was reused (not re-created).
        ArgumentCaptor<ClientCustomsProfile> captor = ArgumentCaptor.forClass(ClientCustomsProfile.class);
        verify(repo).saveAndFlush(captor.capture());
        assertEquals(42L, captor.getValue().getId());
    }

    @Test
    void upsertUpdate_idNotFound_returns404() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(9999L)).thenReturn(Optional.empty());

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(9999L, List.of("US")));

        assertEquals(404, resp.getCode());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void upsertUpdate_crossClientId_treatedAsNotFound() {
        // Profile id 42 belongs to OTHER; caller says clientCode=ACME →
        // the .filter(x -> x.getClientCode().equalsIgnoreCase(code)) drops it,
        // service returns 404.
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(42L)).thenReturn(Optional.of(
                ClientCustomsProfile.builder().id(42L).clientCode("OTHER").build()));

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(42L, List.of("US")));

        assertEquals(404, resp.getCode(),
                "Cross-client id must NOT be updatable via a different clientCode path.");
    }

    @Test
    void upsert_countryAlreadyCoveredByAnotherProfile_returns409() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        // Another profile for ACME already covers "US".
        ClientCustomsProfile existingUS = ClientCustomsProfile.builder()
                .id(1L).clientCode("ACME").build();
        existingUS.syncCountries(new java.util.LinkedHashSet<>(List.of("US")));
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of(existingUS));

        ApiResponse<ClientCustomsProfileDTO> resp = service.upsert("ACME",
                dtoIn(null, List.of("US")));

        assertEquals(409, resp.getCode());
        assertTrue(resp.getMessage().contains("already covered"),
                "409 message must name the culprit country. Got: " + resp.getMessage());
        verify(repo, never()).saveAndFlush(any());
    }

    // ================ delete ================

    @Test
    void delete_existingProfile_isRemoved() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ClientCustomsProfile p = ClientCustomsProfile.builder().id(3L).clientCode("ACME").build();
        when(repo.findById(3L)).thenReturn(Optional.of(p));

        ApiResponse<Void> resp = service.delete("ACME", 3L);

        assertEquals(200, resp.getCode());
        verify(repo, times(1)).delete(p);
    }

    @Test
    void delete_crossClientId_isNoopNotErrored() {
        // Profile belongs to OTHER; caller ACME. .filter drops it, delete never fires.
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(3L)).thenReturn(Optional.of(
                ClientCustomsProfile.builder().id(3L).clientCode("OTHER").build()));

        ApiResponse<Void> resp = service.delete("ACME", 3L);

        assertEquals(200, resp.getCode()); // success message but no-op
        verify(repo, never()).delete(any(ClientCustomsProfile.class));
    }

    @Test
    void delete_idNotFound_isNoopSuccess() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(9999L)).thenReturn(Optional.empty());

        ApiResponse<Void> resp = service.delete("ACME", 9999L);

        assertEquals(200, resp.getCode());
        verify(repo, never()).delete(any(ClientCustomsProfile.class));
    }

    // ================ listPaginated ================

    @Test
    void listPaginated_returnsPageSliceFromRepo() {
        // Two ACME profiles + one OTHER profile in the DB.
        Client client = new Client();
        client.setClientCode("ACME");
        client.setName("Acme Corp");
        when(clientRepo.findAll()).thenReturn(List.of(client));
        ClientCustomsProfile p1 = ClientCustomsProfile.builder().id(1L).clientCode("ACME").build();
        ClientCustomsProfile p2 = ClientCustomsProfile.builder().id(2L).clientCode("ACME").build();
        when(repo.findAll()).thenReturn(List.of(p1, p2));

        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .page(0).size(50).sortBy("client").sortDirection("ASC").build();

        ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> resp = service.listPaginated(filters);

        assertEquals(200, resp.getCode());
        assertEquals(2, resp.getData().getContent().size());
        assertEquals(2L, resp.getData().getTotalElements());
    }

    // ================ getStats ================

    @Test
    void getStats_platformOperator_returnsRepoCounts() {
        // No tenant scope → platform operator path uses repository count queries.
        when(repo.count()).thenReturn(12L);
        when(repo.countDistinctCountries()).thenReturn(34L);
        when(repo.countDistinctClientCodes()).thenReturn(5L);

        ApiResponse<Map<String, Long>> resp = service.getStats();

        assertEquals(200, resp.getCode());
        assertEquals(12L, resp.getData().get("profiles"));
        assertEquals(34L, resp.getData().get("destinationsCovered"));
        assertEquals(5L, resp.getData().get("clientsConfigured"));
    }

    @Test
    void getStats_scopedUser_returnsTenantSlice_clientsConfiguredCollapsesToOneOrZero() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        // 3 profiles for ACME covering {US, GB, DE}.
        ClientCustomsProfile p1 = ClientCustomsProfile.builder().id(1L).clientCode("ACME").build();
        p1.syncCountries(new java.util.LinkedHashSet<>(List.of("US", "GB")));
        ClientCustomsProfile p2 = ClientCustomsProfile.builder().id(2L).clientCode("ACME").build();
        p2.syncCountries(new java.util.LinkedHashSet<>(List.of("DE")));
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of(p1, p2));

        ApiResponse<Map<String, Long>> resp = service.getStats();

        assertEquals(200, resp.getCode());
        assertEquals(2L, resp.getData().get("profiles"));
        assertEquals(3L, resp.getData().get("destinationsCovered"));
        // Scoped USER: clientsConfigured collapses to 1 (their own has profiles).
        assertEquals(1L, resp.getData().get("clientsConfigured"));
    }

    // ================ exportProfilesCsv ================

    @Test
    void exportProfilesCsv_returnsCsvWithHeaderAndOneRowPerProfile() {
        when(clientRepo.findAll()).thenReturn(List.of());
        ClientCustomsProfile p = ClientCustomsProfile.builder().id(1L).clientCode("ACME").build();
        p.syncCountries(new java.util.LinkedHashSet<>(List.of("US")));
        when(repo.findAll()).thenReturn(List.of(p));

        CustomsProfileFilters filters = CustomsProfileFilters.builder()
                .page(0).size(200).sortBy("client").sortDirection("ASC").build();

        String csv = service.exportProfilesCsv(filters);

        // Header (12 columns) present.
        assertTrue(csv.startsWith("Client code,Client name,Countries,Importer type,"),
                "CSV must start with the pinned header. Got: " + csv.substring(0, Math.min(80, csv.length())));
        // One row for ACME with "US" as the country cell.
        assertTrue(csv.contains("ACME"), "CSV must include the ACME row.");
        assertTrue(csv.contains("US"), "CSV must include the US country.");
        // CRLF line endings per RFC 4180.
        assertTrue(csv.contains("\r\n"), "CSV must use CRLF line endings.");
    }
}
