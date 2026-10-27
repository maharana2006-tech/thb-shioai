package com.multiship.backend.service;

import com.multiship.backend.dto.ReportFilters;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.RateShopHistoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.hibernate.jpa.HibernateHints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.multiship.backend.model.RateShopHistory;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 45 — CSV streaming impls. Uses native SQL through EntityManager
 * so joins across Order + OrderTracking + Client stay flexible without
 * introducing a projection DTO per report.
 *
 * <p>Filters are applied by conditionally appending WHERE clauses with
 * named parameters. Streaming writes one row at a time to the given
 * OutputStream, flushing after each batch so large exports don't need
 * to buffer.
 */
@Service
public class ReportServiceImpl implements ReportService {

    private final EntityManager em;
    private final OrderRepository orderRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final RateShopHistoryRepository rateShopHistoryRepository;
    /**
     * Sprint 50 Tier 0.5 PR E - clamp filter.customerNo so a scoped USER
     * exports only their own tenant's rows. Optional so pre-PR-E tests
     * that build the service directly still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Autowired
    public ReportServiceImpl(EntityManager em,
                             OrderRepository orderRepository,
                             OrderTrackingRepository orderTrackingRepository,
                             RateShopHistoryRepository rateShopHistoryRepository) {
        this.em = em;
        this.orderRepository = orderRepository;
        this.orderTrackingRepository = orderTrackingRepository;
        this.rateShopHistoryRepository = rateShopHistoryRepository;
    }

    // ===== Orders + labels =====

    /**
     * Sprint 49 Tier 3 Fix 3 — flush every N rows so downloaders start
     * receiving data immediately AND we periodically clear the
     * EntityManager's L1 cache so a million-row export doesn't OOM.
     */
    private static final int STREAM_FLUSH_INTERVAL = 500;

    @Override
    @Transactional(readOnly = true, timeout = 300)
    public void streamOrdersCsv(ReportFilters f, OutputStream out) {
        clampCustomerNo(f);
        Query q = ReportSql.orders(f).query(em);
        q.setHint(HibernateHints.HINT_FETCH_SIZE, 200);
        writeRows(q, out, 12,
                "orderNo,customerNo,tenantId,shipDate,trackingNumber,status,carrierAmount,billableAmount,currency,destCountry,weight,shipVia");
    }

    @Override
    @Transactional(readOnly = true, timeout = 300)
    public void streamTrackingCsv(ReportFilters f, OutputStream out) {
        clampCustomerNo(f);
        Query q = ReportSql.tracking(f).query(em);
        q.setHint(HibernateHints.HINT_FETCH_SIZE, 200);
        writeRows(q, out, 8, "orderNo,trackingNumber,status,createdAt,updatedAt,customerNo,tenantId,destCountry");
    }

    // ===== Rate-shop history =====

    @Override
    @Transactional(readOnly = true)
    public void streamRateShopCsv(ReportFilters f, OutputStream out) {
        // Sprint 50 Tier 0.5 PR E - clamp f.customerNo for consistency
        // with the other CSV exports; a scoped USER sees only their own.
        clampCustomerNo(f);
        // BUG-166 — the old JPQL used "(:x IS NULL OR col = :x)" for every
        // filter; Postgres cannot type a bind that is only ever compared to
        // NULL and answered "could not determine data type of parameter".
        // Build only the predicates that are present instead.
        Specification<RateShopHistory> spec = (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.getCustomerNo() != null) p.add(cb.equal(root.get("customerNo"), f.getCustomerNo()));
            if (f.getFrom() != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.getFrom()));
            if (f.getTo() != null) p.add(cb.lessThanOrEqualTo(root.get("createdAt"), f.getTo()));
            if (f.getDestCountry() != null) {
                p.add(cb.equal(cb.upper(root.get("destCountry")), f.getDestCountry().toUpperCase(Locale.ROOT)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        List<RateShopHistory> rows = rateShopHistoryRepository.findAll(spec, Sort.by("createdAt").ascending());
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("id,createdAt,customerNo,destCountry,weight,weightUnit,"
                    + "carriersQueried,carriersReturned,"
                    + "cheapestCarrier,cheapestService,cheapestAmount,cheapestCurrency,"
                    + "pickedCarrier,pickedService,pickedAmount,savingsAmount");
            for (var r : rows) {
                w.println(csv(r.getId()) + "," + csv(r.getCreatedAt()) + ","
                        + csv(r.getCustomerNo()) + "," + csv(r.getDestCountry()) + ","
                        + csv(r.getWeight()) + "," + csv(r.getWeightUnit()) + ","
                        + csv(r.getCarriersQueried()) + "," + csv(r.getCarriersReturned()) + ","
                        + csv(r.getCheapestCarrier()) + "," + csv(r.getCheapestService()) + ","
                        + csv(r.getCheapestAmount()) + "," + csv(r.getCheapestCurrency()) + ","
                        + csv(r.getPickedCarrier()) + "," + csv(r.getPickedService()) + ","
                        + csv(r.getPickedAmount()) + "," + csv(r.getSavingsAmount()));
            }
            w.flush();
        }
    }

    // ===== Billing rollup =====

    @Override
    @Transactional(readOnly = true)
    public void streamBillingCsv(ReportFilters f, OutputStream out) {
        clampCustomerNo(f);
        Query q = ReportSql.billing(f).query(em);
        writeRows(q, out, 6, "client,yearMonth,labels,totalSpend,avgSpend,currency");
    }

    // ===== helpers =====

    /**
     * Stream a native query as CSV. The query is executed (first row fetched)
     * <em>before</em> the header line is written, so a SQL failure surfaces
     * as the usual JSON error instead of a committed 200 with an empty body
     * (BUG-166: the old code had already sent the CSV headers when the
     * query died, and the error handler could not write into text/csv).
     */
    private void writeRows(Query q, OutputStream out, int columns, String header) {
        try (Stream<?> stream = q.getResultStream()) {
            Iterator<?> rows = stream.iterator();
            boolean more = rows.hasNext();
            try (PrintWriter w = new PrintWriter(out)) {
                w.println(header);
                int rowCount = 0;
                while (more) {
                    Object[] r = typedRow(rows.next(), columns);
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i < columns; i++) {
                        if (i > 0) line.append(',');
                        line.append(csv(r[i]));
                    }
                    w.println(line);
                    if (++rowCount % STREAM_FLUSH_INTERVAL == 0) {
                        w.flush();
                        em.clear();  // keep L1 cache bounded across the export
                    }
                    more = rows.hasNext();
                }
                w.flush();
            }
        }
    }

    /**
     * Audit-fix #6 — every native-query result row lands as an Object[]
     * whose length matches the SELECT column count. Downstream code
     * reads r[0]..r[N] positionally; adding or removing a column in the
     * SELECT used to silently produce ArrayIndexOutOfBoundsException at
     * runtime OR shift every subsequent field into the wrong CSV
     * position (values misaligned but no crash). This helper turns both
     * failure modes into an immediate, self-describing IllegalStateException
     * that surfaces on the first row.
     */
    static Object[] typedRow(Object row, int expectedColumns) {
        if (!(row instanceof Object[] arr)) {
            throw new IllegalStateException(
                    "Report SQL returned a scalar where " + expectedColumns
                            + "-column tuple was expected — check for column-order drift.");
        }
        if (arr.length != expectedColumns) {
            throw new IllegalStateException(
                    "Report SQL returned " + arr.length + " columns, expected "
                            + expectedColumns + " — check for column-order drift in the SELECT.");
        }
        return arr;
    }

    /**
     * Sprint 50 Tier 0.5 PR E - null-safe helper: when the enforcer isn't
     * wired (pure-unit tests) fall back to no-op; otherwise clamp
     * f.customerNo so a scoped USER exports only their own tenant.
     */
    private void clampCustomerNo(ReportFilters f) {
        if (tenantScope == null || f == null) return;
        f.setCustomerNo(tenantScope.clampClientCode(f.getCustomerNo()));
    }


    /**
     * CSV-safe stringify: nulls → "", commas / quotes / newlines quoted.
     *
     * <p>Sprint 49 Tier 1 — formula-injection guard. Excel and other
     * spreadsheet consumers execute cells beginning with {@code =},
     * {@code +}, {@code -}, {@code @}, TAB, or CR as formulas. A hostile
     * customer name like {@code =cmd|'/c calc'!A1} would fire on export
     * open. Prefix such values with a single quote so the sheet renders
     * them as literal text.
     */
    // Not consolidated into CsvUtil.csvCell: this variant intentionally differs
    // (BigDecimal/LocalDate(Time) coercion before escaping) and is already
    // formula-injection-safe via escapeFormulaInjection below. Routing it
    // through CsvUtil would change report number/date output.
    static String csv(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof BigDecimal bd) s = bd.toPlainString();
        else if (v instanceof LocalDateTime ldt) s = ldt.toString();
        else if (v instanceof java.time.LocalDate ld) s = ld.toString();
        else s = v.toString();
        s = escapeFormulaInjection(s);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static String escapeFormulaInjection(String s) {
        if (s == null || s.isEmpty()) return s;
        char first = s.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + s;
        }
        return s;
    }
}
