package com.multiship.backend.controller;

import com.multiship.backend.dto.AdminUserAssignClientRequest;
import com.multiship.backend.dto.AdminUserAuditDTO;
import com.multiship.backend.dto.AdminUserDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.AdminUserService;
import com.multiship.backend.service.AdminUserService.ActionResult;
import com.multiship.backend.service.AdminUserService.MutationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for AdminUserController (was 0-coverage).
 * Covers the switch-over-MutationOutcome shaping: OK / ALREADY / USER_NOT_FOUND
 * / CLIENT_NOT_FOUND all map to the audit-defined status + errorCode.
 */
class AdminUserControllerTest {

    private AdminUserService adminUserService;
    private AdminUserController controller;
    private UserDetails actor;

    @BeforeEach
    void setUp() {
        adminUserService = mock(AdminUserService.class);
        controller = new AdminUserController(adminUserService);
        actor = User.withUsername("admin").password("x").roles("ADMIN").build();
    }

    private static AdminUserDTO dto() {
        return AdminUserDTO.builder().id(1L).username("u1").role("USER").clientCode("ACME").build();
    }

    // ===== list =====

    @Test
    void list_returns200WithFilteredData() {
        when(adminUserService.list("q", "USER", "ACME", true, 0, 50)).thenReturn(List.of(dto()));

        ResponseEntity<ApiResponse<List<AdminUserDTO>>> resp = controller.list("q", "USER", "ACME", true, 0, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
    }

    // ===== assignClient =====

    @Test
    void assignClient_ok_returns200() {
        AdminUserAssignClientRequest req = new AdminUserAssignClientRequest("ACME", "moved");
        when(adminUserService.assignClient(eq(1L), eq("ACME"), eq("moved"), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.OK, dto()));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.assignClient(1L, req, actor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ACME", resp.getBody().getData().getClientCode());
    }

    @Test
    void assignClient_userNotFound_returns404_ADMIN_TARGET_USER_NOT_FOUND() {
        AdminUserAssignClientRequest req = new AdminUserAssignClientRequest("ACME", null);
        when(adminUserService.assignClient(eq(1L), any(), any(), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.USER_NOT_FOUND, null));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.assignClient(1L, req, actor);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.ADMIN_TARGET_USER_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void assignClient_clientNotFound_returns404_ADMIN_TARGET_CLIENT_NOT_FOUND() {
        AdminUserAssignClientRequest req = new AdminUserAssignClientRequest("MISSING", null);
        when(adminUserService.assignClient(eq(1L), any(), any(), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.CLIENT_NOT_FOUND, null));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.assignClient(1L, req, actor);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(ErrorCode.ADMIN_TARGET_CLIENT_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    @Test
    void assignClient_alreadyInTargetState_returns200Noop() {
        AdminUserAssignClientRequest req = new AdminUserAssignClientRequest("ACME", null);
        when(adminUserService.assignClient(eq(1L), any(), any(), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.ALREADY_IN_TARGET_STATE, dto()));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.assignClient(1L, req, actor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getData());
    }

    // ===== deactivate / reactivate =====

    @Test
    void deactivate_ok_returns200() {
        when(adminUserService.deactivate(eq(1L), any(), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.OK, dto()));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.deactivate(1L, null, actor);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void reactivate_userNotFound_returns404() {
        when(adminUserService.reactivate(eq(1L), any(), anyString()))
                .thenReturn(new MutationOutcome(ActionResult.USER_NOT_FOUND, null));

        ResponseEntity<ApiResponse<AdminUserDTO>> resp = controller.reactivate(1L, null, actor);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(ErrorCode.ADMIN_TARGET_USER_NOT_FOUND.name(), resp.getBody().getErrorCode());
    }

    // ===== audit reads =====

    @Test
    void recentAudit_returns200WithLimitedRows() {
        AdminUserAuditDTO row = AdminUserAuditDTO.builder().id(1L).subjectUserId(1L).action("DEACTIVATE").build();
        when(adminUserService.recentAudit(50)).thenReturn(List.of(row));

        ResponseEntity<ApiResponse<List<AdminUserAuditDTO>>> resp = controller.recentAudit(50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
    }

    @Test
    void userAudit_returns200PerUser() {
        when(adminUserService.auditForUser(1L)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<AdminUserAuditDTO>>> resp = controller.userAudit(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getData().size());
    }
}
