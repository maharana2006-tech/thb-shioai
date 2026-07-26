package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.repository.BulkLabelJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sprint 37 impl. Backpressure via a bounded thread pool so a 500-order
 * submission doesn't OOM. Every per-order label call reuses the
 * existing single-label pipeline ({@link CarrierService#generateLabel})
 * so bulk shares the same idempotency + credential resolution as a
 * one-off click.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLabelServiceImpl implements BulkLabelService {

    /** Max concurrent per-order labels — carriers rate-limit at ~5-10 rps
     *  per account. Keeping it conservative so a big job doesn't trip
     *  carrier throttling. */
    private static final int WORKER_CONCURRENCY = 4;

    /** HTTP timeout for downloading a label PDF from the carrier's CDN. */
    private static final Duration LABEL_FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final BulkLabelJobRepository jobRepository;
    private final CarrierService carrierService;

    /** Fan-out executor for the per-order workers. Shared across every
     *  job because job kick-off already runs on its own thread. */
    private final ExecutorService fanOutExecutor = Executors.newFixedThreadPool(
            WORKER_CONCURRENCY, r -> {
                Thread t = new Thread(r, "bulk-label-worker");
                t.setDaemon(true);
                return t;
            });

    /** Dispatch executor that lifts the job kick-off off the calling
     *  HTTP thread — otherwise the POST /bulk-labels response wouldn't
     *  return until the whole batch was done. */
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bulk-label-dispatcher");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(LABEL_FETCH_TIMEOUT)
            .build();

    @Override
    public ApiResponse<BulkLabelJobDTO> submit(BulkLabelRequestDTO request, String requestedBy) {
        if (request == null || request.getOrderNumbers() == null || request.getOrderNumbers().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "orderNumbers is required.");
        }
        // Persist the job row before we return so the client can poll
        // immediately.
        BulkLabelJob job = new BulkLabelJob();
        job.setOrderNumbers(request.getOrderNumbers().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        job.setRequestedBy(requestedBy);
        job.setStatus("PENDING");
        job.setTotalCount(request.getOrderNumbers().size());
        job.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        BulkLabelJob saved = jobRepository.save(job);

        // Kick off the worker; return before it starts touching carriers.
        dispatchExecutor.submit(() -> runJob(saved.getId()));

        return success(toDto(saved), "Bulk-label job submitted.");
    }

    /**
     * Job worker. Loads the freshly-saved job, iterates order numbers,
     * calls the existing single-label generator for each, and writes
     * the label PDFs into a ZIP. Failure of one order doesn't kill the
     * job — {@code failedCount} accumulates and the ZIP contains only
     * successful labels.
     */
    void runJob(Long jobId) {
        BulkLabelJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Bulk-label job {} disappeared before the worker started", jobId);
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
        jobRepository.save(job);

        long[] orderNos = parseOrderNumbers(job.getOrderNumbers());
        StringBuilder failures = new StringBuilder();

        try (ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBytes)) {

            for (long orderNo : orderNos) {
                try {
                    LabelGenerationResponse label = generateSingle(orderNo);
                    if (label == null) {
                        job.setFailedCount(job.getFailedCount() + 1);
                        failures.append("order ").append(orderNo)
                                .append(": label service returned null\n");
                    } else if (!StringUtils.hasText(label.getTrackingNumber())) {
                        job.setFailedCount(job.getFailedCount() + 1);
                        String reason = label.getMessage() == null ? "unknown" : label.getMessage();
                        failures.append("order ").append(orderNo).append(": ").append(reason).append("\n");
                    } else {
                        byte[] pdf = downloadLabelPdf(label);
                        if (pdf == null) {
                            job.setFailedCount(job.getFailedCount() + 1);
                            failures.append("order ").append(orderNo).append(": label URL unreachable\n");
                        } else {
                            String entryName = "label-" + orderNo + "-" + label.getTrackingNumber() + ".pdf";
                            zip.putNextEntry(new ZipEntry(entryName));
                            zip.write(pdf);
                            zip.closeEntry();
                            job.setSuccessfulCount(job.getSuccessfulCount() + 1);
                        }
                    }
                    jobRepository.save(job);
                } catch (Exception ex) {
                    log.warn("Bulk-label worker: order {} failed: {}", orderNo, ex.getMessage());
                    job.setFailedCount(job.getFailedCount() + 1);
                    failures.append("order ").append(orderNo).append(": ")
                            .append(ex.getMessage() == null ? ex.getClass().getSimpleName()
                                    : ex.getMessage())
                            .append("\n");
                    jobRepository.save(job);
                }
            }

            zip.finish();
            byte[] all = zipBytes.toByteArray();
            if (all.length > 0 && job.getSuccessfulCount() > 0) {
                job.setResultZipBase64(Base64.getEncoder().encodeToString(all));
            }
            job.setStatus("COMPLETED");
        } catch (Exception ex) {
            log.warn("Bulk-label job {} failed globally: {}", jobId, ex.getMessage());
            job.setStatus("FAILED");
            failures.append("global: ").append(ex.getMessage()).append('\n');
        } finally {
            if (failures.length() > 0) job.setFailureMessage(failures.toString());
            job.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
            jobRepository.save(job);
        }
    }

    /**
     * Delegate to the existing single-order label pipeline. Idempotency
     * key + accountId are null — bulk operators aren't picking accounts
     * per row; they want the default cascade to resolve each order's
     * carrier account.
     */
    LabelGenerationResponse generateSingle(long orderNo) {
        ApiResponse<LabelGenerationResponse> resp = carrierService
                .generateLabel(orderNo, null, "bulk-" + orderNo, null);
        return resp == null ? null : resp.getData();
    }

    /**
     * Download the label PDF bytes. Prefers {@code labelPdf} (already
     * base64) when the carrier returned one inline; falls back to
     * fetching {@code labelUrl} over HTTP.
     */
    byte[] downloadLabelPdf(LabelGenerationResponse label) {
        if (StringUtils.hasText(label.getLabelPdf())) {
            String base64 = label.getLabelPdf().trim();
            // Some carriers return "data:application/pdf;base64,..." — strip prefix.
            int comma = base64.indexOf(',');
            if (base64.startsWith("data:") && comma > 0) {
                base64 = base64.substring(comma + 1);
            }
            try {
                return Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException ignored) {
                // Not base64 — fall through to URL fetch.
            }
        }
        if (!StringUtils.hasText(label.getLabelUrl())) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(label.getLabelUrl()))
                    .timeout(LABEL_FETCH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) return null;
            return response.body();
        } catch (Exception ex) {
            log.warn("Bulk-label PDF fetch failed for {}: {}",
                    label.getLabelUrl(), ex.getMessage());
            return null;
        }
    }

    private static long[] parseOrderNumbers(String csv) {
        if (csv == null || csv.isBlank()) return new long[0];
        String[] parts = csv.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Long.parseLong(parts[i].trim()); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }

    @Override
    public ApiResponse<BulkLabelJobDTO> status(Long jobId) {
        Optional<BulkLabelJob> job = jobRepository.findById(jobId);
        return job.map(b -> success(toDto(b), b.getStatus()))
                .orElseGet(() -> failure(HttpStatus.NOT_FOUND, "Bulk-label job " + jobId + " not found."));
    }

    @Override
    public Optional<BulkLabelJob> findRaw(Long jobId) {
        return jobRepository.findById(jobId);
    }

    static BulkLabelJobDTO toDto(BulkLabelJob j) {
        return BulkLabelJobDTO.builder()
                .id(j.getId())
                .status(j.getStatus())
                .totalCount(j.getTotalCount())
                .successfulCount(j.getSuccessfulCount())
                .failedCount(j.getFailedCount())
                .failureMessage(j.getFailureMessage())
                .createdAt(j.getCreatedAt())
                .startedAt(j.getStartedAt())
                .completedAt(j.getCompletedAt())
                .downloadable(StringUtils.hasText(j.getResultZipBase64()))
                .build();
    }

    private static ApiResponse<BulkLabelJobDTO> success(BulkLabelJobDTO data, String message) {
        return ApiResponse.<BulkLabelJobDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<BulkLabelJobDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<BulkLabelJobDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Test / test-hook: force the executors to drain — used in tests
     *  to wait for the async work to finish deterministically. Not
     *  called by production code. */
    public void awaitQuiescenceForTests() {
        try {
            dispatchExecutor.shutdown();
            dispatchExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
