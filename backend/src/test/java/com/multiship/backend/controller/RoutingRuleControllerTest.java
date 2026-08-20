package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.dto.RoutingRuleDTO;
import com.multiship.backend.model.RoutingRule;
import com.multiship.backend.service.RoutingRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — RoutingRuleController was 0-coverage per the
 * test-coverage audit. Routing rules run after rate-shop / manual
 * selection but before label generation; a silent controller bug here
 * can route shipments through the wrong carrier fleet-wide.
 *
 * <p>Focus is on controller-owned logic — this controller has more of
 * it than most:
 * <ul>
 *   <li>{@code save} — 201-vs-200 status based on id presence, path→body
 *       clientCode overwrite, exception-to-HTTP mapping for both
 *       {@code IllegalArgumentException} → 400 and
 *       {@code ObjectOptimisticLockingFailureException} → 409
 *       {@code ROUTING_RULE_CONCURRENT_EDIT} (Audit R2 #351)</li>
 *   <li>{@code delete} — {@code IllegalArgumentException} → 400
 *       {@code VALIDATION_FAILED} (Audit B5 — cross-tenant delete
 *       previously silent-succeeded)</li>
 *   <li>{@code list} — entity → DTO mapping happens in the controller,
 *       not the service</li>
 * </ul>
 *
 * <p>Routing evaluation semantics (matcher order, action resolution,
 * trace generation) are owned by RoutingRuleServiceImpl / covered by
 * RoutingRuleServiceImplTest.
 */
class RoutingRuleControllerTest {

    private RoutingRuleService service;
    private RoutingRuleController controller;

    @BeforeEach
    void setUp() {
        service = mock(RoutingRuleService.class);
        controller = new RoutingRuleController(service);
    }

    // ─── list — controller does entity → DTO mapping ───────────────────────

    @Test
    void list_returnsMappedDtoList() {
        RoutingRule r1 = ruleEntity(101L, "ACME", "Rule A", 100);
        RoutingRule r2 = ruleEntity(102L, "ACME", "Rule B", 200);
        when(service.listForClient("ACME")).thenReturn(List.of(r1, r2));

        ResponseEntity<ApiResponse<List<RoutingRuleDTO>>> resp = controller.list("ACME");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().getData().size());
        assertEquals("Rule A", resp.getBody().getData().get(0).getName());
        assertEquals(101L, resp.getBody().getData().get(0).getId());
        assertEquals("Rules loaded", resp.getBody().getMessage());
    }

    @Test
    void list_returnsEmptyList_whenServiceReturnsNoRules() {
        when(service.listForClient("EMPTY_CLIENT")).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<RoutingRuleDTO>>> resp = controller.list("EMPTY_CLIENT");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getData());
        assertEquals(0, resp.getBody().getData().size());
    }

    // ─── save — 201 vs 200 status semantics ────────────────────────────────

    @Test
    void save_returns201Created_whenIdIsNull() {
        // New rule → 201 Created. This is the controller's own decision
        // (HttpStatus.CREATED vs HttpStatus.OK based on body.getId()).
        RoutingRuleDTO body = RoutingRuleDTO.builder()
                .name("New rule").priority(100).actionType(RoutingRule.ActionType.BLOCK)
                .blockReason("Test").build();
        // Service returns the persisted entity with the DB-assigned id.
        RoutingRule saved = ruleEntity(555L, "ACME", "New rule", 100);
        when(service.save(any())).thenReturn(saved);

        ResponseEntity<ApiResponse<RoutingRuleDTO>> resp = controller.save("ACME", body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals(555L, resp.getBody().getData().getId());
        assertEquals("Rule saved", resp.getBody().getMessage());
    }

    @Test
    void save_returns200Ok_whenIdIsPresent() {
        // Existing rule → 200 OK.
        RoutingRuleDTO body = RoutingRuleDTO.builder()
                .id(555L).name("Updated rule").priority(100).actionType(RoutingRule.ActionType.BLOCK)
                .blockReason("Test").build();
        RoutingRule saved = ruleEntity(555L, "ACME", "Updated rule", 100);
        when(service.save(any())).thenReturn(saved);

        ResponseEntity<ApiResponse<RoutingRuleDTO>> resp = controller.save("ACME", body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void save_overwritesBodyClientCodeWithPathVariable() {
        // Regression guard: the controller MUST use the path variable's
        // clientCode, ignoring any value on the body. Prevents a caller
        // from POSTing to /clients/ACME/routing-rules with body.clientCode
        // = "VICTIM" and having the rule land under VICTIM instead of ACME.
        RoutingRuleDTO body = RoutingRuleDTO.builder()
                .name("Cross-tenant probe").priority(100)
                .actionType(RoutingRule.ActionType.BLOCK).blockReason("test")
                .clientCode("VICTIM")  // ← body says VICTIM
                .build();
        RoutingRule saved = ruleEntity(555L, "ACME", "Cross-tenant probe", 100);
        when(service.save(any())).thenReturn(saved);

        controller.save("ACME", body);  // ← path says ACME

        // The body object is mutated in-place before conversion — verify
        // the entity that reached the service has the path's clientCode,
        // not the body's original value.
        ArgumentCaptor<RoutingRule> entityArg = ArgumentCaptor.forClass(RoutingRule.class);
        verify(service).save(entityArg.capture());
        assertEquals("ACME", entityArg.getValue().getClientCode(),
                "controller must overwrite body.clientCode with the path variable — attacker probes must not land in another tenant");
    }

    @Test
    void save_returns400_onIllegalArgumentException() {
        // Service throws IllegalArgumentException for cross-field validation
        // (e.g. REROUTE without targetServiceId, minWeight > maxWeight).
        // Controller catches and returns 400 VALIDATION_FAILED.
        when(service.save(any())).thenThrow(new IllegalArgumentException("REROUTE requires targetServiceId"));

        ResponseEntity<ApiResponse<RoutingRuleDTO>> resp = controller.save("ACME",
                RoutingRuleDTO.builder().name("bad").priority(100)
                        .actionType(RoutingRule.ActionType.REROUTE).build());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_FAILED", resp.getBody().getErrorCode());
        assertEquals("REROUTE requires targetServiceId", resp.getBody().getMessage());
    }

    @Test
    void save_returns409_onOptimisticLockingFailure() {
        // Audit R2 #351 regression guard — two admins editing the same
        // rule; second save must return 409 ROUTING_RULE_CONCURRENT_EDIT
        // with the retry-hint message, NOT silently overwrite. Adding a
        // future `catch (Exception)` above this handler would swallow the
        // ObjectOptimisticLockingFailureException and revert to silent
        // overwrite — this test pins the contract.
        when(service.save(any())).thenThrow(
                new ObjectOptimisticLockingFailureException(RoutingRule.class, 555L));

        ResponseEntity<ApiResponse<RoutingRuleDTO>> resp = controller.save("ACME",
                RoutingRuleDTO.builder().id(555L).name("race").priority(100)
                        .actionType(RoutingRule.ActionType.BLOCK).blockReason("x").build());

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("ROUTING_RULE_CONCURRENT_EDIT", resp.getBody().getErrorCode());
        assertNull(resp.getBody().getData(), "no data returned on race");
    }

    // ─── delete — cross-tenant guard (Audit B5) ────────────────────────────

    @Test
    void delete_returns200_onSuccess() {
        ResponseEntity<ApiResponse<Void>> resp = controller.delete("ACME", 555L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Rule deleted", resp.getBody().getMessage());
        verify(service).delete("ACME", 555L);
    }

    @Test
    void delete_returns400_onCrossTenantIllegalArgumentException() {
        // Audit B5 — cross-tenant delete used to silent-succeed with 200
        // even when the ruleId belonged to a different client. Now the
        // service throws IllegalArgumentException, controller catches
        // and returns 400 VALIDATION_FAILED with the real reason.
        doThrow(new IllegalArgumentException("Rule 555 belongs to client OTHER, not ACME"))
                .when(service).delete("ACME", 555L);

        ResponseEntity<ApiResponse<Void>> resp = controller.delete("ACME", 555L);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("VALIDATION_FAILED", resp.getBody().getErrorCode());
        assertEquals("Rule 555 belongs to client OTHER, not ACME", resp.getBody().getMessage());
    }

    // ─── dryRun — pure delegation ──────────────────────────────────────────

    @Test
    void dryRun_delegatesToServiceEvaluate() {
        RoutingEvaluationResult result = RoutingEvaluationResult.builder()
                .status("MATCH").matchedRuleId(555L).matchedRuleName("Rule A").build();
        RoutingEvaluationRequest req = new RoutingEvaluationRequest();
        when(service.evaluate(eq("ACME"), any())).thenReturn(result);

        ResponseEntity<ApiResponse<RoutingEvaluationResult>> resp = controller.dryRun("ACME", req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(result, resp.getBody().getData());
        assertEquals("Dry-run complete", resp.getBody().getMessage());
        verify(service, times(1)).evaluate("ACME", req);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private RoutingRule ruleEntity(Long id, String clientCode, String name, int priority) {
        RoutingRule r = new RoutingRule();
        r.setId(id);
        r.setClientCode(clientCode);
        r.setName(name);
        r.setPriority(priority);
        r.setActive(true);
        r.setActionType(RoutingRule.ActionType.BLOCK);
        r.setBlockReason("test");
        return r;
    }
}
