package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelIdentifierDTO;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1 — coverage for the polymorphic {@code identifiers} lookup added to
 * {@code POST /api/v1/bulk-labels}. Focused on the resolution surface
 * ({@link BulkLabelServiceImpl#resolveIdentifiers}) + the two request
 * body shapes' XOR check.
 *
 * <p>Pure Mockito; kept in a separate class from {@link BulkLabelServiceImplTest}
 * so the F1 additions can be reviewed alongside the design without expanding
 * an already-large test file.
 */
class BulkLabelServiceImplF1IdentifiersTest {

    private BulkLabelJobRepository jobRepo;
    private CarrierService carrierService;
    private OrderRepository orderRepo;
    private BulkLabelServiceImpl service;

    private final Map<Long, BulkLabelJob> saved = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        jobRepo = mock(BulkLabelJobRepository.class);
        carrierService = mock(CarrierService.class);
        orderRepo = mock(OrderRepository.class);

        // Persist stub — captures the job so we can assert the resolved
        // orderNo list survived the resolver.
        doAnswer(inv -> {
            BulkLabelJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(seq.getAndIncrement());
            saved.put(j.getId(), j);
            return j;
        }).when(jobRepo).save(any(BulkLabelJob.class));
        // Prevent NPE in the worker's findById call (submit() dispatches
        // asynchronously; tests block on the response envelope only).
        lenient().when(jobRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<Long>getArgument(0))));
        // Every tenant guard lookup returns empty by default (no tenant enforcement).
        lenient().when(orderRepo.findByOrderNo(any())).thenReturn(Optional.empty());

        service = new BulkLabelServiceImpl(jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    // ===== XOR: both empty / both set =====

    @Test
    void neither_field_set_400() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder().build(), "alice");
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("orderNumbers or identifiers"));
    }

    @Test
    void both_fields_set_400() {
        // Refuse rather than silently merging — mixing the two modes at the
        // top level is ambiguous. Mix-mode inside identifiers is the
        // supported path.
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .orderNumbers(List.of(1L, 2L))
                        .identifiers(List.of(idOrderNo("3")))
                        .build(),
                "alice");
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("not both"),
                "message should hint at the XOR rule. got: " + resp.getMessage());
    }

    // ===== Legacy orderNumbers path still works =====

    @Test
    void legacy_orderNumbers_path_untouched() {
        // Pre-F1 shape works verbatim — the whole point of the XOR sentinel
        // is that no existing caller has to change to keep working.
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder().orderNumbers(List.of(101L, 202L)).build(),
                "alice");
        assertEquals(200, resp.getCode());
        assertJobPersisted(2, "101,202");
    }

    // ===== identifiers: orderNo type =====

    @Test
    void identifiers_orderNo_only_resolves_to_numeric() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(idOrderNo("42"), idOrderNo("77")))
                        .build(),
                "alice");
        assertEquals(200, resp.getCode());
        assertJobPersisted(2, "42,77");
    }

    @Test
    void identifiers_orderNo_non_numeric_400_with_itemised_error() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(idOrderNo("42"), idOrderNo("abc"), idOrderNo("77")))
                        .build(),
                "alice");
        assertEquals(400, resp.getCode());
        // Fail-fast: single error message names the offender by index +
        // value so the caller can fix it in one round trip.
        assertTrue(resp.getMessage().contains("[1]"), resp.getMessage());
        assertTrue(resp.getMessage().contains("abc"), resp.getMessage());
        assertTrue(resp.getMessage().contains("not numeric"), resp.getMessage());
    }

    // ===== identifiers: orderRef type =====

    @Test
    void identifiers_orderRef_resolves_via_wmsExternalId() {
        when(orderRepo.findByWmsExternalIdIgnoreCase("WMS-ABC-1"))
                .thenReturn(Optional.of(order(1001)));
        when(orderRepo.findByWmsExternalIdIgnoreCase("WMS-ABC-2"))
                .thenReturn(Optional.of(order(1002)));

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(idOrderRef("WMS-ABC-1"), idOrderRef("WMS-ABC-2")))
                        .build(),
                "alice");

        assertEquals(200, resp.getCode());
        assertJobPersisted(2, "1001,1002");
    }

    @Test
    void identifiers_orderRef_miss_400_with_itemised_error() {
        // Rely on the default @BeforeEach stub (returns empty for any call)
        // to model a wmsExternalId miss.
        when(orderRepo.findByWmsExternalIdIgnoreCase(anyString()))
                .thenReturn(Optional.empty());

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(idOrderRef("NOT-A-REF")))
                        .build(),
                "alice");

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("NOT-A-REF"), resp.getMessage());
        assertTrue(resp.getMessage().contains("not found"), resp.getMessage());
        assertTrue(resp.getMessage().contains("wms_external_id"),
                "message should name the column so the operator knows what to fix. got: "
                        + resp.getMessage());
    }

    @Test
    void identifiers_orderRef_lookup_is_case_insensitive() {
        // The repo method is IgnoreCase, so calling with mixed-case input
        // must go straight through — we don't uppercase in the service.
        when(orderRepo.findByWmsExternalIdIgnoreCase("wms-abc-1"))
                .thenReturn(Optional.of(order(2001)));

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(idOrderRef("wms-abc-1")))
                        .build(),
                "alice");

        assertEquals(200, resp.getCode());
        assertJobPersisted(1, "2001");
    }

    // ===== identifiers: mix-mode =====

    @Test
    void identifiers_mix_orderNo_and_orderRef_in_one_job() {
        // The whole reason we picked polymorphic-list over sibling-fields:
        // a caller with some numeric IDs on hand and some WMS refs should
        // be able to enqueue them in a single job with input order preserved.
        when(orderRepo.findByWmsExternalIdIgnoreCase("WMS-B"))
                .thenReturn(Optional.of(order(5555)));

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .identifiers(List.of(
                                idOrderNo("111"),
                                idOrderRef("WMS-B"),
                                idOrderNo("222")))
                        .build(),
                "alice");

        assertEquals(200, resp.getCode());
        assertJobPersisted(3, "111,5555,222");
    }

    // ===== identifiers: bulk cap still applies post-resolution =====

    @Test
    void identifiers_over_500_after_resolution_returns_bulk_limit_exceeded() {
        // The @Size(500) on the DTO catches this at the servlet when the
        // caller sends >500 identifiers directly; the service check gives
        // programmatic callers the same guarantee (mirrors the pre-F1
        // MAX_BULK_ORDERS check on orderNumbers).
        java.util.List<BulkLabelIdentifierDTO> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 501; i++) tooMany.add(idOrderNo(String.valueOf(i)));

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder().identifiers(tooMany).build(),
                "alice");

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.BULK_LIMIT_EXCEEDED.name(), resp.getErrorCode());
    }

    // ===== fixtures =====

    private void assertJobPersisted(int totalCount, String expectedCsv) {
        assertEquals(1, saved.size(), "exactly one job row should have been persisted");
        BulkLabelJob job = saved.values().iterator().next();
        assertNotNull(job.getOrderNumbers());
        assertEquals(expectedCsv, job.getOrderNumbers(),
                "job.orderNumbers CSV must reflect the resolved orderNos in input order");
        assertEquals(totalCount, job.getTotalCount());
        assertEquals("PENDING", job.getStatus());
    }

    private static BulkLabelIdentifierDTO idOrderNo(String value) {
        return BulkLabelIdentifierDTO.builder().type("orderNo").value(value).build();
    }

    private static BulkLabelIdentifierDTO idOrderRef(String value) {
        return BulkLabelIdentifierDTO.builder().type("orderRef").value(value).build();
    }

    private static Order order(int orderNo) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        return o;
    }
}
