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

    /** Persist a list of previewed rows. Rows with errors are skipped
     *  (validity is the client's responsibility to check first). */
    ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy);

    /**
     * Save the previewed rows as a data record in the Data History (does NOT
     * generate labels). Persists an {@link com.multiship.backend.model.ImportBatch}
     * with the full row payload; returns the summary + batch id.
     */
    ApiResponse<OrderImportPreviewDTO> save(List<OrderImportRowDTO> rows, String requestedBy, String fileName);

    /** All saved imports, newest first (row payload omitted from the list). */
    java.util.List<com.multiship.backend.dto.ImportBatchDTO> history();

    /** One saved import with its full row payload, or null if not found. */
    com.multiship.backend.dto.ImportBatchDTO historyDetail(Long id);

    /**
     * Generate carrier labels for a saved import batch, advancing its status
     * INITIATE → IN_PROGRESS → COMPLETE / PARTIAL_COMPLETE. Null if not found.
     */
    com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(Long id, String requestedBy);

    /**
     * Sprint 55 audit #302 F3.2 — generate labels for a batch, optionally
     * filtered to only rows whose current generatedStatus is NOT
     * {@code GENERATED}. Retry-safe: prevents duplicate carrier calls
     * (and duplicate billing) on rows that already succeeded.
     * onlyFailed=false preserves the original re-generate-all behavior.
     */
    com.multiship.backend.dto.ImportBatchDTO generateLabelsForBatch(Long id, String requestedBy, boolean onlyFailed);

    /** Generate a label for a single row of a saved batch. Null if not found. */
    com.multiship.backend.dto.ImportBatchDTO generateLabelForRow(Long id, int rowNumber, String requestedBy);

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
}
