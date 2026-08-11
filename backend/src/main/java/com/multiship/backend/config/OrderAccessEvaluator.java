package com.multiship.backend.config;

import com.multiship.backend.repository.OrderRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * SpEL helper used in {@code @PreAuthorize} expressions to scope
 * non-operator callers to their own tenant's orders.
 *
 * <p>Sprint 50 Tier 0.5 PR E — delegates the operator/tenant decision to
 * {@link AccessScopePolicy} so USER-with-clientCode (when the flag is on)
 * is treated as a tenant-scoped caller like TENANT. Pre-PR-E behavior
 * mapped USER to tenant via the uppercase-username convention; that path
 * is preserved as a fallback for pre-PR-A tokens that carry no clientCode
 * claim.
 */
@Component("orderAccess")
public class OrderAccessEvaluator {

    private final OrderRepository orderRepository;
    private final AccessScopePolicy accessScope;

    public OrderAccessEvaluator(OrderRepository orderRepository,
                                 AccessScopePolicy accessScope) {
        this.orderRepository = orderRepository;
        this.accessScope = accessScope;
    }

    /** Platform operators see every order; tenant-scoped callers only their own. */
    public boolean canViewOrder(Authentication authentication, Integer orderNo) {
        Optional<String> scope = accessScope.tenantOf(authentication);
        if (scope.isEmpty()) {
            return true;
        }
        if (orderNo == null) {
            return false;
        }
        String owner = scope.get();
        return orderRepository.findByOrderNo(orderNo)
                .map(order -> {
                    String orderTenant = order.getTenantId() != null && !order.getTenantId().isBlank()
                            ? order.getTenantId()
                            : order.getCustNo();
                    return orderTenant != null
                            && orderTenant.trim().toUpperCase().equals(owner);
                })
                .orElse(false);
    }

    /** Platform operators may query any tenant; tenant-scoped callers only their own. */
    public boolean canViewTenant(Authentication authentication, String tenantId) {
        return accessScope.canAccessTenant(authentication, tenantId);
    }
}
