package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderListFilters;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.PaginationRequestDTO;
import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderRawCodesRepository;
import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 49 Tier 5 Fix 3 — regression guard on listOrders composition.
 *
 * <p>Covers the filter-normalization, validation, pagination-clamp,
 * and sort-default paths — the seams where a bad refactor would let
 * a cross-tenant leak or malformed query through unnoticed.
 *
 * <p>Pure Mockito, no Spring context — the service uses @Autowired
 * field injection, so tests set the mocks via reflection to keep
 * this a real unit test (fast, no DB dependency).
 */
class OrderServiceImplTest {

    private OrderRepository orderRepository;
    private CarrierConfigRepository carrierConfigRepository;
    private CarrierService carrierService;
    private OrderRawCodesRepository orderRawCodesRepository;
    private LabelPackageRepository labelPackageRepository;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository = mock(OrderRepository.class);
        carrierConfigRepository = mock(CarrierConfigRepository.class);
        carrierService = mock(CarrierService.class);
        orderRawCodesRepository = mock(OrderRawCodesRepository.class);
        labelPackageRepository = mock(LabelPackageRepository.class);

        service = new OrderServiceImpl();
        inject("orderRepository", orderRepository);
        inject("carrierConfigRepository", carrierConfigRepository);
        inject("carrierService", carrierService);
        inject("orderRawCodesRepository", orderRawCodesRepository);
        inject("labelPackageRepository", labelPackageRepository);
        // Sprint 50 Tier 0.5 PR E — real enforcer wired to an anonymous
        // Authentication (no context set), so clampClientCode returns
        // the caller's requested value unchanged (operator behaviour).
        // Individual tenant-scope tests can flip this by setting the
        // SecurityContextHolder in the test body.
        inject("tenantScope", new TenantScopeEnforcer(new AccessScopePolicy(false)));

        when(orderRepository.findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
    }

    private void inject(String name, Object value) throws Exception {
        Field f = OrderServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private OrderListFilters emptyFilters() {
        return new OrderListFilters();
    }

    private PaginationRequestDTO page(int page, int size) {
        PaginationRequestDTO p = new PaginationRequestDTO();
        p.setPage(page);
        p.setSize(size);
        return p;
    }

    /* -------- tenant scoping -------- */

    @Test
    void tenantFilterIsForwardedToRepositoryUnchanged() {
        OrderListFilters f = emptyFilters();
        f.setTenantId("ACME");
        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), eq("ACME"), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        verify(orderRepository).countOrdersUnified(
                anyString(), eq("ACME"), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nullTenantNormalizesToEmptyString() {
        // "" is the SQL sentinel for "no filter" in findOrdersUnified.
        OrderListFilters f = emptyFilters();
        // tenantId left null

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), eq(""), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    /* -------- validation errors -------- */

    @Test
    void unknownStatusReturns400WithoutHittingRepo() {
        OrderListFilters f = emptyFilters();
        f.setStatus("GARBAGE");

        ApiResponse<PageResponseDTO<OrderResponseDTO>> resp = service.listOrders(f, false, page(0, 20));
        assertEquals(400, resp.getCode());
        assertEquals("ERROR", resp.getStatus());
        assertTrue(resp.getMessage().contains("Unknown status"));
    }

    @Test
    void unknownResolutionReturns400() {
        OrderListFilters f = emptyFilters();
        f.setResolution("MAGIC");

        ApiResponse<PageResponseDTO<OrderResponseDTO>> resp = service.listOrders(f, false, page(0, 20));
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("Unknown resolution"));
    }

    @Test
    void malformedDateFilterReturns400() {
        OrderListFilters f = emptyFilters();
        f.setCreatedFrom("not-a-date");

        ApiResponse<PageResponseDTO<OrderResponseDTO>> resp = service.listOrders(f, false, page(0, 20));
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("yyyy-MM-dd"));
    }

    /* -------- pagination clamping -------- */

    @Test
    void pageSizeAbove100IsClampedTo100() {
        service.listOrders(emptyFilters(), false, page(0, 500));
        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), limitCap.capture(), anyString(), anyString());
        assertEquals(100, limitCap.getValue());
    }

    @Test
    void pageSizeBelow1IsClampedTo1() {
        service.listOrders(emptyFilters(), false, page(0, 0));
        ArgumentCaptor<Integer> limitCap = ArgumentCaptor.forClass(Integer.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), limitCap.capture(), anyString(), anyString());
        assertEquals(1, limitCap.getValue());
    }

    @Test
    void negativePageIsClampedToZero() {
        service.listOrders(emptyFilters(), false, page(-5, 20));
        ArgumentCaptor<Integer> offsetCap = ArgumentCaptor.forClass(Integer.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                offsetCap.capture(), anyInt(), anyString(), anyString());
        assertEquals(0, offsetCap.getValue());
    }

    /* -------- sort defaults -------- */

    @Test
    void nullSortByDefaultsToOrderNoAsc() {
        service.listOrders(emptyFilters(), false, page(0, 20));  // no sort set
        ArgumentCaptor<String> sortBy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sortDir = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), sortBy.capture(), sortDir.capture());
        assertEquals("orderNo", sortBy.getValue());
        assertEquals("ASC", sortDir.getValue());
    }

    /* -------- response shape -------- */

    @Test
    void emptyResultReturnsEmptyPage() {
        ApiResponse<PageResponseDTO<OrderResponseDTO>> resp =
                service.listOrders(emptyFilters(), false, page(0, 20));
        assertNotNull(resp.getData());
        assertEquals(0L, resp.getData().getTotalElements());
        assertTrue(resp.getData().getContent().isEmpty());
    }

    /* -------- listBatches (2026-09-14 batch column filter) --------
     *
     * Guards the /orders/batches picker endpoint. Same filter-normalisation
     * as listOrders — reuse those tests for keyword/tenant/date paths and
     * cover here only what's specific to listBatches: row shape mapping,
     * batch-id digit sanitisation, and the response envelope.
     */

    @Test
    void listBatchesEmptyResultReturnsSuccessWithEmptyList() {
        when(orderRepository.findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> resp = service.listBatches(emptyFilters());

        assertEquals(200, resp.getCode());
        assertEquals("SUCCESS", resp.getStatus());
        assertTrue(resp.getMessage().contains("0 batch"));
        assertNotNull(resp.getData());
        assertTrue(resp.getData().isEmpty());
    }

    @Test
    void listBatchesMapsRepoRowsToBatchIdAndCount() {
        // Repo returns Object[] {batch_id, row_count}. Postgres COUNT(*)
        // widens to Long; batch_id column is INTEGER. Service must return
        // count as long via toLong (defends against Number subclasses).
        when(orderRepository.findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new Object[]{42, 17L},
                        new Object[]{14, 3L}));

        ApiResponse<List<Map<String, Object>>> resp = service.listBatches(emptyFilters());

        assertEquals(200, resp.getCode());
        assertEquals(2, resp.getData().size());
        assertEquals(42, resp.getData().get(0).get("batchId"));
        assertEquals(17L, resp.getData().get(0).get("count"));
        assertEquals(14, resp.getData().get(1).get("batchId"));
        assertEquals(3L, resp.getData().get(1).get("count"));
    }

    @Test
    void listBatchesMalformedDateReturns400WithoutHittingRepo() {
        OrderListFilters f = emptyFilters();
        f.setCreatedFrom("not-a-date");

        ApiResponse<List<Map<String, Object>>> resp = service.listBatches(f);

        assertEquals(400, resp.getCode());
        assertEquals("ERROR", resp.getStatus());
        assertTrue(resp.getMessage().contains("yyyy-MM-dd"));
    }

    @Test
    void listBatchesStripsNonDigitsFromBatchIdBeforeRepo() {
        // Operator types "#14" — service must sanitise to "14" so the
        // native SQL cast succeeds. Mirrors listOrders behaviour.
        OrderListFilters f = emptyFilters();
        f.setBatchId("#14");
        when(orderRepository.findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        service.listBatches(f);

        ArgumentCaptor<String> batchCap = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                batchCap.capture(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals("14", batchCap.getValue());
    }

    @Test
    void listBatchesAllNonDigitBatchIdCollapsesToEmpty() {
        // "abc" carries no numeric payload; treated as no filter so the
        // dropdown returns every batch in scope.
        OrderListFilters f = emptyFilters();
        f.setBatchId("abc");
        when(orderRepository.findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        service.listBatches(f);

        ArgumentCaptor<String> batchCap = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                batchCap.capture(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals("", batchCap.getValue());
    }

    @Test
    void listBatchesForwardsTenantAndFiltersToRepo() {
        OrderListFilters f = emptyFilters();
        f.setTenantId("ACME");
        f.setCustomer("BOB");
        when(orderRepository.findDistinctBatchesUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        service.listBatches(f);

        verify(orderRepository).findDistinctBatchesUnified(
                anyString(), eq("ACME"), anyString(), anyString(),
                eq("BOB"), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /* -------- search prefix syntax (2026-09-15) --------
     *
     * "batch:14" style keywords route to the dedicated filter slot BEFORE
     * the fuzzy keyword branch fires, so operators can escape the noise
     * of substring-matching across every ID field. See SEARCH_PREFIX
     * javadoc on OrderServiceImpl.
     */

    @Test
    void batchPrefixRoutesToBatchIdEqAndClearsKeyword() {
        OrderListFilters f = emptyFilters();
        f.setSearch("batch:14");

        service.listOrders(f, false, page(0, 20));

        // keyword arg (slot 3) empty, batchIdEq arg (slot 9) = "14".
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq("14"), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void orderPrefixRoutesToOrderNoLikeAndClearsKeyword() {
        OrderListFilters f = emptyFilters();
        f.setSearch("order:900044");

        service.listOrders(f, false, page(0, 20));

        // orderNoLike is arg slot 7.
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                anyString(), anyString(), eq("900044"), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void trackingPrefixRoutesToTrackingAndClearsKeyword() {
        OrderListFilters f = emptyFilters();
        f.setSearch("tracking:1Z9999");

        service.listOrders(f, false, page(0, 20));

        // tracking is arg slot 8.
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                anyString(), anyString(), anyString(), eq("1Z9999"),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void clientPrefixRoutesToCustomerAndClearsKeyword() {
        OrderListFilters f = emptyFilters();
        f.setSearch("client:ARHDEV");

        service.listOrders(f, false, page(0, 20));

        // customer is arg slot 5.
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                eq("ARHDEV"), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void unknownPrefixKeepsKeywordFuzzy() {
        // "foo:bar" — no matching prefix. Whole string stays in the
        // keyword slot so today's fuzzy branch handles it.
        OrderListFilters f = emptyFilters();
        f.setSearch("foo:bar");

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq("foo:bar"), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void bareKeywordUnchangedByRouting() {
        // "14" alone — no prefix. Stays in the keyword slot; the
        // fuzzy branch matches it against order_no / batch_id / etc.
        OrderListFilters f = emptyFilters();
        f.setSearch("14");

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq("14"), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void batchPrefixWithNonDigitSuffixFallsBackToFuzzy() {
        // "batch:foo" — the batch slot only accepts digits. Since no
        // digits survive sanitisation the routing is skipped and the
        // keyword flows through fuzzy so we never silently drop input.
        OrderListFilters f = emptyFilters();
        f.setSearch("batch:foo");

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq("batch:foo"), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq(""), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void explicitBatchFilterWinsOverBatchPrefix() {
        // Operator has batch #14 pinned via the panel AND typed
        // "batch:22" in the search box. Panel wins — the prefix is
        // ignored and the keyword falls through untouched.
        OrderListFilters f = emptyFilters();
        f.setBatchId("14");
        f.setSearch("batch:22");

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq("batch:22"), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq("14"), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void prefixIsCaseInsensitive() {
        OrderListFilters f = emptyFilters();
        f.setSearch("BATCH:14");

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                eq("14"), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }

    /**
     * BUG-013 - rows with order_label_tracking.status='FAILED' were silently
     * dropped (counted neither in Failed nor Generated nor the PENDING buckets),
     * so {@code All orders = ready + needs + choose + client + failed + generated}
     * ran short of the real row count. SQL now unions 'ERROR'+'FAILED' into the
     * failed bucket. This test locks the service's pass-through of that count
     * so a future refactor can't drop the mapping.
     */
    @Test
    void getQueueStats_mapsFailedCountFromRepositoryUnion() {
        // Repo returns the post-fix row shape: 5 failed covers both ERROR and FAILED.
        Object[] row = new Object[] { 0L, 0L, 0L, 0L, 5L, 10L };
        when(orderRepository.getQueueStats()).thenReturn(java.util.Collections.singletonList(row));

        ApiResponse<Map<String, Long>> resp = service.getQueueStats();

        assertNotNull(resp.getData());
        assertEquals(5L, resp.getData().get("failed"));
        assertEquals(10L, resp.getData().get("generated"));
        // All = ready + needs + choose + client + failed + generated - union invariant.
        long all = resp.getData().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(15L, all);
    }

    /* -------- BUG-012: SQL LIKE wildcard escape -------- */

    /**
     * BUG-012 (QA 2026-10-23) — a literal {@code '%'} typed into search
     * previously matched every row (34,133 orders), and {@code 'QA_'}
     * matched {@code 'QA-'} because the underscore acts as any-single-
     * char. Service now escapes {@code \ % _} before binding; the SQL
     * carries {@code ESCAPE '\\'} on every LIKE so the escape takes effect.
     */
    @Test
    void searchKeywordEscapesPercentAndUnderscore() {
        OrderListFilters f = emptyFilters();
        f.setSearch("QA_20261020-%");

        service.listOrders(f, false, page(0, 20));

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), keyword.capture(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        assertEquals("QA\\_20261020-\\%", keyword.getValue(),
                "literal % and _ must be backslash-escaped before binding to :keyword");
    }

    @Test
    void customerFilterEscapesLikeWildcards() {
        OrderListFilters f = emptyFilters();
        f.setCustomer("MK%L");

        service.listOrders(f, false, page(0, 20));

        ArgumentCaptor<String> customer = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                customer.capture(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        assertEquals("MK\\%L", customer.getValue());
    }

    @Test
    void existingBackslashInKeywordIsDoubled() {
        // A legitimate backslash in a reference ("QA\\INT") must become
        // "QA\\\\INT" on the wire so Postgres unescapes it back to the
        // original single backslash under ESCAPE '\\'.
        OrderListFilters f = emptyFilters();
        f.setSearch("QA\\INT");

        service.listOrders(f, false, page(0, 20));

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), keyword.capture(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        assertEquals("QA\\\\INT", keyword.getValue());
    }

    @Test
    void plainKeywordLeftUntouched() {
        OrderListFilters f = emptyFilters();
        f.setSearch("Austin");

        service.listOrders(f, false, page(0, 20));

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), keyword.capture(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        assertEquals("Austin", keyword.getValue());
    }

    @Test
    void emptyKeywordPassesThroughAsEmptyString() {
        // The service's "no filter" sentinel is "" — the SQL branches
        // skip the LIKE when :keyword = ''. escapeLike must preserve
        // that sentinel.
        OrderListFilters f = emptyFilters(); // no search set

        service.listOrders(f, false, page(0, 20));

        verify(orderRepository).findOrdersUnified(
                anyString(), anyString(), eq(""), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
    }
}
