package com.multiship.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Splits a parcel weight across commodity lines in proportion to quantity.
 *
 * <p>Each share used to be rounded half-up on its own, so the lines could add up
 * to MORE than the parcel (4.6 kg over quantities 7/7/5 → 1.695 + 1.695 + 1.211
 * = 4.601 kg) and FedEx rejected the shipment with
 * {@code COMMODITYWEIGHT.GREATERTHAN.PACKAGEWEIGHT}. Here every share is rounded
 * DOWN to 3 decimals and the few thousandths left over go to the largest line, so
 * the shares add up to exactly the portion those lines are due — never more.
 */
public final class WeightSplit {

    private WeightSplit() {
    }

    /**
     * @param total    the weight to spread (parcel, or whole shipment)
     * @param qty      quantity of every line; values below 1 count as 1
     * @param spreadMe which lines take a share ({@code false} = the line declared its own weight)
     * @return the share of every line at 3 decimals; {@code null} for lines that take no
     *         share, and all {@code null} when {@code total} is missing or not positive
     */
    public static BigDecimal[] byQuantity(BigDecimal total, int[] qty, boolean[] spreadMe) {
        BigDecimal[] out = new BigDecimal[qty.length];
        int totalQty = 0;
        for (int q : qty) totalQty += Math.max(q, 1);
        if (total == null || total.signum() <= 0 || totalQty == 0) return out;

        int spreadQty = 0;
        int biggest = -1;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < qty.length; i++) {
            if (!spreadMe[i]) continue;
            int q = Math.max(qty[i], 1);
            out[i] = total.multiply(BigDecimal.valueOf(q))
                    .divide(BigDecimal.valueOf(totalQty), 3, RoundingMode.DOWN);
            spreadQty += q;
            sum = sum.add(out[i]);
            if (biggest < 0 || out[i].compareTo(out[biggest]) > 0) biggest = i;
        }
        if (biggest >= 0) {
            BigDecimal due = total.multiply(BigDecimal.valueOf(spreadQty))
                    .divide(BigDecimal.valueOf(totalQty), 3, RoundingMode.DOWN);
            BigDecimal left = due.subtract(sum);
            if (left.signum() > 0) out[biggest] = out[biggest].add(left);
        }
        return out;
    }
}
