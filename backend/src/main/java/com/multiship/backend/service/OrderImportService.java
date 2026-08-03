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

    /** Persist a list of previewed rows. Rows with errors are skipped
     *  (validity is the client's responsibility to check first). */
    ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy);

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
