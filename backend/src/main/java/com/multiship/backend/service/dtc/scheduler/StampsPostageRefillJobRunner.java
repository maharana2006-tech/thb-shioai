package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.service.carriers.StampsPostageService;
import com.multiship.backend.service.carriers.StampsPostageService.Balance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * USPS_POSTAGE_REFILL (V140) — keeps USPS (Stamps.com) postage topped up so a
 * bulk run never fails for lack of postage. Each run reads every authorized
 * USPS account's balance; when it is below the threshold, it adds the refill
 * amount via {@link StampsPostageService#addFunds} (idempotent, reserve-then-
 * execute, audited). Stamps.com retired its own auto-buy page, so this is the
 * app-side replacement. Starts OFF.
 *
 * <p>Params (editable on the DTC Scheduler page): {@code thresholdUsd}
 * (default 25) and {@code refillUsd} (default 50).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StampsPostageRefillJobRunner implements DtcSchedulerJobRunner {

    public static final String KEY = "USPS_POSTAGE_REFILL";
    static final String REQUESTED_BY = "system:postage-refill";
    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("25");
    static final BigDecimal DEFAULT_REFILL = new BigDecimal("50");

    private final CarrierAccountRefRepository accounts;
    private final StampsPostageService postage;

    @Override
    public String jobKey() {
        return KEY;
    }

    @Override
    public Outcome run(Map<String, Object> params) {
        BigDecimal threshold = money(params.get("thresholdUsd"), DEFAULT_THRESHOLD);
        BigDecimal refill = money(params.get("refillUsd"), DEFAULT_REFILL);

        List<CarrierAccountRef> usps = accounts.findByActiveTrue().stream()
                .filter(a -> "USPS".equalsIgnoreCase(a.getCarrierCode()))
                .filter(a -> StringUtils.hasText(a.getStampsRefreshToken()))
                .toList();
        if (usps.isEmpty()) {
            return Outcome.success("No authorized USPS accounts to check.");
        }

        int healthy = 0, refilled = 0, failed = 0;
        StringBuilder detail = new StringBuilder();
        for (CarrierAccountRef a : usps) {
            String label = a.getAccountNumber() == null ? ("#" + a.getId()) : a.getAccountNumber();
            try {
                Balance b = postage.balance(a.getId());
                if (b.available() != null && b.available().compareTo(threshold) >= 0) {
                    healthy++;
                    continue;
                }
                // Low — top up. addFunds enforces min/cap and audits the result.
                postage.addFunds(a.getId(), refill, null, REQUESTED_BY);
                refilled++;
                log.info("[Postage refill] {} was low (< {}) — added {}", label, threshold, refill);
                append(detail, label + ": refilled " + refill);
            } catch (Exception e) {
                failed++;
                log.warn("[Postage refill] {} could not be refilled: {}", label, e.getMessage());
                append(detail, label + ": FAILED (" + e.getMessage() + ")");
            }
        }

        String msg = String.format("%d USPS account(s): %d healthy, %d refilled, %d failed.%s",
                usps.size(), healthy, refilled, failed,
                detail.length() > 0 ? " [" + detail + "]" : "");
        if (failed > 0 && refilled == 0) {
            return new Outcome(DtcSchedulerEngine.FAILED, msg);
        }
        return Outcome.success(msg);
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append("; ");
        sb.append(s);
    }

    /** Parse a positive USD amount from a job param, else the default. */
    private static BigDecimal money(Object v, BigDecimal fallback) {
        if (v instanceof Number n && n.doubleValue() > 0) {
            return new BigDecimal(n.toString());
        }
        if (v != null) {
            try {
                BigDecimal b = new BigDecimal(v.toString().trim());
                if (b.signum() > 0) return b;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
