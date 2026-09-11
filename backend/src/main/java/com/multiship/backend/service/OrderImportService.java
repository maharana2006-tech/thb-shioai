package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;

import java.io.InputStream;
import java.util.List;

/**
 * Sprint 40 — CSV / XLSX order import. Two-phase: {@link #preview}
 * parses the file into rows + validates each; the frontend renders the
 * preview and the operator can edit / discard bad rows before hitting
 * {@link #commit} to persist them.
 */
public interface OrderImportService {

    /** Detect format from filename and parse into row DTOs with per-row
     *  validation. Never throws — parse errors surface as a single
     *  error row so the caller sees them in the UI. */
    ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body);

    /**
     * Sprint 48 — same as {@link #preview(String, InputStream)} but attaches
     * a non-fatal warning to every row whose accountNumber differs from
     * the account the .xlsx template was scoped to. Frontend passes this
     * so operator edits that diverge from the template default are
     * visible without blocking the commit.
     *
     * <p>{@code expectedAccountId} null (or resolves to a missing / blank
     * account) = no divergence check; behaves exactly like the 2-arg
     * overload above.
     */
    ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body, Long expectedAccountId);

    /** Same, with {@code allowDuplicate=true} skipping the same-name / same-content
     *  guard so a legitimately re-sent file can be imported as a new batch. */
    ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body, Long expectedAccountId,
                                               boolean allowDuplicate);

    /** Persist a list of previewed rows. Rows with errors are skipped
     *  (validity is the client's responsibility to check first). */
    ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy);

    /**
     * Save the previewed rows as a data record in the Data History (does NOT
     * generate labels). Persists an {@link com.multiship.backend.model.ImportBatch}
     * with the full row payload; returns the summary + batch id.
     *
     * @param draft when {@code true} the whole batch is parked as a DRAFT even
     *   if some rows still have errors (they are held NEEDS_FIX). When
     *   {@code false} this is a final save and is rejected (422) unless every
     *   row is valid.
     */
    /** Same as {@code save(rows, requestedBy, fileName, draft)} with the duplicate-file
     *  guard skipped when {@code allowDuplicate} — the operator's "import anyway". */
    ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy,
                                            String fileName, boolean draft, boolean allowDuplicate);

    ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy,
                                            String fileName, boolean draft);

    /** Live (non-deleted) CSV/XLSX imports, newest first (row payload omitted).
     *  Excludes WMS/API-sourced batches — those surface via {@link #apiBatches()}. */
    java.util.List<com.multiship.backend.dto.ImportBatchDTO> history();

    /** Live batches from a non-file source (WMS pulls / external API), newest
     *  first — shown under the "API" section of All Orders, not Import history. */
    java.util.List<com.multiship.backend.dto.ImportBatchDTO> apiBatches();

    /** Soft-deleted imports, newest first — the Trash view. */
    java.util.List<com.multiship.backend.dto.ImportBatchDTO> deletedHistory();

    /** One saved import with its full row payload, or null if not found. */
    com.multiship.backend.dto.ImportBatchDTO historyDetail(Long id);

    /**
     * Soft-delete an import batch — moves it to Trash (sets deletedAt/deletedBy)
     * instead of removing it. Returns the updated DTO, or null if not found.
     * Idempotent: deleting an already-deleted batch is a no-op.
     */
    com.multiship.backend.dto.ImportBatchDTO softDeleteBatch(Long id, String requestedBy);

    /**
     * Restore a soft-deleted import batch from Trash (clears deletedAt/deletedBy).
     * Returns the updated DTO, or null if not found.
     */
    com.multiship.backend.dto.ImportBatchDTO restoreBatch(Long id);

    /**
     * Empty the Trash — PERMANENTLY (hard) delete every soft-deleted batch the
     * caller's tenant owns. This is irreversible. Returns the number purged.
     */
    int purgeTrash(String requestedBy);

    /**
     * Set a batch's bill-to account mode: "AUTO" (cascade) or "PLATFORM"
     * (house account). Persisted so the choice survives reloads. Returns the
     * updated DTO, or null if not found.
     */
    com.multiship.backend.dto.ImportBatchDTO setBillingMode(Long id, String mode, String requestedBy);

    /**
     * Generate carrier labels for a saved import batch, advancing its status
     * INITIATE → IN_PROGRESS → COMPLETE / PARTIAL_COMPLETE. Null if not found.
     */
    com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(Long id, String requestedBy);

    /**
     * Generate labels for a batch, with two independent options:
     *  - Sprint 55 audit #302 F3.2 — {@code onlyFailed}=true filters to rows
     *    whose current generatedStatus is NOT {@code GENERATED} (retry-safe:
     *    prevents duplicate carrier calls + billing on already-succeeded rows).
     *  - {@code usePlatformAccount}=true forces the platform (house) account for
     *    every row (Data History "Use platform account" option).
     */
    com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(
            Long id, String requestedBy, boolean onlyFailed, boolean usePlatformAccount);

    /** As above; {@code allowDuplicate}=true skips the "already labelled" confirmation gate. */
    com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(
            Long id, String requestedBy, boolean onlyFailed, boolean usePlatformAccount, boolean allowDuplicate);

    /** Generate a label for a single row of a saved batch. Null if not found. */
    com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy);

    /** As above; {@code allowDuplicate}=true skips the "already labelled" confirmation gate. */
    com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy, boolean allowDuplicate);

    /**
     * Live label-generation progress for a batch, so the UI can show a real
     * "X of N" bar while a generate/retry runs. {@code running} is false (with
     * done=total=0) when no generation is in flight for the batch.
     */
    record GenProgressView(int done, int total, boolean running, String note) {
        public GenProgressView(int done, int total, boolean running) { this(done, total, running, null); }
    }

    /** Snapshot the in-flight generation progress for {@code id}. Never null. */
    GenProgressView generationProgress(Long id);

    /**
     * Import I-3 — cooperatively cancel an in-flight generate-labels-for-batch
     * run. Marks the batch with a flag that worker groups poll before
     * invoking the carrier; already-in-flight carrier calls run to
     * completion because we can't interrupt a paid label mid-request
     * without leaking it.
     *
     * <p>404 if the batch id is unknown, 409 if the batch is already in
     * a terminal state (COMPLETE / PARTIAL_COMPLETE / FAILED / CANCELLED).
     * Tenant-scoped: a scoped USER cannot cancel another tenant's batch.
     */
    ApiResponse<String> cancelGeneration(Long id);

    /**
     * Sprint 51 — correct one row of a saved import in place (Data History
     * inline edit). Applies the edited row, re-runs the full validation
     * pipeline over the whole batch, re-stamps each ungenerated row as
     * SAVED / NEEDS_FIX, and recomputes the batch's saved / invalid counts
     * and status. A row that already has a label (GENERATED) is not edited.
     * Null if the batch is not found.
     */
    com.multiship.backend.dto.ImportBatchDTO updateBatchRow(
            Long id, int rowNumber, OrderImportRowDTO edited, String requestedBy);

    /**
     * Sprint 48 — dry-run validation on rows the operator may have edited
     * post-preview. Runs the same pipeline as {@link #preview(String, InputStream)}
     * (sanitize → resolveNamesToCodes → validateRow → validateInternationalItems)
     * without touching a file OR persisting anything. Returns rows with
     * updated {@code errors}/{@code warnings} lists so the preview table
     * can refresh in place.
     */
    ApiResponse<OrderImportPreviewDTO> validate(List<OrderImportRowDTO> rows);

    /**
     * Sprint 48 — per-row address validation via each row's picked
     * carrier. For every row with a recipient block + carrierCode, calls
     * the carrier's {@code validateAddress} connector. Invalid rows get
     * a non-blocking warning appended. Rows without a picked carrier
     * are skipped silently. Never mutates errors — this is a WARNING
     * layer, not a hard-block.
     */
    ApiResponse<OrderImportPreviewDTO> validateAddresses(List<OrderImportRowDTO> rows);

    /** Canonical CSV template — comma-separated header line + one
     *  sample row. Returned as a byte[] with UTF-8 encoding. */
    byte[] csvTemplate();

    /**
     * Sprint 48 — .xlsx template with data validation dropdowns, sample
     * rows, and an operator-facing instructions block. When {@code accountId}
     * is supplied, the template is scoped to that account: the sample
     * accountNumber cell is prefilled, carrierCode is locked to the
     * account's carrier, and the serviceType / packageType dropdowns
     * only list options for that carrier. Null accountId = generic
     * template with every enabled carrier's options offered.
     *
     * <p>Returns the raw .xlsx bytes; the controller wires the
     * Content-Type / filename headers.
     */
    byte[] xlsxTemplate(Long accountId);

    // ── Staging: upload → validate → Save (2026-09-11 bulk-upload restructure) ──
    // Nothing reaches Import history until Save, and Save writes only orders whose
    // every row is valid. Invalid orders stay staged (editable / downloadable).

    /** Parse + validate a file into staging. 409 when the same file is already staged or imported. */
    ApiResponse<com.multiship.backend.dto.StagingUploadDTO> stageUpload(
            String filename, java.io.InputStream body, boolean allowDuplicate, String requestedBy);

    ApiResponse<com.multiship.backend.dto.StagingUploadDTO> getStaging(Long id, String requestedBy);

    /** Edit one staged row; the whole upload is re-validated. Saved rows are read-only. */
    ApiResponse<com.multiship.backend.dto.StagingUploadDTO> updateStagingRow(
            Long id, int rowNumber, OrderImportRowDTO edited, String requestedBy);

    /** Save the fully valid, not-yet-saved orders to Import history as a new batch. */
    ApiResponse<com.multiship.backend.dto.StagingUploadDTO> saveStaging(Long id, String requestedBy);

    ApiResponse<com.multiship.backend.dto.StagingUploadDTO> discardStaging(Long id, String requestedBy);

    /** Every row of each unsaved invalid order, in template columns + an "errors" column. Null when none. */
    StagingErrorFile stagingErrorFile(Long id, String format, String requestedBy);

    record StagingErrorFile(byte[] bytes, String fileName, String contentType) {}
}
