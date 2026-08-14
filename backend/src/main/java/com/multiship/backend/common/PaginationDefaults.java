package com.multiship.backend.common;

/**
 * Sprint 51 AC-L1 — single source of truth for `page` / `size` query
 * defaults across the REST surface.
 *
 * <p>Historically list endpoints picked their own defaultValue on the
 * @RequestParam (some 20, some 25, some 50) and their own upper bound
 * for size clamping (some 100, some 200, some unset entirely). The mix
 * makes it hard for a frontend to reason about "what will I get if I
 * omit the page parameter", and lets a caller ask for size=100000 on a
 * hot endpoint that never got a Math.min guard.
 *
 * <p>Every list endpoint should reference this class:
 * <pre>
 *   @RequestParam(defaultValue = PaginationDefaults.DEFAULT_SIZE_STR) int size,
 *   ...
 *   int safeSize = PaginationDefaults.clamp(size);
 * </pre>
 */
public final class PaginationDefaults {

    /** Default rows per page when the caller omits `size`. */
    public static final int DEFAULT_SIZE = 25;

    /** Hard upper bound: any request for more is capped here. */
    public static final int MAX_SIZE = 100;

    /** String form for @RequestParam(defaultValue = ...) — the annotation
     *  requires a compile-time constant string. Kept in sync with DEFAULT_SIZE. */
    public static final String DEFAULT_SIZE_STR = "25";

    private PaginationDefaults() {
        // static-only holder — no instances.
    }

    /**
     * Clamp a caller-supplied size to [1, MAX_SIZE]. size <= 0 collapses
     * to DEFAULT_SIZE (rather than 0, which would produce a nonsense
     * empty page); anything above MAX_SIZE is capped to MAX_SIZE.
     */
    public static int clamp(int size) {
        if (size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }
}
