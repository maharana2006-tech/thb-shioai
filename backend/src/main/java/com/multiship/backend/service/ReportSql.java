package com.multiship.backend.service;

import com.multiship.backend.dto.ReportFilters;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One builder for the report datasets' native SQL. A filter is applied with
 * {@link #where}, which appends the clause <em>and</em> records the bind in
 * the same call, so the SQL and its parameters cannot drift apart (BUG-168:
 * the old code bound every filter it knew while the SQL only had some of
 * the placeholders, and Postgres refused the query).
 *
 * <p>Package-private and pure so {@code ReportSqlTest} can check the table
 * names against the JPA entities and the binds against the placeholders
 * without a database.
 */
final class ReportSql {

    private static final Pattern PLACEHOLDER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([a-z_][a-z0-9_]*)");

    private final StringBuilder sql;
    private final Map<String, Object> params = new LinkedHashMap<>();

    private ReportSql(String base) {
        this.sql = new StringBuilder(base);
    }

    /** Append {@code clause} and bind {@code name} when {@code value} is present. */
    ReportSql where(Object value, String clause, String name) {
        if (value != null) {
            sql.append(' ').append(clause);
            params.put(name, value);
        }
        return this;
    }

    ReportSql raw(String fragment) {
        sql.append(fragment);
        return this;
    }

    String sql() {
        return sql.toString();
    }

    Map<String, Object> params() {
        return Collections.unmodifiableMap(params);
    }

    Query query(EntityManager em) {
        Query q = em.createNativeQuery(sql());
        params.forEach(q::setParameter);
        return q;
    }

    Set<String> placeholders() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    Set<String> tables() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TABLE.matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ----- datasets -----

    private static ReportSql withCommonFilters(ReportSql s, ReportFilters f, String dateColumn) {
        return s.where(ts(f.getFrom()), "AND " + dateColumn + " >= :fromDate", "fromDate")
                .where(ts(f.getTo()), "AND " + dateColumn + " <= :toDate", "toDate")
                .where(f.getCustomerNo(), "AND (o.tenant_id = :customerNo OR o.cust_no = :customerNo)", "customerNo")
                .where(like(f.getCarrier()), "AND UPPER(o.ship_via) LIKE UPPER(:carrier)", "carrier")
                .where(f.getStatus(), "AND UPPER(t.status) = UPPER(:status)", "status")
                .where(f.getDestCountry(), "AND UPPER(o.shipto_country_cd) = UPPER(:destCountry)", "destCountry")
                .where(f.getMinWeightLb(), "AND o.weight >= :minWeight", "minWeight")
                .where(f.getMaxWeightLb(), "AND o.weight <= :maxWeight", "maxWeight");
    }

    static ReportSql orders(ReportFilters f) {
        ReportSql s = new ReportSql("""
            SELECT o.order_no, o.cust_no, o.tenant_id, o.created_date,
                   t.tracking_number, t.status,
                   t.carrier_amount, t.billable_amount, t.markup_currency,
                   o.shipto_country_cd, o.weight, o.ship_via
            FROM label_batch o
            LEFT JOIN order_label_tracking t ON t.order_no = o.order_no
            WHERE 1=1
        """);
        return withCommonFilters(s, f, "o.created_date")
                .raw(" ORDER BY o.created_date DESC, o.order_no DESC");
    }

    static ReportSql tracking(ReportFilters f) {
        ReportSql s = new ReportSql("""
            SELECT t.order_no, t.tracking_number, t.status, t.created_at, t.updated_at,
                   o.cust_no, o.tenant_id, o.shipto_country_cd
            FROM order_label_tracking t
            LEFT JOIN label_batch o ON o.order_no = t.order_no
            WHERE 1=1
        """);
        return withCommonFilters(s, f, "t.updated_at")
                .raw(" ORDER BY t.updated_at DESC");
    }

    static ReportSql billing(ReportFilters f) {
        ReportSql s = new ReportSql("""
            SELECT COALESCE(o.tenant_id, o.cust_no) AS client,
                   TO_CHAR(o.created_date, 'YYYY-MM') AS ym,
                   COUNT(*)                     AS labels,
                   COALESCE(SUM(t.billable_amount), 0) AS spend,
                   COALESCE(AVG(t.billable_amount), 0) AS avg_spend,
                   MAX(t.markup_currency)       AS currency
            FROM label_batch o
            LEFT JOIN order_label_tracking t ON t.order_no = o.order_no
            WHERE t.is_label_generated = true
        """);
        return withCommonFilters(s, f, "o.created_date")
                .raw(" GROUP BY client, ym ORDER BY client, ym DESC");
    }

    private static java.sql.Timestamp ts(java.time.LocalDateTime t) {
        return t == null ? null : java.sql.Timestamp.valueOf(t);
    }

    private static String like(String s) {
        return s == null ? null : "%" + s + "%";
    }
}
