package com.multiship.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-149 — the 401 entry point names the API-key header on external paths only. */
class SecurityConfigExternalPathTest {

    @Test
    void externalPathsAreRecognised() {
        assertTrue(SecurityConfig.isExternalApiPath(new MockHttpServletRequest("GET", "/api/v1/external/shipments/1/tracking")));
        assertTrue(SecurityConfig.isExternalApiPath(new MockHttpServletRequest("POST", "/api/v2/external/rates")));
    }

    @Test
    void sessionPathsAreNot() {
        assertFalse(SecurityConfig.isExternalApiPath(new MockHttpServletRequest("GET", "/api/v1/orders")));
        assertFalse(SecurityConfig.isExternalApiPath(new MockHttpServletRequest("GET", "/api/v1/api-keys")));
    }

    @Test
    void contextPathIsStripped() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ms/api/v2/external/rates");
        req.setContextPath("/ms");
        assertTrue(SecurityConfig.isExternalApiPath(req));
    }
}
