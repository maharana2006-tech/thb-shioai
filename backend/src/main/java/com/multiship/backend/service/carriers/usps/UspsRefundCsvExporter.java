package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsRefundCsvRowDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * PR-D — builds a PS 3533 refund-request CSV for the platform admin to
 * upload to the USPS Business Customer Gateway eVS Refund portal.
 *
 * <p>USPS has no REST API for the refund workflow — batch CSV upload
 * is the only path. This service queries VOIDED USPS shipments that
 * haven't yet been reconciled (
 * {@link OrderTrackingRepository#findVoidedUnreconciledUspsBetween})
 * in the caller-specified date range and emits one CSV row per
 * eligible tracking number. The CRID/MID pair the row includes is
 * pulled from the associated {@link CarrierAccountRef} so USPS books
 * the refund against the correct mailer.
 *
 * <p><b>REGULATORY_REFERENCE</b>: PS Form 3533, "Application for Refund
 * of Fees, Products and Withdrawal of Customer Accounts" —
 * <a href="https://about.usps.com/forms/ps3533.pdf">USPS forms library</a>.
 * eVS Refund upload variant is described in Publication 205, Chapter 6.
 * Operator-verify {@link #PS3533_HEADER} against the current spec before
 * production — USPS occasionally revises the column set between annual
 * mail-service announcements; a wrong header rejects the whole batch at
 * the portal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsRefundCsvExporter {

    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    /**
     * Header row required by the eVS Refund upload format. Order-sensitive
     * — USPS parses positionally.
     */
    public static final List<String> PS3533_HEADER = List.of(
            "TrackingNumber",
            "MailerId",
            "CustomerRegistrationId",
            "OriginalPostage",
            "LabelDate",
            "ReasonCode",
            "CustomerReference"
    );

    /** All rows use the same reason — the operator only ever generates
     *  this CSV for labels they voided before use. Damaged / other reason
     *  codes go through a separate USPS workflow this platform doesn't
     *  expose. */
    private static final String REASON_UNUSED = "UNUSED";

    /** ISO local date is what the eVS Refund portal accepts. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Build the CSV rows for the given date range.
     *
     * <p>Defaults when either bound is null:
     * <ul>
     *   <li>{@code from} null → 30 days before {@code to} (or 30 days
     *       ago from now if {@code to} is also null). Matches the
     *       operational cadence of "monthly refund batches" without
     *       forcing operators to always supply a start date.</li>
     *   <li>{@code to} null → now.</li>
     * </ul>
     */
    public List<UspsRefundCsvRowDTO> buildRows(LocalDateTime from, LocalDateTime to) {
        LocalDateTime resolvedTo = to != null ? to : LocalDateTime.now();
        LocalDateTime resolvedFrom = from != null ? from : resolvedTo.minusDays(30);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException(
                    "startDate must be on or before endDate (got "
                            + resolvedFrom + " > " + resolvedTo + ").");
        }

        List<OrderTracking> candidates = orderTrackingRepository
                .findVoidedUnreconciledUspsBetween(resolvedFrom, resolvedTo);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Batch-fetch CarrierAccountRef rows by (accountNumber, USPS)
        // so we don't do N+1 queries when the batch is large.
        Map<String, CarrierAccountRef> accountsByNumber = loadUspsAccounts(candidates);

        List<UspsRefundCsvRowDTO> rows = new ArrayList<>(candidates.size());
        for (OrderTracking t : candidates) {
            String accountNumber = t.getAccountNumber();
            CarrierAccountRef acct = accountNumber == null
                    ? null
                    : accountsByNumber.get(accountNumber.toLowerCase(Locale.ROOT));
            String mid = acct != null ? acct.getUspsDirectMid() : null;
            String crid = acct != null ? acct.getUspsDirectCrid() : null;
            LocalDate labelDate = t.getLabelGeneratedAt() != null
                    ? t.getLabelGeneratedAt().toLocalDate()
                    : null;
            BigDecimal postage = t.getCarrierAmount();
            String reference = t.getOrderNo() != null ? t.getOrderNo().toString() : null;
            rows.add(new UspsRefundCsvRowDTO(
                    t.getTrackingNumber(),
                    mid,
                    crid,
                    postage,
                    labelDate,
                    REASON_UNUSED,
                    reference
            ));
        }
        return rows;
    }

    /**
     * Serialize a row list to the exact PS 3533 CSV wire format —
     * header + one line per row. Empty rows list still emits the
     * header so the file is a valid CSV even in "no refunds pending"
     * runs (some operators upload it anyway to keep an audit paper
     * trail).
     */
    public byte[] toCsvBytes(List<UspsRefundCsvRowDTO> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", PS3533_HEADER)).append("\r\n");
        for (UspsRefundCsvRowDTO r : rows) {
            sb.append(escape(r.trackingNumber())).append(',');
            sb.append(escape(r.mailerId())).append(',');
            sb.append(escape(r.customerRegistrationId())).append(',');
            sb.append(r.originalPostage() != null ? r.originalPostage().toPlainString() : "").append(',');
            sb.append(r.labelDate() != null ? DATE_FMT.format(r.labelDate()) : "").append(',');
            sb.append(escape(r.reasonCode())).append(',');
            sb.append(escape(r.customerReference()));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Convenience for the admin controller: query + serialize in one
     * step. Returns the fully-formed CSV bytes ready for the
     * {@code Content-Disposition} attachment stream.
     */
    public byte[] exportCsv(LocalDateTime from, LocalDateTime to) {
        List<UspsRefundCsvRowDTO> rows = buildRows(from, to);
        log.info("USPS PS 3533 refund CSV export: {} row(s) between {} and {}.",
                rows.size(), from, to);
        return toCsvBytes(rows);
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Load the USPS CarrierAccountRef row for every distinct account
     * number in {@code trackings}. Returned map is keyed on lower-cased
     * account number for case-insensitive lookup.
     */
    private Map<String, CarrierAccountRef> loadUspsAccounts(List<OrderTracking> trackings) {
        Map<String, CarrierAccountRef> out = new HashMap<>();
        for (OrderTracking t : trackings) {
            String acctNo = t.getAccountNumber();
            if (acctNo == null || acctNo.isBlank()) continue;
            String key = acctNo.toLowerCase(Locale.ROOT);
            if (out.containsKey(key)) continue;
            Optional<CarrierAccountRef> found = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(acctNo, "USPS");
            found.ifPresent(a -> out.put(key, a));
        }
        return out;
    }

    /**
     * Minimal CSV escape — wrap values that contain a comma, quote or
     * newline in double quotes and double any embedded quotes. Nulls
     * are emitted as an empty field so USPS can distinguish "unknown"
     * from "zero".
     */
    private static String escape(String v) {
        if (v == null) return "";
        boolean needsQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needsQuote) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
