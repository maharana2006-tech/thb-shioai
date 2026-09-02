package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin diagnostic snapshot of everything that feeds the multi-package
 * label rendering path for a single order. Answers "why is order N still
 * showing only 1 label" without shell access to Postgres.
 *
 * <p>Read by {@code GET /orders/{orderNo}/label-state} — ADMIN-only, no
 * write side. Shape mirrors the two SQL queries that ops used to run:
 * one on {@code label_batch} (Order), one on {@code label_package}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelStateDTO {

    /** Order identifier (echo of the path variable). */
    private Integer orderNo;

    /** Present when {@code label_batch} has a row for this order. */
    private OrderState order;

    /** Present when {@code order_label_tracking} has a row. */
    private TrackingState tracking;

    /** One entry per {@code label_package} row, ordered by sequenceNumber. */
    private List<PackageState> labelPackages;

    /** Signals derived from the raw state — what the endpoints will actually do. */
    private Derived derived;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderState {
        private Integer packageCount;
        /** True when {@code label_batch.packages_json} is populated (V33). */
        private boolean hasPackagesJson;
        /** Character length of packages_json when present (0 when NULL). */
        private int packagesJsonLength;
        /** MANUAL | WMS | API | ERP | null. */
        private String source;
        /** GENERATED | ERROR | PENDING | ... */
        private String orderStatus;
        /** From the tracking row's is_label_generated — matches the queue's icon. */
        private boolean isLabelGenerated;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackingState {
        private String status;
        private String trackingNumber;
        /** Whether the shipment-level label bytes are stored at all. */
        private boolean hasLabelFilePath;
        /** Size of the stored artifact (bytes for base64, chars for URL). */
        private int labelFilePathLength;
        /** ZPL | PDF | URL | BASE64_UNKNOWN | NONE — output of the artifact resolver's sniffer. */
        private String detectedFormat;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageState {
        private Integer sequenceNumber;
        private String trackingNumber;
        private boolean hasLabelFilePath;
        private int labelFilePathLength;
        /** ZPL | PDF | URL | BASE64_UNKNOWN | NONE — same sniffer as TrackingState. */
        private String detectedFormat;
        /**
         * PRESENT | EMPTY_STORED | UNRESOLVABLE_FORMAT | FETCH_FAILED
         * <p>Runs the actual {@code LabelArtifactResolver.resolveAsBytes}
         * for this pkg + "ZPL" and reports what it returned. This is
         * exactly what the composite endpoint sees at render time.
         */
        private String resolverOutcomeZpl;
        /** Same, but for "PDF". */
        private String resolverOutcomePdf;
        /**
         * PR #551 — whether {@link com.multiship.backend.service.ZebrashRenderer}
         * can actually render the stored ZPL bytes to PNG. Discriminates
         * between "bytes are present" (what {@code resolverOutcomeZpl} tells
         * you) and "the render pipeline actually produces a PNG" (what the
         * FE {@code <img>} tag will see). FedEx sandbox ZPL uses malformed
         * {@code ^CF,0,0,0} commands that some renderers reject — bytes are
         * PRESENT but zebrash returns null → FE falls back to facsimile.
         * Values: RENDERABLE | RENDER_FAILED | SKIPPED (bytes absent).
         */
        private String zebrashOutcomeZpl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Derived {
        /**
         * {@code MAX(label_package.length, Order.packageCount)} — the value
         * {@link com.multiship.backend.controller.OrderController#effectivePkgCount}
         * would return. FE picker uses the same signal.
         */
        private int effectivePkgCount;
        /** {@code label_package} row count only — for comparing against effectivePkgCount. */
        private int labelPackageRowCount;
        /**
         * Sequence numbers that {@code effectivePkgCount} implies should exist
         * but don't (e.g. effective=2 but only pkg 1 has a row). Empty list
         * = every expected package has a persisted row.
         */
        private List<Integer> missingSequences;
        /**
         * Sequence numbers whose rows exist but resolve to empty via the ZPL
         * artifact resolver. These are the panels the composite silently
         * skips ("state 3/4/5" of the diagnosis matrix).
         */
        private List<Integer> unresolvableZplSequences;
        /**
         * Human-readable summary matching the matrix in memory
         * project_label_preview_audit.md — e.g. "STATE_1_OK",
         * "STATE_3_PKG_BLANK", "STATE_5_NO_ROWS_FACSIMILE_ONLY".
         */
        private String matrixVerdict;
    }
}
