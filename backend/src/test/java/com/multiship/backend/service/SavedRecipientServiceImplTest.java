package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.SavedRecipientDTO;
import com.multiship.backend.model.SavedRecipient;
import com.multiship.backend.repository.SavedRecipientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SavedRecipientServiceImpl}. Mocks the repository
 * so no real DB is required.
 */
class SavedRecipientServiceImplTest {

    private SavedRecipientRepository repo;
    private SavedRecipientServiceImpl service;

    private final java.util.Map<Long, SavedRecipient> saved = new java.util.HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        repo = mock(SavedRecipientRepository.class);

        doAnswer(inv -> {
            SavedRecipient r = inv.getArgument(0);
            if (r.getId() == null) r.setId(seq.getAndIncrement());
            saved.put(r.getId(), r);
            return r;
        }).when(repo).save(any(SavedRecipient.class));
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<Long>getArgument(0))));
        when(repo.existsById(anyLong()))
                .thenAnswer(inv -> saved.containsKey(inv.<Long>getArgument(0)));
        doAnswer(inv -> {
            saved.remove(inv.<Long>getArgument(0));
            return null;
        }).when(repo).deleteById(anyLong());
        // findExisting default: no dupe.
        when(repo.findExisting(anyString(), any())).thenReturn(Optional.empty());

        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pass-through.
        service = new SavedRecipientServiceImpl(repo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    private static SavedRecipientDTO acmeRequest() {
        return SavedRecipientDTO.builder()
                .name("Acme Warehouse")
                .company("Acme Ltd")
                .phone("5551234567")
                .addressLine1("1 Warehouse Way")
                .city("Louisville")
                .state("KY")
                .postalCode("40209")
                .countryCode("US")
                .residential(false)
                .build();
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotSearchForForeignTenant() {
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            SavedRecipientServiceImpl scopedService = new SavedRecipientServiceImpl(repo,
                    new TenantScopeEnforcer(new AccessScopePolicy(true)));

            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.search("acme", "OTHER"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /* -------------------------- Validation -------------------------- */

    @Test
    void createRejectsMissingName() {
        SavedRecipientDTO r = acmeRequest();
        r.setName(null);
        ApiResponse<SavedRecipientDTO> resp = service.create(r);
        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().toLowerCase().contains("name"));
    }

    @Test
    void createRejectsMissingAddressLine1() {
        SavedRecipientDTO r = acmeRequest();
        r.setAddressLine1(null);
        assertEquals(400, service.create(r).getCode());
    }

    @Test
    void createRejectsMissingCityPostalCountry() {
        SavedRecipientDTO r = acmeRequest();
        r.setCity(null);
        assertEquals(400, service.create(r).getCode());
        r.setCity("Louisville");
        r.setPostalCode(null);
        assertEquals(400, service.create(r).getCode());
        r.setPostalCode("40209");
        r.setCountryCode(null);
        assertEquals(400, service.create(r).getCode());
    }

    @Test
    void createRejectsNullRequest() {
        assertEquals(400, service.create(null).getCode());
    }

    /* -------------------------- Create + Dedup -------------------------- */

    @Test
    void createReturnsPersistedRow() {
        ApiResponse<SavedRecipientDTO> resp = service.create(acmeRequest());
        assertEquals("success", resp.getStatus());
        assertNotNull(resp.getData());
        assertNotNull(resp.getData().getId());
        assertEquals("Acme Warehouse", resp.getData().getName());
        assertNotNull(resp.getData().getCreatedAt());
        assertNotNull(resp.getData().getUpdatedAt());
    }

    @Test
    void createIsIdempotentWhenDuplicateExists() {
        // Prime the dupe check with an existing row.
        SavedRecipient existing = new SavedRecipient();
        existing.setId(42L);
        existing.setName("Acme Warehouse");
        existing.setAddressLine1("1 Warehouse Way");
        existing.setPostalCode("40209");
        when(repo.findExisting(anyString(), any())).thenReturn(Optional.of(existing));

        ApiResponse<SavedRecipientDTO> resp = service.create(acmeRequest());
        assertEquals("success", resp.getStatus());
        assertEquals(42L, resp.getData().getId(),
                "Duplicate create should return the existing row's id");
        assertTrue(resp.getMessage().toLowerCase().contains("already"));
    }

    @Test
    void createNormalisesCountryCodeToUppercase() {
        SavedRecipientDTO r = acmeRequest();
        r.setCountryCode("us");
        assertEquals("US", service.create(r).getData().getCountryCode());
    }

    /* -------------------------- Dedup hash properties -------------------------- */

    @Test
    void dedupHashIsCaseInsensitiveAndWhitespaceTolerant() {
        String a = SavedRecipientServiceImpl.dedupHash("Acme", "1 Way", "40209");
        String b = SavedRecipientServiceImpl.dedupHash(" acme ", " 1 way ", "40209");
        String c = SavedRecipientServiceImpl.dedupHash("ACME", "1 WAY", "40209");
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void dedupHashDiffersByAddressOrPostal() {
        String base = SavedRecipientServiceImpl.dedupHash("Acme", "1 Way", "40209");
        assertNotEquals(base,
                SavedRecipientServiceImpl.dedupHash("Acme", "2 Way", "40209"));
        assertNotEquals(base,
                SavedRecipientServiceImpl.dedupHash("Acme", "1 Way", "40210"));
    }

    /* -------------------------- Update + Delete -------------------------- */

    @Test
    void updateReturnsUpdatedRow() {
        // Create then update.
        ApiResponse<SavedRecipientDTO> created = service.create(acmeRequest());
        Long id = created.getData().getId();

        SavedRecipientDTO patch = acmeRequest();
        patch.setName("Acme Returns Depot");
        patch.setCity("Cincinnati");
        patch.setPostalCode("45202");
        ApiResponse<SavedRecipientDTO> resp = service.update(id, patch);
        assertEquals("success", resp.getStatus());
        assertEquals("Acme Returns Depot", resp.getData().getName());
        assertEquals("Cincinnati", resp.getData().getCity());
    }

    @Test
    void updateReturns404ForUnknownRow() {
        assertEquals(404, service.update(9999L, acmeRequest()).getCode());
    }

    @Test
    void deleteRemovesRow() {
        ApiResponse<SavedRecipientDTO> created = service.create(acmeRequest());
        Long id = created.getData().getId();
        ApiResponse<Void> resp = service.delete(id);
        assertEquals("success", resp.getStatus());
        assertFalse(saved.containsKey(id));
    }

    @Test
    void deleteReturns404ForUnknownRow() {
        assertEquals(404, service.delete(9999L).getCode());
    }

    /* -------------------------- Search -------------------------- */

    @Test
    void searchDelegatesToRepositoryWithOwner() {
        SavedRecipient acme = new SavedRecipient();
        acme.setId(1L); acme.setName("Acme Warehouse"); acme.setOwnerCustomerNo("C001");
        acme.setAddressLine1("1 Way"); acme.setCity("L"); acme.setPostalCode("40209");
        acme.setCountryCode("US");
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(acme)));

        ApiResponse<List<SavedRecipientDTO>> resp = service.search("acme", "C001");
        assertEquals(1, resp.getData().size());
        assertEquals("Acme Warehouse", resp.getData().get(0).getName());
    }

    @Test
    void searchNullQueryTreatedAsEmpty() {
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.<SavedRecipient>of()));
        ApiResponse<List<SavedRecipientDTO>> resp = service.search(null, null);
        assertEquals("success", resp.getStatus());
        assertNotNull(resp.getData());
    }

    @Test
    void searchAsksTheDatabaseForAtMost25NewestFirst() {
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.<SavedRecipient>of()));
        service.search("wacker chicago", null);
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> paging =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(repo).findAll(any(org.springframework.data.jpa.domain.Specification.class), paging.capture());
        assertEquals(25, paging.getValue().getPageSize(), "Search cap is 25");
        assertNotNull(paging.getValue().getSort().getOrderFor("updatedAt"));
    }

    /** Each word must match somewhere — so the text is split on spaces and commas. */
    @Test
    void theSearchTextIsSplitIntoWords() {
        assertEquals(List.of("233", "wacker", "chicago", "60606"), SavedRecipientSearch.words("  233 Wacker,  Chicago 60606 "));
        assertEquals(List.of("sw1a", "1aa"), SavedRecipientSearch.words("SW1A 1AA"));
        assertEquals(List.of(), SavedRecipientSearch.words("   "));
        assertEquals(List.of(), SavedRecipientSearch.words(null));
        assertEquals(SavedRecipientSearch.MAX_WORDS, SavedRecipientSearch.words("a b c d e f g h").size());
    }

    @Test
    void byIdReturnsRowOrNotFound() {
        SavedRecipient r = new SavedRecipient();
        r.setId(5L); r.setName("Test"); r.setAddressLine1("a"); r.setCity("c");
        r.setPostalCode("p"); r.setCountryCode("US");
        when(repo.findById(5L)).thenReturn(Optional.of(r));

        assertEquals(200, service.byId(5L).getCode());
        assertEquals("Test", service.byId(5L).getData().getName());
        when(repo.findById(999L)).thenReturn(Optional.empty());
        assertEquals(404, service.byId(999L).getCode());
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    /**
     * Editing one entry into an exact copy of another (same name + street +
     * postal code, same owner) must be refused with a message naming it —
     * it used to hit the unique index and come back as a bare 500.
     */
    @Test
    void anEditThatDuplicatesAnotherEntryIsRefusedByName() {
        Long first = service.create(acmeRequest()).getData().getId();
        SavedRecipientDTO other = acmeRequest();
        other.setName("Acme Annex");
        other.setAddressLine1("2 Annex Road");
        Long second = service.create(other).getData().getId();

        // The collision lookup finds the FIRST entry for the edited identity.
        when(repo.findExisting(anyString(), any())).thenReturn(Optional.of(saved.get(first)));
        ApiResponse<SavedRecipientDTO> r = service.update(second, acmeRequest());
        assertEquals(409, r.getCode());
        assertTrue(r.getMessage().contains("Acme Warehouse") && r.getMessage().contains("1 Warehouse Way"), r.getMessage());

        // Editing an entry while keeping its own identity is not a collision.
        ApiResponse<SavedRecipientDTO> self = service.update(first, acmeRequest());
        assertEquals(200, self.getCode(), self.getMessage());
    }

    /** The Address book page: a platform operator with no client sees every entry. */
    @Test
    void theListShowsEveryEntryToAPlatformOperatorWithNoClientPicked() {
        // A signed-in admin with no client of their own is a platform operator.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            listAsOperator();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void listAsOperator() {
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.<SavedRecipient>of()));
        assertEquals(200, service.list(null, null, 0, 25).getCode());
        org.mockito.Mockito.clearInvocations(repo);

        service.list("chicago", "DES875", 2, 500);
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> paging =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(repo).findAll(any(org.springframework.data.jpa.domain.Specification.class), paging.capture());
        assertEquals(2, paging.getValue().getPageNumber());
        assertEquals(100, paging.getValue().getPageSize(), "page size is capped at 100");
    }
}
