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
}
