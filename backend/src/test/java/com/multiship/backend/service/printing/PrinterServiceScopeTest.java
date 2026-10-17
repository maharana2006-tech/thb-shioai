package com.multiship.backend.service.printing;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.model.Printer;
import com.multiship.backend.model.PrinterAssignment;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.PrinterAssignmentRepository;
import com.multiship.backend.repository.PrinterRepository;
import com.multiship.backend.service.TenantScopeEnforcer;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IDOR regression for the printer READ endpoints. A client-scoped USER must see
 * only its own-client assignments plus the defaults (clientCode null), and the
 * printers those point at — never another client's printer host/IPs. Platform
 * operators (ADMIN) are unaffected. See PrinterController list / assignments /
 * {id}/queue-depth / {id}/tags / {id}/test-history.
 */
class PrinterServiceScopeTest {

    private PrinterRepository printers;
    private PrinterAssignmentRepository assignments;
    private PrinterService service;

    @BeforeEach
    void setUp() {
        printers = mock(PrinterRepository.class);
        assignments = mock(PrinterAssignmentRepository.class);
        service = new PrinterService(printers, assignments,
                mock(ClientRepository.class), mock(OrderRepository.class));
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        when(printers.findAllByOrderByNameAscIdAsc())
                .thenReturn(List.of(printer(1L, "p-acme"), printer(2L, "p-other"), printer(3L, "p-default")));
        when(assignments.findAllByOrderByClientCodeAscDocTypeAsc())
                .thenReturn(List.of(assignment("ACME", 1L), assignment("OTHER", 2L), assignment(null, 3L)));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Printer printer(Long id, String name) {
        Printer p = new Printer();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static PrinterAssignment assignment(String clientCode, Long printerId) {
        PrinterAssignment a = new PrinterAssignment();
        a.setClientCode(clientCode);
        a.setDocType("LABEL");
        a.setPrinterId(printerId);
        return a;
    }

    private static void authAs(String role, String tenant) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = User.withUsername(tenant == null ? "admin" : tenant.toLowerCase())
                .password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        if (tenant != null) token.setDetails(new JwtAuthenticationFilter.AuthDetails(tenant));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void scopedUserSeesOnlyOwnAndDefaultPrinters() {
        authAs("USER", "ACME");
        List<Long> ids = service.list().stream().map(Printer::getId).toList();
        assertEquals(List.of(1L, 3L), ids, "ACME sees its own printer + the default, not OTHER's");
    }

    @Test
    void scopedUserSeesOnlyOwnAndDefaultAssignments() {
        authAs("USER", "ACME");
        List<PrinterAssignment> result = service.listAssignments();
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(a -> "ACME".equals(a.getClientCode())), "own kept");
        assertTrue(result.stream().anyMatch(a -> a.getClientCode() == null), "default kept");
        assertFalse(result.stream().anyMatch(a -> "OTHER".equals(a.getClientCode())), "foreign hidden");
    }

    @Test
    void scopedUserGets403OnForeignPrinterId() {
        authAs("USER", "ACME");
        assertThrows(AccessDeniedException.class, () -> service.assertPrinterVisible(2L));
        assertThrows(AccessDeniedException.class, () -> service.queueDepth(2L));
        assertDoesNotThrow(() -> service.assertPrinterVisible(1L), "own printer visible");
        assertDoesNotThrow(() -> service.assertPrinterVisible(3L), "default printer visible");
    }

    @Test
    void platformOperatorSeesAll() {
        authAs("ADMIN", null);
        assertEquals(3, service.list().size());
        assertEquals(3, service.listAssignments().size());
        assertDoesNotThrow(() -> service.assertPrinterVisible(2L));
    }
}
