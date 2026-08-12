package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderCustomsDTO;
import com.multiship.backend.dto.OrderCustomsUpsertRequest;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR H — customs read/write must refuse cross-tenant
 * access. Both {@code getCustoms} and {@code upsertCustoms} route
 * through {@code requireOrder}, which pulls the order and enforces a
 * tenant match on {@code tenantId} (fallback: {@code custNo}).
 */
class CustomsServiceImplTest {

    private OrderCustomsRepository customsRepo;
    private OrderRepository orderRepo;
    private TenantScopeEnforcer tenantScope;
    private CustomsServiceImpl service;

    @BeforeEach
    void setUp() {
        customsRepo = mock(OrderCustomsRepository.class);
        orderRepo = mock(OrderRepository.class);
        tenantScope = mock(TenantScopeEnforcer.class);

        service = new CustomsServiceImpl(customsRepo, orderRepo);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    private Order orderOwnedBy(String tenantId, String custNo) {
        Order o = new Order();
        o.setOrderNo(1001);
        o.setTenantId(tenantId);
        o.setCustNo(custNo);
        return o;
    }

    /* -------------------------- getCustoms -------------------------- */

    @Test
    void getCustoms_scopedUserOnForeignOrder_isDenied() {
        Order foreign = orderOwnedBy("OTHER", "OTHER");
        when(orderRepo.findByOrderNo(1001)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.getCustoms("1001"));
        verify(customsRepo, never()).findByOrderNoIgnoreCase(any());
    }

    @Test
    void getCustoms_ownOrder_returnsSuccess() {
        Order own = orderOwnedBy("ACME", "ACME");
        when(orderRepo.findByOrderNo(1001)).thenReturn(Optional.of(own));
        when(customsRepo.findByOrderNoIgnoreCase("1001")).thenReturn(Optional.empty());

        ApiResponse<OrderCustomsDTO> resp = service.getCustoms("1001");

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
    }

    @Test
    void getCustoms_fallsBackToCustNoWhenTenantIdBlank() {
        Order own = orderOwnedBy(null, "ACME");
        when(orderRepo.findByOrderNo(1001)).thenReturn(Optional.of(own));
        when(customsRepo.findByOrderNoIgnoreCase("1001")).thenReturn(Optional.empty());

        ApiResponse<OrderCustomsDTO> resp = service.getCustoms("1001");

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
    }

    /* -------------------------- upsertCustoms -------------------------- */

    @Test
    void upsertCustoms_scopedUserOnForeignOrder_isDenied() {
        Order foreign = orderOwnedBy("OTHER", null);
        when(orderRepo.findByOrderNo(1001)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        OrderCustomsUpsertRequest req = OrderCustomsUpsertRequest.builder().build();
        assertThrows(AccessDeniedException.class,
                () -> service.upsertCustoms("1001", req));
        verify(customsRepo, never()).save(any());
    }

    @Test
    void upsertCustoms_ownOrder_persists() {
        Order own = orderOwnedBy("ACME", null);
        when(orderRepo.findByOrderNo(1001)).thenReturn(Optional.of(own));
        when(customsRepo.findByOrderNoIgnoreCase("1001"))
                .thenReturn(Optional.of(OrderCustoms.builder()
                        .orderNo("1001").items(new ArrayList<>()).build()));
        when(customsRepo.save(any(OrderCustoms.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<OrderCustomsDTO> resp = service.upsertCustoms(
                "1001", OrderCustomsUpsertRequest.builder().currency("USD").build());

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(customsRepo).save(any(OrderCustoms.class));
    }
}
