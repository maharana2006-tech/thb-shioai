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
                anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(List.of());
        when(orderRepository.countOrdersUnified(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
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
                anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString(), anyString());
        verify(orderRepository).countOrdersUnified(
                anyString(), eq("ACME"), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
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
                anyString(), anyString(), anyString(), anyString(),
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
                anyString(), anyString(), anyString(), anyString(),
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
                anyString(), anyString(), anyString(), anyString(),
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
                anyString(), anyString(), anyString(), anyString(),
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
                anyString(), anyString(), anyString(), anyString(),
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
}
