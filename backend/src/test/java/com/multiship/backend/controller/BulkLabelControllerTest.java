package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.service.BulkLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage backfill — BulkLabelController was 0-coverage per the
 * test-coverage audit. Flagged as fleet-wide label mistakes risk (a bad
 * code path here → 100+ labels get generated with wrong data).
 *
 * <p>Focused on controller-owned logic. The service ({@link
 * BulkLabelService}) covers submission validation, tenant enforcement,
 * carrier fan-out, and the async job state machine — this test is only
 * about the 3 controller entry points.
 *
 * <p>The {@code download} endpoint is the interesting one: it has real
 * controller-owned logic (Optional.empty → 404, null/empty zip → 404,
 * Base64 decode + attachment header) that is NOT delegated to the
 * service. That branch cluster gets the most attention here.
 */
class BulkLabelControllerTest {

    private BulkLabelService bulkLabelService;
    private BulkLabelController controller;
    private UserDetails alice;

    @BeforeEach
    void setUp() {
        bulkLabelService = mock(BulkLabelService.class);
        controller = new BulkLabelController(bulkLabelService);
        alice = User.withUsername("alice").password("x").authorities("ROLE_USER").build();
    }

    // ─── submit: principal → username + status echo ────────────────────────

    @Test
    void submit_extractsUsernameFromPrincipal_andEchoesServiceStatus() {
        BulkLabelRequestDTO req = BulkLabelRequestDTO.builder()
                .orderNumbers(List.of(101L, 102L, 103L)).build();
        BulkLabelJobDTO data = BulkLabelJobDTO.builder().id(9L).status("PENDING").totalCount(3).build();
        ApiResponse<BulkLabelJobDTO> serviceResp = ApiResponse.<BulkLabelJobDTO>builder()
                .status("success").code(202).data(data).build();
        when(bulkLabelService.submit(any(), anyString())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<BulkLabelJobDTO>> resp = controller.submit(req, alice);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertSame(data, resp.getBody().getData());
        verify(bulkLabelService).submit(eq(req), eq("alice"));
    }

    @Test
    void submit_defaultsUsernameToUnknown_whenPrincipalNull() {
        // Internal / service-to-service caller with no auth principal — must
        // not NPE; requestedBy on the persisted BulkLabelJob still captures
        // something so audit trails aren't blank.
        ApiResponse<BulkLabelJobDTO> serviceResp = ApiResponse.<BulkLabelJobDTO>builder()
                .status("success").code(202).build();
        when(bulkLabelService.submit(any(), anyString())).thenReturn(serviceResp);

        controller.submit(BulkLabelRequestDTO.builder().orderNumbers(List.of(1L)).build(), null);

        verify(bulkLabelService).submit(any(), eq("unknown"));
    }

    @Test
    void submit_echoesServiceStatusCode_onTenantMismatch() {
        // Sprint 50 Tier 0.5 PR E — service returns 403 when the request
        // includes orderNumbers from another tenant. Controller must not
        // rewrite; the caller distinguishes 403 (denied) from 400 (bad
        // request shape).
        ApiResponse<BulkLabelJobDTO> serviceResp = ApiResponse.<BulkLabelJobDTO>builder()
                .status("error").code(403).errorCode("TENANT_MISMATCH")
                .message("Order 12345 belongs to another tenant.")
                .build();
        when(bulkLabelService.submit(any(), anyString())).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<BulkLabelJobDTO>> resp = controller.submit(
                BulkLabelRequestDTO.builder().orderNumbers(List.of(12345L)).build(), alice);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("TENANT_MISMATCH", resp.getBody().getErrorCode());
    }

    // ─── status: pure delegation + status echo ─────────────────────────────

    @Test
    void status_delegatesToServiceAndEchoesCode() {
        BulkLabelJobDTO data = BulkLabelJobDTO.builder()
                .id(9L).status("COMPLETED").totalCount(3).build();
        data.setSuccessfulCount(2);
        data.setFailedCount(1);
        ApiResponse<BulkLabelJobDTO> serviceResp = ApiResponse.<BulkLabelJobDTO>builder()
                .status("success").code(200).data(data).build();
        when(bulkLabelService.status(9L)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<BulkLabelJobDTO>> resp = controller.status(9L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("COMPLETED", resp.getBody().getData().getStatus());
    }

    @Test
    void status_echoesServiceStatusCode_onJobNotFound() {
        ApiResponse<BulkLabelJobDTO> serviceResp = ApiResponse.<BulkLabelJobDTO>builder()
                .status("error").code(404).errorCode("VALIDATION_ERROR")
                .message("Job 999 not found.")
                .build();
        when(bulkLabelService.status(999L)).thenReturn(serviceResp);

        ResponseEntity<ApiResponse<BulkLabelJobDTO>> resp = controller.status(999L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ─── download: 4-branch controller-owned logic ─────────────────────────

    @Test
    void download_returns404_whenJobNotFound() {
        when(bulkLabelService.findRaw(999L)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> resp = controller.download(999L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNull(resp.getBody());
    }

    @Test
    void download_returns404_whenResultZipBase64IsNull() {
        // Job present but the async job hasn't finished (still PENDING /
        // IN_PROGRESS) — resultZipBase64 hasn't been written yet.
        // Controller must return 404, NOT 200 with an empty body.
        BulkLabelJob job = new BulkLabelJob();
        job.setId(9L);
        job.setStatus("IN_PROGRESS");
        job.setResultZipBase64(null);
        when(bulkLabelService.findRaw(9L)).thenReturn(Optional.of(job));

        ResponseEntity<byte[]> resp = controller.download(9L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNull(resp.getBody());
    }

    @Test
    void download_returns404_whenResultZipBase64IsEmpty() {
        // Edge case: job COMPLETED but with 0 successful labels → empty ZIP.
        // Same 404 semantic — API doc says "404 otherwise so the caller can
        // distinguish not-ready from job-not-found" (i.e. both null and
        // empty count as not-downloadable).
        BulkLabelJob job = new BulkLabelJob();
        job.setId(9L);
        job.setStatus("COMPLETED");
        job.setResultZipBase64("");
        when(bulkLabelService.findRaw(9L)).thenReturn(Optional.of(job));

        ResponseEntity<byte[]> resp = controller.download(9L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void download_streamsBase64DecodedZip_withAttachmentHeaders_whenReady() {
        byte[] realZipBytes = new byte[]{'P', 'K', 3, 4, 0x0A};  // ZIP local file header magic
        String base64 = Base64.getEncoder().encodeToString(realZipBytes);

        BulkLabelJob job = new BulkLabelJob();
        job.setId(42L);
        job.setStatus("COMPLETED");
        job.setResultZipBase64(base64);
        when(bulkLabelService.findRaw(42L)).thenReturn(Optional.of(job));

        ResponseEntity<byte[]> resp = controller.download(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertArrayEquals(realZipBytes, resp.getBody());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, resp.getHeaders().getContentType());
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("bulk-labels-42.zip"),
                "expected filename with job id in Content-Disposition; got: " + disposition);
    }

    @Test
    void download_forwardsJobIdToServiceLookup() {
        // Regression guard: the @PathVariable Long is passed through as-is
        // (no accidental cast / trim / autoboxing bug that would let 42L
        // resolve to a different job).
        when(bulkLabelService.findRaw(anyLong())).thenReturn(Optional.empty());

        controller.download(42L);

        verify(bulkLabelService).findRaw(42L);
    }
}
