package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.ndsshipment.NdsShipmentLookupService;
import com.multiship.backend.service.ndsshipment.NdsShipmentPrefill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * REST for the NDS Shipment prefill feature. Consumed by the FE
 * Manual Shipment page when the operator scans {@code .X<containerId>}
 * or {@code .Y<batchId>} into the scan field.
 *
 * <p>HTTP status contract:
 * <ul>
 *   <li>{@code 200} — OK / WARNING / BLOCKED (payload always present,
 *       operator UI decides visual treatment from {@code status}).</li>
 *   <li>{@code 404} — no order/container/batch found for the scanned
 *       value (either registry says "no such id" or downstream tables
 *       don't have the header).</li>
 *   <li>{@code 422} — scan value is unparseable
 *       ({@link NdsShipmentLookupService#lookup} threw
 *       {@link IllegalArgumentException}).</li>
 *   <li>{@code 503} — NDS is unreachable / mis-configured; the S1
 *       framework surfaced an {@link ExternalSystemException}.</li>
 * </ul>
 *
 * <p>Logging discipline: {@code INFO} on entry and success with
 * scanned value + scope + client code + order count + package count +
 * status + elapsed ms. <b>Never</b> log recipient address at INFO —
 * that's PII from an external system.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manual-shipment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class NdsShipmentLookupController {

    private final NdsShipmentLookupService service;

    @GetMapping("/nds-lookup")
    public ResponseEntity<ApiResponse<NdsShipmentPrefill>> lookup(@RequestParam("scan") String scan) {
        long t0 = System.nanoTime();
        try {
            Optional<NdsShipmentPrefill> maybe = service.lookup(scan);
            if (maybe.isEmpty()) {
                log.info("nds-lookup: NOT_FOUND scan='{}' elapsedMs={}",
                        scan, elapsedMs(t0));
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(err(HttpStatus.NOT_FOUND,
                                "No NDS record matched the scanned value."));
            }
            NdsShipmentPrefill p = maybe.get();
            log.info("nds-lookup: {} scope={} client={} orders={} packages={} elapsedMs={}",
                    p.status(), p.scope(), p.clientCode(),
                    p.orders() == null ? 0 : p.orders().size(),
                    p.packages() == null ? 0 : p.packages().size(),
                    elapsedMs(t0));
            return ResponseEntity.ok(ok(p));
        } catch (IllegalArgumentException iae) {
            log.info("nds-lookup: UNPROCESSABLE scan='{}' reason={} elapsedMs={}",
                    scan, iae.getMessage(), elapsedMs(t0));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(err(HttpStatus.UNPROCESSABLE_ENTITY, iae.getMessage()));
        } catch (ExternalSystemException ese) {
            log.warn("nds-lookup: NDS unreachable scan='{}' kind={} elapsedMs={}",
                    scan, ese.kind(), elapsedMs(t0), ese);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(err(HttpStatus.SERVICE_UNAVAILABLE,
                            "NDS is unavailable: " + ese.getMessage()));
        }
    }

    // ─── ApiResponse envelope helpers ────────────────────────────────

    private static ApiResponse<NdsShipmentPrefill> ok(NdsShipmentPrefill body) {
        return ApiResponse.<NdsShipmentPrefill>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build();
    }

    private static ApiResponse<NdsShipmentPrefill> err(HttpStatus status, String message) {
        return ApiResponse.<NdsShipmentPrefill>builder()
                .status("ERROR").code(status.value()).timestamp(LocalDateTime.now())
                .message(message).errorCode(status.name()).build();
    }

    private static long elapsedMs(long t0Nanos) {
        return (System.nanoTime() - t0Nanos) / 1_000_000;
    }
}
