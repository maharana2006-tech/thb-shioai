package com.multiship.backend.config;

import com.multiship.backend.repository.OrderRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * SpEL helper used in @PreAuthorize expressions to scope TENANT users to
 * their own orders. A TENANT user maps to a tenant code by the uppercase
 * username convention (user "arhdev" -> tenant "ARHDEV"), mirroring
 * COALESCE(tenant_id, cust_no) on the order.
 */
@Component("orderAccess")
public class OrderAccessEvaluator {

    private final OrderRepository orderRepository;

    public OrderAccessEvaluator(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Operators see every order; a TENANT only orders of their own tenant. */
    public boolean canViewOrder(Authentication authentication, Integer orderNo) {
        if (isOperator(authentication)) {
            return true;
        }

        if (!hasRole(authentication, "ROLE_TENANT") || orderNo == null) {
            return false;
        }

        String ownTenant = tenantIdOf(authentication);

        return orderRepository.findByOrderNo(orderNo)
                .map(order -> {
                    String orderTenant = order.getTenantId() != null && !order.getTenantId().isBlank()
                            ? order.getTenantId()
                            : order.getCustNo();
                    return orderTenant != null && orderTenant.trim().toUpperCase().equals(ownTenant);
                })
                .orElse(false);
    }

    /** Operators may query any tenant; a TENANT only their own tenant id. */
    public boolean canViewTenant(Authentication authentication, String tenantId) {
        if (isOperator(authentication)) {
            return true;
        }

        return hasRole(authentication, "ROLE_TENANT")
                && tenantId != null
                && tenantId.trim().toUpperCase().equals(tenantIdOf(authentication));
    }

    private boolean isOperator(Authentication authentication) {
        return hasRole(authentication, "ROLE_ADMIN") || hasRole(authentication, "ROLE_USER");
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }

        return false;
    }

    private String tenantIdOf(Authentication authentication) {
        return authentication.getName().trim().toUpperCase();
    }
}
