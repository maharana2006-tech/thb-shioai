package com.multiship.backend.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightSplitTest {

    private static BigDecimal sum(BigDecimal[] shares) {
        return Arrays.stream(shares).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean[] all(int n) {
        boolean[] b = new boolean[n];
        Arrays.fill(b, true);
        return b;
    }

    /** The three FedEx COMMODITYWEIGHT.GREATERTHAN.PACKAGEWEIGHT orders from the 2026-09-10 smoke run. */
    @Test
    void sharesNeverOutweighTheParcel() {
        Object[][] cases = {
                {"4.6", new int[]{7, 7, 5}},          // LS0910-0009: half-up gave 4.601
                {"13", new int[]{3, 7, 2, 1, 1}},     // LS0910-0033: half-up gave 13.001
                {"9.2", new int[]{3, 1, 3, 8, 4}},    // LS0910-0047: half-up gave 9.201
        };
        for (Object[] c : cases) {
            BigDecimal total = new BigDecimal((String) c[0]);
            int[] qty = (int[]) c[1];
            BigDecimal[] shares = WeightSplit.byQuantity(total, qty, all(qty.length));
            assertEquals(0, sum(shares).compareTo(total), "shares must add up to exactly " + total);
            for (BigDecimal s : shares) assertTrue(s.signum() > 0);
        }
    }

    @Test
    void evenSplitIsUnchanged() {
        BigDecimal[] shares = WeightSplit.byQuantity(new BigDecimal("10.0"), new int[]{2, 3}, all(2));
        assertEquals(new BigDecimal("4.000"), shares[0]);
        assertEquals(new BigDecimal("6.000"), shares[1]);
    }

    @Test
    void linesWithTheirOwnWeightTakeNoShare() {
        // 10 kg over qty 2 (own weight) + 3: the spread line gets its 3/5 = 6.000, never more
        BigDecimal[] shares = WeightSplit.byQuantity(new BigDecimal("10"), new int[]{2, 3}, new boolean[]{false, true});
        assertNull(shares[0]);
        assertEquals(new BigDecimal("6.000"), shares[1]);
    }

    @Test
    void missingTotalGivesNoShares() {
        BigDecimal[] shares = WeightSplit.byQuantity(null, new int[]{1, 2}, all(2));
        assertNull(shares[0]);
        assertNull(shares[1]);
    }
}
