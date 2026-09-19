package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 55 audit #297 — preview counts for a shipping-mapping rule
 * delete. Frontend renders a "N packages + M warehouses will be
 * unlinked" summary in the confirm dialog before committing, matching
 * the pattern established by {@link ClientCascadePreviewDTO}.
 *
 * <p>Rule delete has no hard blockers today (rule + its join rows are
 * always safe to remove) but operators benefit from seeing what
 * downstream state will be cleaned so they don't nuke a heavily-
 * configured rule accidentally.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleCascadePreviewDTO {
    private Long ruleId;
    /** The mapping's order ship-method code (e.g. "GROUND_STANDARD"). */
    private String shipviaCd;
    /** Count of allowed-package rows attached to this rule. */
    private long allowedPackageCount;
    /** Count of restricted-warehouse rows attached to this rule. */
    private long allowedWarehouseCount;
    /** Per-client aliases for the same ship via code, in Settings → Code Maps.
     *  They are a second way to map the same code, so a delete that leaves them
     *  behind makes the code half-work. Removed only when the caller asks. */
    private long clientAliasCount;
    /** Other rules that also cover this code — when there are none, the code
     *  stops resolving entirely and files carrying it start failing. */
    private long otherRulesForCode;
}
