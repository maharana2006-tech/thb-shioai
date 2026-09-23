package com.multiship.backend.service;

import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The unified "documents" view: one row per LABELLED order carrying every
 * artifact the label generation produced — tracking number, label (PDF/ZPL,
 * served by the existing /orders/{n}/label/* endpoints), commercial invoice
 * (when the order has customs data), and the billing statement figures
 * (carrier cost / markup / billable, straight off the tracking row).
 *
 * <p>Assembled from what the pipeline already persists — no duplicate blob
 * store; the row tells the UI which per-order download endpoints are live.
 */
@Service
@RequiredArgsConstructor
public class OrderDocumentSummaryService {

    private final OrderTrackingRepository orderTrackingRepository;
    private final OrderRepository orderRepository;
    private final OrderCustomsRepository orderCustomsRepository;

    /** Tenant clamp — a scoped USER sees only their own orders' documents. */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    /** One Documents-table row. */
    @Builder
    public record DocumentRow(
            Integer orderNo,
            String custNo,
            String recipientName,
            String city,
            String countryCode,
            String carrier,
            String trackingNumber,
            LocalDateTime generatedAt,
            /** true → customs data exists → the commercial invoice renders. */
            boolean hasInvoice,
            /** true → the label was cancelled at the carrier; downloads remain
             *  for record-keeping but the charge is reversed. */
            boolean voided,
            Integer packageCount,
            String accountNumber,
            BigDecimal carrierAmount,
            BigDecimal billableAmount,
            String markupKind,
            BigDecimal markupValue,
            String markupCurrency) {}

    @Transactional(readOnly = true)
    public List<DocumentRow> list(int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();

        List<OrderTracking> tracks = orderTrackingRepository
                .findGeneratedNewestFirst(PageRequest.of(0, capped));
        List<Integer> orderNos = tracks.stream()
                .map(OrderTracking::getOrderNo)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderNos.isEmpty()) return List.of();

        Map<Integer, Order> ordersByNo = new HashMap<>();
        for (Order o : orderRepository.findByOrderNoIn(orderNos)) {
            ordersByNo.putIfAbsent(o.getOrderNo(), o);
        }
        Set<String> customsOrderNos = new HashSet<>();
        for (OrderCustoms c : orderCustomsRepository.findByOrderNoIn(
                orderNos.stream().map(String::valueOf).toList())) {
            if (c.getOrderNo() != null) customsOrderNos.add(c.getOrderNo().trim());
        }

        List<DocumentRow> rows = new ArrayList<>(tracks.size());
        Set<Integer> seen = new HashSet<>();
        for (OrderTracking t : tracks) {
            Integer no = t.getOrderNo();
            if (no == null || !seen.add(no)) continue;
            Order order = ordersByNo.get(no);
            if (order == null) continue;
            // Tenant clamp — same owner rule as everywhere else: tenantId
            // first, custNo as the legacy fallback.
            if (scope.isPresent()) {
                String owner = StringUtils.hasText(order.getTenantId())
                        ? order.getTenantId() : order.getCustNo();
                if (owner == null || !owner.trim().equalsIgnoreCase(scope.get())) continue;
            }
            rows.add(DocumentRow.builder()
                    .orderNo(no)
                    .custNo(order.getCustNo())
                    .recipientName(order.getShipName())
                    .city(order.getShiptoCity())
                    .countryCode(order.getShiptoCountryCd())
                    .carrier(canonicalCarrier(t.getShipViaCd()))
                    .trackingNumber(t.getTrackingNumber())
                    .generatedAt(t.getLabelGeneratedAt())
                    .hasInvoice(customsOrderNos.contains(String.valueOf(no)))
                    .voided("VOIDED".equalsIgnoreCase(t.getStatus() == null ? "" : t.getStatus().trim()))
                    .packageCount(order.getPackageCount())
                    .accountNumber(t.getAccountNumber())
                    .carrierAmount(t.getCarrierAmount())
                    .billableAmount(t.getBillableAmount())
                    .markupKind(t.getMarkupKind())
                    .markupValue(t.getMarkupValue())
                    .markupCurrency(t.getMarkupCurrency())
                    .build());
        }
        return rows;
    }

    // ── One page at a time, filtered and sorted in the database ─────────────

    /** Rows per page at most — the Documents tab pages, it never lists everything. */
    private static final int MAX_PAGE_SIZE = 200;

    /** What the Documents tab asks for. Blank / null means "any". */
    public record Query(
            /** Order number, tracking number, recipient, client or city — any part. */
            String q,
            /** A canonical carrier (UPS, FEDEX, USPS, DHL) — matched through the stored ship-via codes. */
            String carrier,
            /** LIVE (generated, not voided) or VOIDED. */
            String status,
            /** YES (has a commercial invoice) or NO. */
            String invoice,
            LocalDate from,
            LocalDate to,
            /** generated · order · recipient · destination · carrier · billed */
            String sort,
            boolean asc) {}

    /** Counts over the whole scope (not the current filters) for the filter menu. */
    public record Facets(long total, long live, long voided, long withInvoice, List<CarrierCount> carriers) {}
    public record CarrierCount(String carrier, long count) {}

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<DocumentRow> page(Query query, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();

        // The rows of this page: the tracking row and its order, side by side.
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        jakarta.persistence.criteria.Root<OrderTracking> t = cq.from(OrderTracking.class);
        jakarta.persistence.criteria.Root<Order> o = cq.from(Order.class);
        cq.multiselect(t, o)
                .where(predicates(cb, cq, t, o, query, scope, carrierCodes(query)).toArray(new jakarta.persistence.criteria.Predicate[0]))
                .orderBy(ordering(cb, t, o, query));
        List<jakarta.persistence.Tuple> tuples = em.createQuery(cq)
                .setFirstResult((int) paging.getOffset())
                .setMaxResults(paging.getPageSize())
                .getResultList();

        // How many in all.
        jakarta.persistence.criteria.CriteriaQuery<Long> cc = cb.createQuery(Long.class);
        jakarta.persistence.criteria.Root<OrderTracking> t2 = cc.from(OrderTracking.class);
        jakarta.persistence.criteria.Root<Order> o2 = cc.from(Order.class);
        cc.select(cb.countDistinct(t2.get("orderNo")))
                .where(predicates(cb, cc, t2, o2, query, scope, carrierCodes(query)).toArray(new jakarta.persistence.criteria.Predicate[0]));
        long total = em.createQuery(cc).getSingleResult();

        // Which of these orders carry customs data (one lookup for the page).
        List<Integer> orderNos = tuples.stream()
                .map(tu -> tu.get(0, OrderTracking.class).getOrderNo())
                .filter(Objects::nonNull).distinct().toList();
        Set<String> customsOrderNos = new HashSet<>();
        if (!orderNos.isEmpty()) {
            for (OrderCustoms c : orderCustomsRepository.findByOrderNoIn(orderNos.stream().map(String::valueOf).toList())) {
                if (c.getOrderNo() != null) customsOrderNos.add(c.getOrderNo().trim());
            }
        }

        List<DocumentRow> rows = new ArrayList<>(tuples.size());
        Set<Integer> seen = new HashSet<>();
        for (jakarta.persistence.Tuple tu : tuples) {
            OrderTracking track = tu.get(0, OrderTracking.class);
            Order order = tu.get(1, Order.class);
            Integer no = track.getOrderNo();
            if (no == null || !seen.add(no)) continue;
            rows.add(toRow(track, order, customsOrderNos.contains(String.valueOf(no))));
        }
        return new org.springframework.data.domain.PageImpl<>(rows, paging, total);
    }

    @Transactional(readOnly = true)
    public Facets facets() {
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
        Query any = new Query(null, null, null, null, null, null, null, false);
        long total = count(cb, any, scope);
        long voided = count(cb, new Query(null, null, "VOIDED", null, null, null, null, false), scope);
        long withInvoice = count(cb, new Query(null, null, null, "YES", null, null, null, false), scope);

        // Carriers as stored, folded onto their canonical code.
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        jakarta.persistence.criteria.Root<OrderTracking> t = cq.from(OrderTracking.class);
        jakarta.persistence.criteria.Root<Order> o = cq.from(Order.class);
        jakarta.persistence.criteria.Expression<String> code = cb.upper(t.get("shipViaCd"));
        cq.multiselect(code, cb.countDistinct(t.get("orderNo")))
                .where(predicates(cb, cq, t, o, any, scope, null).toArray(new jakarta.persistence.criteria.Predicate[0]))
                .groupBy(code);
        Map<String, Long> byCarrier = new java.util.TreeMap<>();
        for (jakarta.persistence.Tuple tu : em.createQuery(cq).getResultList()) {
            String canonical = canonicalCarrier(tu.get(0, String.class));
            if (!StringUtils.hasText(canonical)) continue;
            byCarrier.merge(canonical, tu.get(1, Long.class), Long::sum);
        }
        List<CarrierCount> carriers = byCarrier.entrySet().stream()
                .map(e -> new CarrierCount(e.getKey(), e.getValue())).toList();
        return new Facets(total, total - voided, voided, withInvoice, carriers);
    }

    private long count(jakarta.persistence.criteria.CriteriaBuilder cb, Query query, Optional<String> scope) {
        jakarta.persistence.criteria.CriteriaQuery<Long> cc = cb.createQuery(Long.class);
        jakarta.persistence.criteria.Root<OrderTracking> t = cc.from(OrderTracking.class);
        jakarta.persistence.criteria.Root<Order> o = cc.from(Order.class);
        cc.select(cb.countDistinct(t.get("orderNo")))
                .where(predicates(cb, cc, t, o, query, scope, carrierCodes(query)).toArray(new jakarta.persistence.criteria.Predicate[0]));
        return em.createQuery(cc).getSingleResult();
    }

    /** The stored ship-via codes (upper-cased) that resolve to the carrier asked for; null when no carrier filter. */
    private Set<String> carrierCodes(Query query) {
        if (!StringUtils.hasText(query.carrier())) return null;
        String wanted = query.carrier().trim().toUpperCase(Locale.ROOT);
        Set<String> codes = new HashSet<>();
        for (String code : em.createQuery("select distinct upper(t.shipViaCd) from OrderTracking t where t.shipViaCd is not null", String.class)
                .getResultList()) {
            if (wanted.equals(canonicalCarrier(code))) codes.add(code);
        }
        return codes;
    }

    /** The WHERE of every documents query: labelled (or voided) tracking rows, their order, the tenant, the filters. */
    private static List<jakarta.persistence.criteria.Predicate> predicates(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.AbstractQuery<?> owner,
            jakarta.persistence.criteria.Root<OrderTracking> t,
            jakarta.persistence.criteria.Root<Order> o,
            Query query, Optional<String> scope, Set<String> carrierCodes) {
        List<jakarta.persistence.criteria.Predicate> where = new ArrayList<>();
        jakarta.persistence.criteria.Expression<String> status = cb.upper(cb.coalesce(t.get("status"), ""));
        jakarta.persistence.criteria.Predicate isVoided = cb.equal(status, "VOIDED");
        // Same rows as the flat list: a generated label, or one that was voided.
        where.add(cb.or(cb.isTrue(t.get("isLabelGenerated")), isVoided));
        where.add(cb.equal(o.get("orderNo"), t.get("orderNo")));
        // Tenant clamp — tenantId first, custNo as the legacy fallback.
        scope.ifPresent(s -> {
            String owner_ = s.trim().toLowerCase(Locale.ROOT);
            jakarta.persistence.criteria.Expression<String> tenant = cb.lower(cb.coalesce(o.get("tenantId"), ""));
            where.add(cb.or(
                    cb.equal(tenant, owner_),
                    cb.and(cb.equal(tenant, ""), cb.equal(cb.lower(cb.coalesce(o.get("custNo"), "")), owner_))));
        });
        if (query.q() != null && StringUtils.hasText(query.q())) {
            String needle = "%" + query.q().trim().toLowerCase(Locale.ROOT) + "%";
            List<jakarta.persistence.criteria.Predicate> any = new ArrayList<>();
            any.add(cb.like(cb.lower(cb.coalesce(t.get("trackingNumber"), "")), needle));
            any.add(cb.like(cb.lower(cb.coalesce(o.get("shipName"), "")), needle));
            any.add(cb.like(cb.lower(cb.coalesce(o.get("custNo"), "")), needle));
            any.add(cb.like(cb.lower(cb.coalesce(o.get("shiptoCity"), "")), needle));
            String digits = query.q().trim().replaceFirst("^#", "");
            if (digits.matches("\\d{1,9}")) any.add(cb.equal(t.get("orderNo"), Integer.parseInt(digits)));
            where.add(cb.or(any.toArray(new jakarta.persistence.criteria.Predicate[0])));
        }
        if (carrierCodes != null) {
            // The stored codes that resolve to the asked-for carrier; none → no rows.
            where.add(carrierCodes.isEmpty() ? cb.disjunction()
                    : cb.upper(cb.coalesce(t.get("shipViaCd"), "")).in(carrierCodes));
        }
        if ("VOIDED".equalsIgnoreCase(query.status())) where.add(isVoided);
        else if ("LIVE".equalsIgnoreCase(query.status())) where.add(cb.and(cb.isTrue(t.get("isLabelGenerated")), cb.not(isVoided)));
        if ("YES".equalsIgnoreCase(query.invoice()) || "NO".equalsIgnoreCase(query.invoice())) {
            jakarta.persistence.criteria.Subquery<Integer> customs = owner.subquery(Integer.class);
            jakarta.persistence.criteria.Root<OrderCustoms> c = customs.from(OrderCustoms.class);
            // order_customs.order_no is text; Hibernate's cast (JPA's Expression.as() is a no-op here).
            org.hibernate.query.criteria.JpaExpression<Integer> orderNo =
                    (org.hibernate.query.criteria.JpaExpression<Integer>) t.<Integer>get("orderNo");
            jakarta.persistence.criteria.Expression<String> orderNoText =
                    ((org.hibernate.query.criteria.HibernateCriteriaBuilder) cb).cast(orderNo, String.class);
            customs.select(cb.literal(1)).where(cb.equal(c.get("orderNo"), orderNoText));
            where.add("YES".equalsIgnoreCase(query.invoice()) ? cb.exists(customs) : cb.not(cb.exists(customs)));
        }
        if (query.from() != null) where.add(cb.greaterThanOrEqualTo(t.get("labelGeneratedAt"), query.from().atStartOfDay()));
        if (query.to() != null) where.add(cb.lessThan(t.get("labelGeneratedAt"), query.to().plusDays(1).atStartOfDay()));
        return where;
    }

    private static List<jakarta.persistence.criteria.Order> ordering(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Root<OrderTracking> t,
            jakarta.persistence.criteria.Root<Order> o,
            Query query) {
        String key = query.sort() == null ? "generated" : query.sort().trim().toLowerCase(Locale.ROOT);
        jakarta.persistence.criteria.Expression<?> by = switch (key) {
            case "order" -> t.get("orderNo");
            case "recipient" -> cb.lower(cb.coalesce(o.get("shipName"), ""));
            case "destination" -> cb.lower(cb.coalesce(o.get("shiptoCity"), ""));
            case "carrier" -> cb.upper(cb.coalesce(t.get("shipViaCd"), ""));
            case "billed" -> t.get("billableAmount");
            default -> t.get("labelGeneratedAt");
        };
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>(2);
        orders.add(query.asc() ? cb.asc(by) : cb.desc(by));
        if (!"order".equals(key)) orders.add(cb.desc(t.get("orderNo")));
        return orders;
    }

    private static DocumentRow toRow(OrderTracking t, Order order, boolean hasInvoice) {
        return DocumentRow.builder()
                .orderNo(t.getOrderNo())
                .custNo(order.getCustNo())
                .recipientName(order.getShipName())
                .city(order.getShiptoCity())
                .countryCode(order.getShiptoCountryCd())
                .carrier(canonicalCarrier(t.getShipViaCd()))
                .trackingNumber(t.getTrackingNumber())
                .generatedAt(t.getLabelGeneratedAt())
                .hasInvoice(hasInvoice)
                .voided("VOIDED".equalsIgnoreCase(t.getStatus() == null ? "" : t.getStatus().trim()))
                .packageCount(order.getPackageCount())
                .accountNumber(t.getAccountNumber())
                .carrierAmount(t.getCarrierAmount())
                .billableAmount(t.getBillableAmount())
                .markupKind(t.getMarkupKind())
                .markupValue(t.getMarkupValue())
                .markupCurrency(t.getMarkupCurrency())
                .build();
    }

    private static String canonicalCarrier(String shipViaCd) {
        String canonical = TrackingServiceImpl.canonicalizeCarrierCode(shipViaCd);
        return StringUtils.hasText(canonical)
                ? canonical.toUpperCase(Locale.ROOT)
                : (shipViaCd == null ? null : shipViaCd.trim().toUpperCase(Locale.ROOT));
    }
}
