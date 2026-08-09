package com.multiship.backend.util;

import com.multiship.backend.dto.CustomsCommodityDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 48 B11 — resolves declared value per package from commercial-invoice
 * commodities. Research-informed shape: some carriers need per-package values
 * (UPS strict, FedEx domestic), others need one shipment total (DHL, FedEx intl,
 * Stamps). Producing all three at once means each connector just projects what
 * its wire format wants.
 *
 * <p>Grouping rules:
 * <ul>
 *   <li>Every commodity has a {@code boxSeq} → group by boxSeq; per-package
 *       total is the sum of {@code unitValue × quantity} for its commodities.</li>
 *   <li>All commodities have {@code boxSeq == null} → treat as one logical
 *       package (backward-compat with legacy single-box CI). Shipment total
 *       becomes the sole per-package entry.</li>
 *   <li>Mixed (some assigned, some unassigned) → unassigned items collapse
 *       into box 1 (least surprising default; can be tightened later if
 *       ops surfaces false-assignment issues).</li>
 *   <li>Empty commodities list AND no {@code fallback} value → every
 *       per-package entry is {@code BigDecimal.ZERO}. Callers should
 *       decide whether to omit the wire field entirely at zero.</li>
 * </ul>
 *
 * <p>Sum invariant: {@code sum(perPackage) == shipmentTotal == customsTotal}
 * by construction (all three derive from the same commodity rows). This
 * eliminates the reconciliation risk DHL and Stamps impose
 * ({@code declaredValue ≥ sum(customs items)}).
 */
public final class DeclaredValueContextBuilder {

    private DeclaredValueContextBuilder() {}

    /**
     * @param perPackage      size = {@code packageCount}. Index 0 = box 1.
     * @param shipmentTotal   sum(perPackage). Same as customsTotal here.
     * @param customsTotal    same as shipmentTotal — items are the source of
     *                        both carriage and customs value.
     * @param currency        propagated from the caller (usually
     *                        {@code IntlShipmentBlockDTO.customsCurrency}
     *                        or {@code request.declaredValueCurrency}).
     */
    public record DeclaredValueContext(
            List<BigDecimal> perPackage,
            BigDecimal shipmentTotal,
            BigDecimal customsTotal,
            String currency) {
    }

    /**
     * @param commodities   CI commodities on the shipment (may be null / empty)
     * @param packageCount  total physical packages in the shipment (>= 1)
     * @param currency      currency to attach to the context (may be null;
     *                      caller falls back to "USD" typically)
     * @param fallback      shipment-level declared value to use when
     *                      commodities is empty; distributed evenly across
     *                      packages. Null → per-package is all zeros.
     */
    public static DeclaredValueContext build(List<CustomsCommodityDTO> commodities,
                                             int packageCount,
                                             String currency,
                                             BigDecimal fallback) {
        return build(commodities, packageCount, null, currency, fallback);
    }

    /**
     * Overload that maps commodities' {@code boxSeq} against a real
     * packages list — required when the shipment has been split into a
     * batch, because a package's {@code sequenceNumber} within a batch
     * no longer equals its array index. When {@code packages} is null,
     * behaves as the legacy 4-arg version (boxSeq → array index).
     */
    public static DeclaredValueContext build(List<CustomsCommodityDTO> commodities,
                                             int packageCount,
                                             List<com.multiship.backend.dto.PackageDetailDTO> packages,
                                             String currency,
                                             BigDecimal fallback) {
        int n = Math.max(1, packageCount);
        BigDecimal[] buckets = new BigDecimal[n];
        for (int i = 0; i < n; i++) buckets[i] = BigDecimal.ZERO;

        // Map boxSeq (original global sequence) → local array index.
        // When packages is null / seq numbers absent, fall through to
        // boxSeq-1 (legacy behaviour, correct for un-split shipments).
        java.util.Map<Integer, Integer> seqToIdx = new java.util.HashMap<>();
        if (packages != null) {
            for (int i = 0; i < packages.size(); i++) {
                Integer seq = packages.get(i).getSequenceNumber();
                if (seq != null) seqToIdx.put(seq, i);
            }
        }

        boolean anyItems = commodities != null && !commodities.isEmpty();
        if (anyItems) {
            for (CustomsCommodityDTO c : commodities) {
                if (c == null) continue;
                BigDecimal line = c.lineTotalValue();
                if (line == null) continue;
                Integer seq = c.getBoxSeq();
                int idx;
                if (seq == null || seq < 1) {
                    idx = 0;  // unassigned → first box (least-surprising default)
                } else if (!seqToIdx.isEmpty()) {
                    // Strict mode (packages supplied) — an item whose seq
                    // doesn't match any package in THIS batch belongs to a
                    // different batch (after auto-split) and is dropped here.
                    Integer mapped = seqToIdx.get(seq);
                    if (mapped == null) continue;
                    idx = mapped;
                } else {
                    // Legacy fallback: seq matches array position 1:1 when
                    // packages weren't supplied.
                    idx = Math.min(seq - 1, n - 1);
                }
                buckets[idx] = buckets[idx].add(line);
            }
        } else if (fallback != null && fallback.signum() > 0) {
            // No items — distribute the shipment-level fallback across boxes.
            // Even split with the remainder on the last box so the sum matches
            // exactly (no rounding drift).
            BigDecimal each = fallback.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal used = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                if (i == n - 1) {
                    buckets[i] = fallback.subtract(used);
                } else {
                    buckets[i] = each;
                    used = used.add(each);
                }
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal b : buckets) total = total.add(b);
        return new DeclaredValueContext(List.of(buckets), total, total, currency);
    }
}
