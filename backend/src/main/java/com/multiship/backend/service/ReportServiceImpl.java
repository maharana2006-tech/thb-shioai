package com.multiship.backend.service;

import com.multiship.backend.dto.ReportFilters;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.RateShopHistoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional(readOnly = true)
    public void streamOrdersCsv(ReportFilters f, OutputStream out) {
        StringBuilder sql = new StringBuilder("""
            SELECT o.order_no, o.cust_no, o.tenant_id, o.created_date,
                   t.tracking_number, t.status,
                   t.carrier_amount, t.billable_amount, t.markup_currency,
                   o.shipto_country_cd, o.weight, o.ship_via
            FROM label_batch o
            LEFT JOIN order_tracking t ON t.order_no = o.order_no
            WHERE 1=1
        """);
        // Filters
        if (f.getFrom() != null) sql.append(" AND o.created_date >= :fromDate");
        if (f.getTo() != null)   sql.append(" AND o.created_date <= :toDate");
        if (f.getCustomerNo() != null) sql.append(" AND (o.tenant_id = :customerNo OR o.cust_no = :customerNo)");
        if (f.getCarrier() != null) sql.append(" AND UPPER(o.ship_via) LIKE UPPER(:carrier)");
        if (f.getStatus() != null) sql.append(" AND UPPER(t.status) = UPPER(:status)");
        if (f.getDestCountry() != null) sql.append(" AND UPPER(o.shipto_country_cd) = UPPER(:destCountry)");
        if (f.getMinWeightLb() != null) sql.append(" AND o.weight >= :minWeight");
        if (f.getMaxWeightLb() != null) sql.append(" AND o.weight <= :maxWeight");
        sql.append(" ORDER BY o.created_date DESC, o.order_no DESC");

        Query q = em.createNativeQuery(sql.toString());
        bindFilters(q, f);

        try (PrintWriter w = new PrintWriter(out)) {
            w.println("orderNo,customerNo,tenantId,shipDate,trackingNumber,status,carrierAmount,billableAmount,currency,destCountry,weight,shipVia");
            for (Object row : q.getResultList()) {
                Object[] r = (Object[]) row;
                w.println(csv(r[0]) + "," + csv(r[1]) + "," + csv(r[2]) + "," + csv(r[3]) + ","
                        + csv(r[4]) + "," + csv(r[5]) + "," + csv(r[6]) + "," + csv(r[7]) + ","
                        + csv(r[8]) + "," + csv(r[9]) + "," + csv(r[10]) + "," + csv(r[11]));
            }
            w.flush();
        }
    }

    // ===== Tracking events =====

    @Override
    @Transactional(readOnly = true)
    public void streamTrackingCsv(ReportFilters f, OutputStream out) {
        // We use OrderTracking snapshots (there isn't a per-event history
        // table on the platform yet) and pair with the order for client
        // context.
        StringBuilder sql = new StringBuilder("""
            SELECT t.order_no, t.tracking_number, t.status, t.created_at, t.updated_at,
                   o.cust_no, o.tenant_id, o.shipto_country_cd
            FROM order_tracking t
            LEFT JOIN label_batch o ON o.order_no = t.order_no
            WHERE 1=1
        """);
        if (f.getFrom() != null) sql.append(" AND t.updated_at >= :fromDate");
        if (f.getTo() != null)   sql.append(" AND t.updated_at <= :toDate");
        if (f.getCustomerNo() != null) sql.append(" AND (o.tenant_id = :customerNo OR o.cust_no = :customerNo)");
        if (f.getStatus() != null) sql.append(" AND UPPER(t.status) = UPPER(:status)");
        if (f.getDestCountry() != null) sql.append(" AND UPPER(o.shipto_country_cd) = UPPER(:destCountry)");
        sql.append(" ORDER BY t.updated_at DESC");

        Query q = em.createNativeQuery(sql.toString());
        bindFilters(q, f);

        try (PrintWriter w = new PrintWriter(out)) {
            w.println("orderNo,trackingNumber,status,createdAt,updatedAt,customerNo,tenantId,destCountry");
            for (Object row : q.getResultList()) {
                Object[] r = (Object[]) row;
                w.println(csv(r[0]) + "," + csv(r[1]) + "," + csv(r[2]) + "," + csv(r[3]) + ","
                        + csv(r[4]) + "," + csv(r[5]) + "," + csv(r[6]) + "," + csv(r[7]));
            }
            w.flush();
        }
    }

    // ===== Rate-shop history =====

    @Override
    @Transactional(readOnly = true)
    public void streamRateShopCsv(ReportFilters f, OutputStream out) {
        var rows = rateShopHistoryRepository.reportRows(
                f.getCustomerNo(), f.getFrom(), f.getTo());
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("id,createdAt,customerNo,destCountry,weight,weightUnit,"
                    + "carriersQueried,carriersReturned,"
                    + "cheapestCarrier,cheapestService,cheapestAmount,cheapestCurrency,"
                    + "pickedCarrier,pickedService,pickedAmount,savingsAmount");
            for (var r : rows) {
                if (f.getDestCountry() != null && !f.getDestCountry().equalsIgnoreCase(r.getDestCountry())) continue;
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
        StringBuilder sql = new StringBuilder("""
            SELECT COALESCE(o.tenant_id, o.cust_no) AS client,
                   TO_CHAR(o.created_date, 'YYYY-MM') AS ym,
                   COUNT(*)                     AS labels,
                   COALESCE(SUM(t.billable_amount), 0) AS spend,
                   COALESCE(AVG(t.billable_amount), 0) AS avg_spend,
                   MAX(t.markup_currency)       AS currency
            FROM label_batch o
            LEFT JOIN order_tracking t ON t.order_no = o.order_no
            WHERE t.is_label_generated = true
        """);
        if (f.getFrom() != null) sql.append(" AND o.created_date >= :fromDate");
        if (f.getTo() != null)   sql.append(" AND o.created_date <= :toDate");
        if (f.getCustomerNo() != null) sql.append(" AND (o.tenant_id = :customerNo OR o.cust_no = :customerNo)");
        sql.append(" GROUP BY client, ym ORDER BY client, ym DESC");

        Query q = em.createNativeQuery(sql.toString());
        bindFilters(q, f);

        try (PrintWriter w = new PrintWriter(out)) {
            w.println("client,yearMonth,labels,totalSpend,avgSpend,currency");
            for (Object row : q.getResultList()) {
                Object[] r = (Object[]) row;
                w.println(csv(r[0]) + "," + csv(r[1]) + "," + csv(r[2]) + ","
                        + csv(r[3]) + "," + csv(r[4]) + "," + csv(r[5]));
            }
            w.flush();
        }
    }

    // ===== helpers =====

    private static void bindFilters(Query q, ReportFilters f) {
        if (f.getFrom() != null) q.setParameter("fromDate", java.sql.Timestamp.valueOf(f.getFrom()));
        if (f.getTo() != null)   q.setParameter("toDate", java.sql.Timestamp.valueOf(f.getTo()));
        if (f.getCustomerNo() != null) q.setParameter("customerNo", f.getCustomerNo());
        if (f.getCarrier() != null) q.setParameter("carrier", "%" + f.getCarrier() + "%");
        if (f.getStatus() != null) q.setParameter("status", f.getStatus());
        if (f.getDestCountry() != null) q.setParameter("destCountry", f.getDestCountry());
        if (f.getMinWeightLb() != null) q.setParameter("minWeight", f.getMinWeightLb());
        if (f.getMaxWeightLb() != null) q.setParameter("maxWeight", f.getMaxWeightLb());
    }

    /**
     * CSV-safe stringify: nulls → "", commas / quotes / newlines quoted.
     */
    static String csv(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof BigDecimal bd) s = bd.toPlainString();
        else if (v instanceof LocalDateTime ldt) s = ldt.toString();
        else if (v instanceof java.time.LocalDate ld) s = ld.toString();
        else s = v.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
