package com.multiship.backend.service;

import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk Mailer's batch lists, answered by the database: filtered, sorted and
 * paged there, scoped to the caller's client through {@code import_batch.client_code}.
 * The older list endpoints load every batch and parse each one's rows; this
 * never touches the rows.
 */
@Service
@RequiredArgsConstructor
public class BulkBatchQueryService {

    /** Which list: file imports, WMS/API fetches, or Trash (deleted, any source). */
    public enum View { FILE, API, TRASH }

    /** Filters from the Import history toolbar. Nulls / blanks mean "any". */
    public record Query(
            View view,
            String status,
            String q,
            LocalDate from,
            LocalDate to,
            String createdBy,
            /** HAS · NONE — has a label batch yet. */
            String labelBatch,
            Integer minSaved,
            /** created · fileName · savedRows · status · labelBatch */
            String sort,
            boolean asc) { }

    /** The cards and chips above the list — over the whole view, not the current filters. */
    public record Summary(
            long total,
            long readyToGenerate,
            long generating,
            long needsFixes,
            long completedThisWeek,
            Map<String, Long> statusCounts,
            List<String> creators) { }

    static final List<String> STATUSES =
            List.of("DRAFT", "INITIATE", "IN_PROGRESS", "PARTIAL_COMPLETE", "COMPLETE", "FAILED", "CANCELLED");
    private static final int MAX_PAGE_SIZE = 100;

    /** The list columns — never rows_json, which can be megabytes per batch. */
    static final List<String> LIST_COLUMNS = List.of("id", "createdBy", "fileName", "status", "labelBatchId",
            "createdAt", "completedAt", "generationStartedAt", "note", "totalRows", "savedRows", "invalidRows",
            "deletedAt", "deletedBy", "billingMode", "source", "labelsGenerated", "labelsFailed", "labelOrders", "labelsCounted");

    private final ImportBatchRepository repository;
    private final TenantScopeEnforcer tenantScope;
    private final jakarta.persistence.EntityManager entityManager;
    /** When each batch was last printed. Optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.printing.DocumentPrintLog printLog;
    /** Live void status of the page's generated orders. Optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.OrderTrackingRepository trackingRepository;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Transactional(readOnly = true)
    public Page<ImportBatchDTO> list(Query query, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Specification<ImportBatch> where = visible(query.view()).and(matching(query));
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        Root<ImportBatch> root = cq.from(ImportBatch.class);
        cq.multiselect(LIST_COLUMNS.stream().<jakarta.persistence.criteria.Selection<?>>map(c -> root.get(c).alias(c)).toList());
        cq.where(where.and(ordered(query)).toPredicate(root, cq, cb));
        List<jakarta.persistence.Tuple> tuples = entityManager.createQuery(cq)
                .setFirstResult((int) paging.getOffset())
                .setMaxResults(paging.getPageSize())
                .getResultList();
        List<ImportBatchDTO> content = tuples.stream().map(BulkBatchQueryService::summaryOf).toList();
        applyLabelCounts(tuples, content);
        applyLastPrinted(content);
        return new org.springframework.data.domain.PageImpl<>(content, paging, repository.count(where));
    }

    /**
     * One batch as the list shows it — its facts, label counts and last print —
     * without its rows, in any view (Trash included). The batch page's header:
     * a 50k-row batch's rows_json is tens of MB, this is a few hundred bytes.
     */
    @Transactional(readOnly = true)
    public Optional<ImportBatchDTO> one(Long id) {
        if (id == null) return Optional.empty();
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        Root<ImportBatch> root = cq.from(ImportBatch.class);
        cq.multiselect(LIST_COLUMNS.stream().<jakarta.persistence.criteria.Selection<?>>map(c -> root.get(c).alias(c)).toList());
        List<Predicate> p = new ArrayList<>(List.of(cb.equal(root.get("id"), id)));
        scope.ifPresent(s -> p.add(cb.equal(cb.upper(root.get("clientCode")), s.trim().toUpperCase(Locale.ROOT))));
        cq.where(p.toArray(Predicate[]::new));
        List<jakarta.persistence.Tuple> tuples = entityManager.createQuery(cq).getResultList();
        if (tuples.isEmpty()) return Optional.empty();
        List<ImportBatchDTO> content = tuples.stream().map(BulkBatchQueryService::summaryOf).toList();
        applyLabelCounts(tuples, content);
        applyLastPrinted(content);
        return Optional.of(content.get(0));
    }

    private void applyLastPrinted(List<ImportBatchDTO> content) {
        if (printLog == null) return;
        java.util.Map<Integer, LocalDateTime> printed = printLog.lastPrintedByLabelBatch(content.stream()
                .map(ImportBatchDTO::getLabelBatchId).filter(java.util.Objects::nonNull).toList());
        for (ImportBatchDTO d : content) {
            LocalDateTime at = d.getLabelBatchId() == null ? null : printed.get(d.getLabelBatchId());
            d.setLastPrintedAt(at == null ? null : at.toString());
        }
    }

    @Transactional(readOnly = true)
    public Summary summary(View view) {
        Specification<ImportBatch> base = visible(view);
        // One aggregation query for all counts (was 11 separate count(*) calls, one per
        // bucket — dominant cost of every DataHistoryPage load). Hibernate emits
        // SUM(CASE WHEN ... THEN 1 ELSE 0 END) which Postgres plans as a single seq/index
        // scan with per-row branching, same shape as count(*) FILTER (WHERE ...).
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        Root<ImportBatch> root = cq.from(ImportBatch.class);
        Expression<String> statusUpper = cb.upper(root.get("status"));
        Expression<Integer> invalid = root.get("invalidRows");
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        List<jakarta.persistence.criteria.Selection<?>> sel = new ArrayList<>();
        for (String s : STATUSES) {
            sel.add(cb.sum(cb.<Long>selectCase()
                    .when(cb.equal(statusUpper, s), 1L).otherwise(0L)).alias("st_" + s));
        }
        sel.add(cb.count(root).alias("total"));
        sel.add(cb.sum(cb.<Long>selectCase()
                .when(cb.and(cb.equal(statusUpper, "INITIATE"), cb.equal(invalid, 0)), 1L)
                .otherwise(0L)).alias("ready"));
        sel.add(cb.sum(cb.<Long>selectCase()
                .when(cb.or(statusUpper.in("DRAFT", "FAILED"), cb.greaterThan(invalid, 0)), 1L)
                .otherwise(0L)).alias("needsFixes"));
        sel.add(cb.sum(cb.<Long>selectCase()
                .when(cb.and(cb.equal(statusUpper, "COMPLETE"),
                             cb.greaterThanOrEqualTo(root.<LocalDateTime>get("completedAt"), weekAgo)), 1L)
                .otherwise(0L)).alias("doneThisWeek"));

        cq.multiselect(sel);
        cq.where(base.toPredicate(root, cq, cb));
        jakarta.persistence.Tuple t = entityManager.createQuery(cq).getSingleResult();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : STATUSES) {
            long n = asLong(t.get("st_" + s));
            if (n > 0) byStatus.put(s, n);   // preserve prior contract: only non-zero buckets appear
        }
        long total = asLong(t.get("total"));
        byStatus.put("ALL", total);
        return new Summary(total, asLong(t.get("ready")), byStatus.getOrDefault("IN_PROGRESS", 0L),
                asLong(t.get("needsFixes")), asLong(t.get("doneThisWeek")),
                byStatus, creators(base));
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Where each batch's labels stand, in rows: the stored generated / failed
     * counts, less the rows of orders voided since (read live — a void from
     * the Orders page counts too). One tracking query for the whole page.
     */
    void applyLabelCounts(List<jakarta.persistence.Tuple> tuples, List<ImportBatchDTO> content) {
        List<Map<Integer, Integer>> perBatch = new ArrayList<>();
        java.util.Set<Integer> allOrders = new java.util.HashSet<>();
        for (jakarta.persistence.Tuple t : tuples) {
            Map<Integer, Integer> orders = parseLabelOrders(t.get("labelOrders", String.class));
            perBatch.add(orders);
            allOrders.addAll(orders.keySet());
        }
        java.util.Set<Integer> voided = voidedOrdersOfBatches(content, allOrders);
        for (int i = 0; i < content.size(); i++) {
            jakarta.persistence.Tuple t = tuples.get(i);
            ImportBatchDTO d = content.get(i);
            if (!Boolean.TRUE.equals(t.get("labelsCounted", Boolean.class))) continue;   // rows gone — nothing to show
            int generated = orZero(t.get("labelsGenerated", Integer.class));
            int failed = orZero(t.get("labelsFailed", Integer.class));
            int voidedRows = perBatch.get(i).entrySet().stream()
                    .filter(e -> voided.contains(e.getKey())).mapToInt(Map.Entry::getValue).sum();
            d.setLiveOrders((int) perBatch.get(i).keySet().stream().filter(o -> !voided.contains(o)).count());
            d.setLabelsGenerated(Math.max(0, generated - voidedRows));
            d.setLabelsVoided(voidedRows);
            d.setLabelsFailed(failed);
            d.setLabelsPending(Math.max(0, d.getTotalRows() - generated - failed));
        }
    }

    /** SQL literal: a label_orders map of digit keys to integers. */
    private static final String LABEL_MAP_SHAPE =
            "'^\\{\\s*(\"[0-9]+\"\\s*:\\s*[0-9]+\\s*(,\\s*\"[0-9]+\"\\s*:\\s*[0-9]+\\s*)*)?\\}$'";

    /**
     * The orders of these batches that were voided since generation. The
     * database expands each batch's label_orders map and joins it to the
     * tracking rows itself — only the page's batch ids go over the wire. It
     * sent every labelled order number of the page (tens of thousands for a
     * client's big batches) in one IN list.
     */
    private java.util.Set<Integer> voidedOrdersOfBatches(List<ImportBatchDTO> content, java.util.Set<Integer> allOrders) {
        java.util.Set<Integer> voided = new java.util.HashSet<>();
        if (allOrders.isEmpty()) return voided;
        if (entityManager != null) {
            // Only maps of the shape this code writes — {"906976": 1, …} — are cast
            // (inside the CASE, so no plan can cast first): a failed cast would abort
            // this read-only transaction. Any other map simply counts no voids.
            List<?> hits = entityManager.createNativeQuery(
                            "SELECT DISTINCT CAST(e.key AS INTEGER) FROM import_batch b "
                                    + "CROSS JOIN LATERAL jsonb_each_text(CASE WHEN b.label_orders ~ " + LABEL_MAP_SHAPE
                                    + " THEN CAST(b.label_orders AS jsonb) END) e "
                                    + "JOIN order_label_tracking t ON t.order_no = CAST(e.key AS INTEGER) "
                                    + "WHERE b.id IN (:ids) "
                                    + "AND UPPER(t.status) = 'VOIDED'")
                    .setParameter("ids", content.stream().map(ImportBatchDTO::getId).toList())
                    .getResultList();
            for (Object o : hits) voided.add(((Number) o).intValue());
            return voided;
        }
        if (trackingRepository != null) {
            for (var tr : trackingRepository.findByOrderNoIn(allOrders)) {
                if ("VOIDED".equalsIgnoreCase(tr.getStatus())) voided.add(tr.getOrderNo());
            }
        }
        return voided;
    }

    private static int orZero(Integer v) { return v == null ? 0 : v; }

    static Map<Integer, Integer> parseLabelOrders(String json) {
        Map<Integer, Integer> out = new java.util.HashMap<>();
        if (!StringUtils.hasText(json)) return out;
        try {
            JSON.readTree(json).properties().forEach(e -> {
                try { out.put(Integer.parseInt(e.getKey()), e.getValue().asInt()); } catch (NumberFormatException ignored) { }
            });
        } catch (Exception ignored) {
            // an unreadable map just means no live-void adjustment for that batch
        }
        return out;
    }

    /** Everyone who created a batch in this view — the "Created by" filter's choices. */
    private List<String> creators(Specification<ImportBatch> where) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<ImportBatch> root = cq.from(ImportBatch.class);
        cq.select(root.get("createdBy")).distinct(true)
                .where(cb.and(where.toPredicate(root, cq, cb), cb.isNotNull(root.get("createdBy"))))
                .orderBy(cb.asc(root.get("createdBy")));
        return entityManager.createQuery(cq).getResultList();
    }

    // ─── Specifications ───────────────────────────────────────────────────

    /** The view (live file / live API / Trash) and the caller's client. */
    Specification<ImportBatch> visible(View view) {
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            Expression<String> source = cb.upper(cb.coalesce(root.get("source"), "BULK"));
            switch (view == null ? View.FILE : view) {
                case TRASH -> p.add(cb.isNotNull(root.get("deletedAt")));
                case API -> {
                    p.add(cb.isNull(root.get("deletedAt")));
                    p.add(source.in("WMS", "API"));
                }
                default -> {
                    p.add(cb.isNull(root.get("deletedAt")));
                    p.add(cb.not(source.in("WMS", "API")));
                }
            }
            // A client-scoped user sees their client's batches only; operators see all.
            scope.ifPresent(s -> p.add(cb.equal(cb.upper(root.get("clientCode")), s.trim().toUpperCase(Locale.ROOT))));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    /** The toolbar's filters. Search matches the file name, the creator, "#id" and the label batch number. */
    static Specification<ImportBatch> matching(Query f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(f.status()) && !"ALL".equalsIgnoreCase(f.status())) {
                p.add(cb.equal(cb.upper(root.get("status")), f.status().trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(f.q())) {
                String q = f.q().trim().toLowerCase(Locale.ROOT);
                String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(cb.coalesce(root.get("fileName"), "")), like, '\\'));
                any.add(cb.like(cb.lower(cb.coalesce(root.get("createdBy"), "")), like, '\\'));
                String digits = q.replaceFirst("^#", "").replaceFirst("^batch\\s*", "");
                if (digits.matches("\\d{1,9}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                    any.add(cb.equal(root.get("labelBatchId"), Integer.parseInt(digits)));
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from().atStartOfDay()));
            if (f.to() != null) p.add(cb.lessThan(root.get("createdAt"), f.to().plusDays(1).atStartOfDay()));
            if (StringUtils.hasText(f.createdBy())) p.add(cb.equal(root.get("createdBy"), f.createdBy()));
            if ("HAS".equalsIgnoreCase(f.labelBatch())) p.add(cb.isNotNull(root.get("labelBatchId")));
            if ("NONE".equalsIgnoreCase(f.labelBatch())) p.add(cb.isNull(root.get("labelBatchId")));
            if (f.minSaved() != null) p.add(cb.greaterThanOrEqualTo(root.get("savedRows"), f.minSaved()));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    /**
     * Sort order. "status" puts the batches needing a hand first (draft /
     * generating, then ready, partial, failed, done), as the page always has.
     * Ties break on id so paging is stable.
     */
    static Specification<ImportBatch> ordered(Query f) {
        return (root, query, cb) -> {
            // The count query of a page must not carry an ORDER BY.
            if (query != null && !Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                String key = f.sort() == null ? "created" : f.sort();
                Expression<?> by = switch (key) {
                    case "fileName" -> cb.lower(cb.coalesce(root.get("fileName"), ""));
                    case "savedRows" -> root.get("totalRows");
                    case "labelBatch" -> cb.coalesce(root.<Integer>get("labelBatchId"), -1);
                    case "status" -> statusRank(root, cb);
                    default -> root.get("createdAt");
                };
                query.orderBy(f.asc() ? cb.asc(by) : cb.desc(by), f.asc() ? cb.asc(root.get("id")) : cb.desc(root.get("id")));
            }
            return cb.conjunction();
        };
    }

    private static Expression<Integer> statusRank(Root<ImportBatch> root, CriteriaBuilder cb) {
        Expression<String> s = cb.upper(cb.coalesce(root.get("status"), ""));
        return cb.<Integer>selectCase()
                .when(s.in("DRAFT", "IN_PROGRESS"), 0)
                .when(cb.equal(s, "INITIATE"), 1)
                .when(cb.equal(s, "PARTIAL_COMPLETE"), 2)
                .when(cb.equal(s, "FAILED"), 3)
                .when(cb.equal(s, "COMPLETE"), 4)
                .otherwise(9);
    }

    /** A list entry from the selected columns. */
    static ImportBatchDTO summaryOf(jakarta.persistence.Tuple t) {
        ImportBatch b = new ImportBatch();
        b.setId(t.get("id", Long.class));
        b.setCreatedBy(t.get("createdBy", String.class));
        b.setFileName(t.get("fileName", String.class));
        b.setStatus(t.get("status", String.class));
        b.setLabelBatchId(t.get("labelBatchId", Integer.class));
        b.setCreatedAt(t.get("createdAt", LocalDateTime.class));
        b.setCompletedAt(t.get("completedAt", LocalDateTime.class));
        b.setGenerationStartedAt(t.get("generationStartedAt", LocalDateTime.class));
        b.setNote(t.get("note", String.class));
        b.setTotalRows(t.get("totalRows", Integer.class));
        b.setSavedRows(t.get("savedRows", Integer.class));
        b.setInvalidRows(t.get("invalidRows", Integer.class));
        b.setDeletedAt(t.get("deletedAt", LocalDateTime.class));
        b.setDeletedBy(t.get("deletedBy", String.class));
        b.setBillingMode(t.get("billingMode", String.class));
        b.setSource(t.get("source", String.class));
        return summaryOf(b);
    }

    /** A batch as a list entry — from its own columns; the rows are never read. */
    static ImportBatchDTO summaryOf(ImportBatch b) {
        return ImportBatchDTO.builder()
                .id(b.getId())
                .slug(b.getSlug())
                .createdBy(b.getCreatedBy())
                .fileName(b.getFileName())
                .status(b.getStatus())
                .labelBatchId(b.getLabelBatchId())
                .createdAt(b.getCreatedAt() == null ? null : b.getCreatedAt().toString())
                .completedAt(b.getCompletedAt() == null ? null : b.getCompletedAt().toString())
                .generationStartedAt(b.getGenerationStartedAt() == null ? null : b.getGenerationStartedAt().toString())
                .note(b.getNote())
                .totalRows(b.getTotalRows())
                .savedRows(b.getSavedRows())
                .invalidRows(b.getInvalidRows())
                .deletedAt(b.getDeletedAt() == null ? null : b.getDeletedAt().toString())
                .deletedBy(b.getDeletedBy())
                .billingMode(StringUtils.hasText(b.getBillingMode()) ? b.getBillingMode() : "AUTO")
                .source(StringUtils.hasText(b.getSource()) ? b.getSource() : "BULK")
                .build();
    }
}
