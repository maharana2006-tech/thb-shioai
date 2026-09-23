package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * USPS Direct — coverage for the three per-account tenant identifiers
 * ({@code uspsDirectAccountNumber}, {@code uspsDirectCrid},
 * {@code uspsDirectMid}) wired through {@link AccountRefServiceImpl}:
 *
 * <ul>
 *   <li>Upsert with values set → entity gets them populated.</li>
 *   <li>Upsert with values blank (empty string) → entity gets them nulled.
 *       Matches the null-vs-empty-string convention shared by every other
 *       optional identifier field on {@link AccountRefUpsertRequest}
 *       (shippingPurpose / clearanceOption / currency / thirdParty*).</li>
 *   <li>{@code toDTO} echoes the three fields verbatim — the FE renders
 *       the "USPS Direct provisioned" state from these three columns.</li>
 * </ul>
 *
 * <p>Pure Mockito; no DB, no Spring. See
 * {@code docs/usps-direct-integration.md} sections 4.2 (tenant identifiers)
 * and 6.6 (per-call resolution).
 */
class AccountRefServiceUspsIdentifiersTest {

    private CarrierAccountRefRepository accountRepo;
    private OrderTrackingRepository trackingRepo;
    private CarrierService carrierService;
    private AuditService auditService;
    private ApplicationEventPublisher publisher;
    private CarrierConnector uspsConnector;

    private AccountRefServiceImpl service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(CarrierAccountRefRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        carrierService = mock(CarrierService.class);
        auditService = mock(AuditService.class);
        publisher = mock(ApplicationEventPublisher.class);
        uspsConnector = mock(CarrierConnector.class);
        when(uspsConnector.getCarrierCode()).thenReturn("USPS");
        when(carrierService.getCarrierConnector("USPS")).thenReturn(uspsConnector);

        service = new AccountRefServiceImpl(
                accountRepo, trackingRepo, carrierService, auditService, publisher);
        // TenantScopeEnforcer left null; the null-safe wrappers treat that
        // as "operator" (pass-through), so we don't have to model tenant
        // clamping in these identifier-focused tests.
    }

    private static AccountRefUpsertRequest.AccountRefUpsertRequestBuilder baseRequest() {
        return AccountRefUpsertRequest.builder()
                .accountNumber("USPS-A-01")
                .carrierCode("USPS")
                .clientId("cid")
                .clientSecret("csec")
                .customerNo("ACME");
    }

    /* --------- upsert: values populate the entity verbatim (no upper-casing) --------- */

    @Test
    void upsert_withUspsDirectIdentifiersSet_persistsThemVerbatim() {
        AccountRefUpsertRequest req = baseRequest()
                .uspsDirectAccountNumber("PA-1234567890")
                .uspsDirectCrid("CRID-98765")
                .uspsDirectMid("MID-abcDEF-12")
                .build();
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.empty());
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        ArgumentCaptor<CarrierAccountRef> captor = ArgumentCaptor.forClass(CarrierAccountRef.class);
        verify(accountRepo).save(captor.capture());
        CarrierAccountRef persisted = captor.getValue();
        // Values persist as-is — USPS returns mixed-case identifiers from
        // provisioning and we don't want to canonicalise them.
        assertEquals("PA-1234567890", persisted.getUspsDirectAccountNumber());
        assertEquals("CRID-98765", persisted.getUspsDirectCrid());
        assertEquals("MID-abcDEF-12", persisted.getUspsDirectMid());
    }

    @Test
    void upsert_withUspsDirectIdentifiersOmitted_leavesEntityFieldsAlone() {
        // Existing account with USPS Direct identifiers already provisioned.
        // A request that OMITS the fields (null in Java) must keep the
        // persisted values — matches the null-vs-empty-string convention
        // that null = keep.
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(42L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid-old");
        existing.setClientSecret("csec-old");
        existing.setUspsDirectAccountNumber("PA-EXIST");
        existing.setUspsDirectCrid("CRID-EXIST");
        existing.setUspsDirectMid("MID-EXIST");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountRefUpsertRequest req = baseRequest()
                // uspsDirect* deliberately unset (null) — must keep persisted.
                .build();

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        assertEquals("PA-EXIST", existing.getUspsDirectAccountNumber());
        assertEquals("CRID-EXIST", existing.getUspsDirectCrid());
        assertEquals("MID-EXIST", existing.getUspsDirectMid());
    }

    @Test
    void upsert_withUspsDirectIdentifiersBlank_nullsEntityFields() {
        // Existing account with USPS Direct identifiers provisioned.
        // A request that sets them to EMPTY STRING = clear (revert to
        // "not provisioned") — matches the null-vs-empty-string
        // convention that empty string = clear.
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(42L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid-old");
        existing.setClientSecret("csec-old");
        existing.setUspsDirectAccountNumber("PA-EXIST");
        existing.setUspsDirectCrid("CRID-EXIST");
        existing.setUspsDirectMid("MID-EXIST");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountRefUpsertRequest req = baseRequest()
                .uspsDirectAccountNumber("")
                .uspsDirectCrid("")
                .uspsDirectMid("")
                .build();

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        assertNull(existing.getUspsDirectAccountNumber(), "empty string must clear the column");
        assertNull(existing.getUspsDirectCrid());
        assertNull(existing.getUspsDirectMid());
    }

    @Test
    void upsert_withWhitespaceOnlyIdentifiers_treatedAsBlank_nullsEntityFields() {
        // Trim-then-check semantics: "   " is normalised to "" and clears
        // the column. Matches the applyTrimmable helper's behaviour for
        // every other optional identifier on this DTO.
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(42L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid-old");
        existing.setClientSecret("csec-old");
        existing.setUspsDirectAccountNumber("PA-EXIST");
        existing.setUspsDirectCrid("CRID-EXIST");
        existing.setUspsDirectMid("MID-EXIST");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountRefUpsertRequest req = baseRequest()
                .uspsDirectAccountNumber("   ")
                .uspsDirectCrid("\t")
                .uspsDirectMid("  ")
                .build();

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        assertNull(existing.getUspsDirectAccountNumber());
        assertNull(existing.getUspsDirectCrid());
        assertNull(existing.getUspsDirectMid());
    }

    @Test
    void upsert_withUspsDirectIdentifiersTrimmedNotUpperCased() {
        // Leading/trailing whitespace is trimmed but internal case is
        // preserved (mixed-case identifiers from USPS provisioning must
        // round-trip byte-perfect).
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(42L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid-old");
        existing.setClientSecret("csec-old");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountRefUpsertRequest req = baseRequest()
                .uspsDirectAccountNumber("  PA-xyzABC  ")
                .uspsDirectCrid(" CRID-mixed ")
                .uspsDirectMid("MID-lowercase")
                .build();

        service.upsertAccount(req);

        assertEquals("PA-xyzABC", existing.getUspsDirectAccountNumber(),
                "internal case must be preserved (USPS identifiers are opaque)");
        assertEquals("CRID-mixed", existing.getUspsDirectCrid());
        assertEquals("MID-lowercase", existing.getUspsDirectMid());
    }

    /* --------- toDTO exposes all three identifiers verbatim --------- */

    @Test
    void toDTO_includesUspsDirectIdentifierFields() {
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(99L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid");
        existing.setClientSecret("csec");
        existing.setUspsDirectAccountNumber("PA-42");
        existing.setUspsDirectCrid("CRID-42");
        existing.setUspsDirectMid("MID-42");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Drive through upsert with the fields omitted so we're seeing
        // the toDTO round-trip from an entity that already has them set.
        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(baseRequest().build());

        assertEquals("success", resp.getStatus());
        CarrierAccountRefDTO dto = resp.getData();
        assertNotNull(dto, "upsert must return a DTO on success");
        assertEquals("PA-42", dto.getUspsDirectAccountNumber(),
                "toDTO must echo uspsDirectAccountNumber verbatim");
        assertEquals("CRID-42", dto.getUspsDirectCrid());
        assertEquals("MID-42", dto.getUspsDirectMid());
    }

    @Test
    void toDTO_withNullUspsDirectFields_returnsNullNotEmptyString() {
        // Unprovisioned account (all three columns NULL) — the DTO must
        // echo null so the FE renders the "Not provisioned" state
        // rather than the "Provisioned as empty" state.
        CarrierAccountRef existing = new CarrierAccountRef();
        existing.setId(99L);
        existing.setAccountNumber("USPS-A-01");
        existing.setCarrierCode("USPS");
        existing.setCustomerNo("ACME");
        existing.setClientId("cid");
        existing.setClientSecret("csec");
        // uspsDirect* deliberately left null.
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCaseAndCustomerNoIgnoreCase("USPS-A-01", "USPS", "ACME"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(baseRequest().build());

        assertEquals("success", resp.getStatus());
        CarrierAccountRefDTO dto = resp.getData();
        assertNull(dto.getUspsDirectAccountNumber());
        assertNull(dto.getUspsDirectCrid());
        assertNull(dto.getUspsDirectMid());
    }
}
