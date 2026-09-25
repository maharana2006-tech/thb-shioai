package com.multiship.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every import's rows, one record each in import_batch_row (V87) — file
 * imports as well as WMS/API pulls. The batch page reads a page of them in
 * SQL; a write stores only the rows that changed, in JDBC batches (the
 * table's IDENTITY key rules out Hibernate's insert batching).
 */
@Component
public class ImportBatchRowStore {

    private static final int BATCH = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public ImportBatchRowStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── the row ↔ columns mapping ─────────────────────────────────────────

    private enum Kind { TEXT, NUMBER, INT, BOOL, ORDER_NO, LIST, MAP }

    private record Col(String name, Kind kind, Function<OrderImportRowDTO, Object> get, BiConsumer<OrderImportRowDTO, Object> set) { }

    @SuppressWarnings("unchecked")
    private static final List<Col> COLS = List.of(
            text("order_ref", OrderImportRowDTO::getOrderRef, OrderImportRowDTO::setOrderRef),
            text("client_code", OrderImportRowDTO::getClientCode, OrderImportRowDTO::setClientCode),
            text("bill_to", OrderImportRowDTO::getBillTo, OrderImportRowDTO::setBillTo),
            text("warehouse_code", OrderImportRowDTO::getWarehouseCode, OrderImportRowDTO::setWarehouseCode),
            text("recipient_name", OrderImportRowDTO::getRecipientName, OrderImportRowDTO::setRecipientName),
            text("recipient_company", OrderImportRowDTO::getRecipientCompany, OrderImportRowDTO::setRecipientCompany),
            text("recipient_phone", OrderImportRowDTO::getRecipientPhone, OrderImportRowDTO::setRecipientPhone),
            text("recipient_email", OrderImportRowDTO::getRecipientEmail, OrderImportRowDTO::setRecipientEmail),
            text("address_line1", OrderImportRowDTO::getAddressLine1, OrderImportRowDTO::setAddressLine1),
            text("address_line2", OrderImportRowDTO::getAddressLine2, OrderImportRowDTO::setAddressLine2),
            text("city", OrderImportRowDTO::getCity, OrderImportRowDTO::setCity),
            text("state", OrderImportRowDTO::getState, OrderImportRowDTO::setState),
            text("postal_code", OrderImportRowDTO::getPostalCode, OrderImportRowDTO::setPostalCode),
            text("country_code", OrderImportRowDTO::getCountryCode, OrderImportRowDTO::setCountryCode),
            text("carrier_code", OrderImportRowDTO::getCarrierCode, OrderImportRowDTO::setCarrierCode),
            text("account_number", OrderImportRowDTO::getAccountNumber, OrderImportRowDTO::setAccountNumber),
            text("service_type", OrderImportRowDTO::getServiceType, OrderImportRowDTO::setServiceType),
            text("ship_via_code", OrderImportRowDTO::getShipViaCode, OrderImportRowDTO::setShipViaCode),
            text("ship_via_note", OrderImportRowDTO::getShipViaNote, OrderImportRowDTO::setShipViaNote),
            text("package_type", OrderImportRowDTO::getPackageType, OrderImportRowDTO::setPackageType),
            number("weight", OrderImportRowDTO::getWeight, OrderImportRowDTO::setWeight),
            text("weight_unit", OrderImportRowDTO::getWeightUnit, OrderImportRowDTO::setWeightUnit),
            new Col("weight_inherited", Kind.BOOL, OrderImportRowDTO::getWeightInherited, (r, v) -> r.setWeightInherited((Boolean) v)),
            number("length", OrderImportRowDTO::getLength, OrderImportRowDTO::setLength),
            number("width", OrderImportRowDTO::getWidth, OrderImportRowDTO::setWidth),
            number("height", OrderImportRowDTO::getHeight, OrderImportRowDTO::setHeight),
            text("dim_unit", OrderImportRowDTO::getDimUnit, OrderImportRowDTO::setDimUnit),
            text("currency", OrderImportRowDTO::getCurrency, OrderImportRowDTO::setCurrency),
            text("incoterms", OrderImportRowDTO::getIncoterms, OrderImportRowDTO::setIncoterms),
            text("reference", OrderImportRowDTO::getReference, OrderImportRowDTO::setReference),
            text("item_description", OrderImportRowDTO::getItemDescription, OrderImportRowDTO::setItemDescription),
            text("item_sku", OrderImportRowDTO::getItemSku, OrderImportRowDTO::setItemSku),
            new Col("item_quantity", Kind.INT, OrderImportRowDTO::getItemQuantity, (r, v) -> r.setItemQuantity((Integer) v)),
            number("item_unit_value", OrderImportRowDTO::getItemUnitValue, OrderImportRowDTO::setItemUnitValue),
            text("hs_code", OrderImportRowDTO::getHsCode, OrderImportRowDTO::setHsCode),
            text("country_of_origin", OrderImportRowDTO::getCountryOfOrigin, OrderImportRowDTO::setCountryOfOrigin),
            new Col("errors", Kind.LIST, OrderImportRowDTO::getErrors, (r, v) -> r.setErrors((List<String>) v)),
            new Col("warnings", Kind.LIST, OrderImportRowDTO::getWarnings, (r, v) -> r.setWarnings((List<String>) v)),
            new Col("generated_order_no", Kind.ORDER_NO, OrderImportRowDTO::getGeneratedOrderNo, (r, v) -> r.setGeneratedOrderNo((Integer) v)),
            text("generated_tracking_number", OrderImportRowDTO::getGeneratedTrackingNumber, OrderImportRowDTO::setGeneratedTrackingNumber),
            text("generated_status", OrderImportRowDTO::getGeneratedStatus, OrderImportRowDTO::setGeneratedStatus),
            text("generated_message", OrderImportRowDTO::getGeneratedMessage, OrderImportRowDTO::setGeneratedMessage),
            text("tracking_url", OrderImportRowDTO::getTrackingUrl, OrderImportRowDTO::setTrackingUrl),
            new Col("batch_id", Kind.INT, OrderImportRowDTO::getBatchId, (r, v) -> r.setBatchId((Integer) v)),
            new Col("custom_fields", Kind.MAP, OrderImportRowDTO::getCustomFields, (r, v) -> r.setCustomFields((Map<String, String>) v)));

    private static Col text(String name, Function<OrderImportRowDTO, String> get, BiConsumer<OrderImportRowDTO, String> set) {
        return new Col(name, Kind.TEXT, get::apply, (r, v) -> set.accept(r, (String) v));
    }

    private static Col number(String name, Function<OrderImportRowDTO, BigDecimal> get, BiConsumer<OrderImportRowDTO, BigDecimal> set) {
        return new Col(name, Kind.NUMBER, get::apply, (r, v) -> set.accept(r, (BigDecimal) v));
    }

    private static final String COLUMN_LIST = String.join(", ", COLS.stream().map(Col::name).toList());

    /** What goes into the column. Lists and maps are JSON, as the WMS pull already wrote them. */
    static Object toDb(Col c, OrderImportRowDTO r) {
        Object v = c.get().apply(r);
        if (v == null) return null;
        return switch (c.kind()) {
            case ORDER_NO -> String.valueOf(v);
            case LIST -> ((List<?>) v).isEmpty() ? null : json(v);
            case MAP -> ((Map<?, ?>) v).isEmpty() ? null : json(v);
            default -> v;
        };
    }

    private static Object fromDb(Col c, ResultSet rs) throws SQLException {
        return switch (c.kind()) {
            case TEXT -> rs.getString(c.name());
            case NUMBER -> rs.getBigDecimal(c.name());
            case INT -> { int v = rs.getInt(c.name()); yield rs.wasNull() ? null : v; }
            case BOOL -> { boolean v = rs.getBoolean(c.name()); yield rs.wasNull() ? null : v; }
            case ORDER_NO -> {
                String s = rs.getString(c.name());
                if (!StringUtils.hasText(s)) yield null;
                try { yield Integer.valueOf(s.trim()); } catch (NumberFormatException e) { yield null; }
            }
            case LIST -> readList(rs.getString(c.name()));
            case MAP -> readMap(rs.getString(c.name()));
        };
    }

    private static String json(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    /** JSON array as written here; the WMS pull once wrote "a, b" — read that too. */
    static List<String> readList(String s) {
        if (!StringUtils.hasText(s)) return new ArrayList<>();
        try {
            return JSON.readValue(s, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return new ArrayList<>(List.of(s));
        }
    }

    private static Map<String, String> readMap(String s) {
        if (!StringUtils.hasText(s)) return null;
        try {
            return JSON.readValue(s, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private static OrderImportRowDTO rowOf(ResultSet rs) throws SQLException {
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setRowNumber(rs.getInt("row_number"));
        for (Col c : COLS) c.set().accept(r, fromDb(c, rs));
        return r;
    }

    /** Same stored value: numbers compare by value (2 and 2.00 are one weight). */
    static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }

    // ─── reads ─────────────────────────────────────────────────────────────

    /** Every row of an import, in row order. */
    public List<OrderImportRowDTO> load(long batchId) {
        return jdbc.query("SELECT row_number, " + COLUMN_LIST + " FROM import_batch_row WHERE import_batch_id = :b ORDER BY row_number, id",
                Map.of("b", batchId), (rs, i) -> rowOf(rs));
    }

    public boolean has(long batchId) {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM import_batch_row WHERE import_batch_id = :b)",
                Map.of("b", batchId), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    /** One row by its number, or null. */
    public OrderImportRowDTO row(long batchId, int rowNumber) {
        List<OrderImportRowDTO> rows = jdbc.query("SELECT row_number, " + COLUMN_LIST + " FROM import_batch_row "
                        + "WHERE import_batch_id = :b AND row_number = :n ORDER BY id LIMIT 1",
                Map.of("b", batchId, "n", rowNumber), (rs, i) -> rowOf(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The rows of these orders (trimmed orderRef), and the row with this number — an order's lines, for an edit. */
    public List<OrderImportRowDTO> rowsOfOrders(long batchId, Collection<String> orderRefs, int rowNumber) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId).addValue("n", rowNumber)
                .addValue("refs", orderRefs == null || orderRefs.isEmpty() ? List.of("~no order~") : orderRefs);
        return jdbc.query("SELECT row_number, " + COLUMN_LIST + " FROM import_batch_row WHERE import_batch_id = :b "
                + "AND (row_number = :n OR TRIM(order_ref) IN (:refs)) ORDER BY row_number, id", p, (rs, i) -> rowOf(rs));
    }

    /** What the batch's counters read: rows, rows with errors, labelled, failed, and rows of orders with no broken line. */
    public record Stats(int total, int invalid, int generated, int failed, int ready) { }

    public Stats stats(long batchId) {
        String key = "COALESCE(NULLIF(TRIM(order_ref), ''), '#' || row_number)";
        Map<String, Object> m = jdbc.queryForMap("SELECT COUNT(*) AS total, "
                + "COUNT(*) FILTER (WHERE " + HAS_ERRORS + ") AS invalid, "
                + "COUNT(*) FILTER (WHERE UPPER(COALESCE(generated_status, '')) = 'GENERATED') AS generated, "
                + "COUNT(*) FILTER (WHERE UPPER(COALESCE(generated_status, '')) = 'FAILED') AS failed, "
                + "COUNT(*) FILTER (WHERE " + key + " NOT IN (SELECT " + key + " FROM import_batch_row "
                + "   WHERE import_batch_id = :b AND " + HAS_ERRORS + ")) AS ready "
                + "FROM import_batch_row WHERE import_batch_id = :b", Map.of("b", batchId));
        return new Stats(((Number) m.get("total")).intValue(), ((Number) m.get("invalid")).intValue(),
                ((Number) m.get("generated")).intValue(), ((Number) m.get("failed")).intValue(),
                ((Number) m.get("ready")).intValue());
    }

    /** A labelled row in some live import with one of the asked orderRefs. */
    public record RefHit(String ref, int orderNo, String status, long batchId) { }

    /**
     * Rows of live (not trashed) imports, newest import first, that carry an
     * order number and one of these orderRefs (upper-cased) — the "already
     * generated in another import" check, one indexed query per 1,000 refs
     * instead of reading every row of 60 imports.
     */
    public List<RefHit> labelledRowsWithRefs(Collection<String> refsUpper) {
        List<RefHit> out = new ArrayList<>();
        List<String> refs = new ArrayList<>(refsUpper);
        for (int i = 0; i < refs.size(); i += BATCH) {
            out.addAll(jdbc.query("SELECT UPPER(TRIM(r.order_ref)) AS ref, r.generated_order_no AS no, r.generated_status AS st, b.id AS bid "
                            + "FROM import_batch_row r JOIN import_batch b ON b.id = r.import_batch_id "
                            + "WHERE b.deleted_at IS NULL AND r.generated_order_no ~ '^[0-9]+$' AND UPPER(r.order_ref) IN (:refs) "
                            + "ORDER BY b.id DESC, r.row_number",
                    Map.of("refs", refs.subList(i, Math.min(i + BATCH, refs.size()))),
                    (rs, n) -> new RefHit(rs.getString("ref"), Integer.parseInt(rs.getString("no")), rs.getString("st"), rs.getLong("bid"))));
        }
        return out;
    }

    /** The first client code in row order — who owns the import. */
    public String firstClientCode(long batchId) {
        List<String> c = jdbc.queryForList("SELECT client_code FROM import_batch_row WHERE import_batch_id = :b "
                + "AND client_code IS NOT NULL AND TRIM(client_code) <> '' ORDER BY row_number LIMIT 1", Map.of("b", batchId), String.class);
        return c.isEmpty() ? null : c.get(0);
    }

    /** Orders with a label (GENERATED): what a live void can take back. */
    public List<Integer> labelledOrders(long batchId) {
        List<Integer> out = new ArrayList<>();
        for (String s : jdbc.queryForList("SELECT DISTINCT generated_order_no FROM import_batch_row WHERE import_batch_id = :b "
                + "AND UPPER(generated_status) = 'GENERATED' AND generated_order_no ~ '^[0-9]+$'", Map.of("b", batchId), String.class)) {
            out.add(Integer.valueOf(s));
        }
        return out;
    }

    private static final String HAS_ERRORS = "(errors IS NOT NULL AND errors <> '' AND errors <> '[]')";

    /** A page of rows for view all | attention | pending | live, searched like the batch page. */
    public record Page(List<OrderImportRowDTO> rows, long total, long all, long attention, long pending, List<String> clientCodes) { }

    public Page page(long batchId, String view, String q, Collection<Integer> voidedOrders, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("b", batchId);
        List<String> voided = voidedOrders == null ? List.of() : voidedOrders.stream().map(String::valueOf).toList();
        p.addValue("voided", voided.isEmpty() ? List.of("-") : voided);
        String attention = "(" + HAS_ERRORS + " OR UPPER(COALESCE(generated_status, '')) = 'FAILED')";
        String pending = "(UPPER(COALESCE(generated_status, '')) <> 'GENERATED' OR generated_order_no IN (:voided))";
        String live = "(generated_order_no IS NOT NULL AND UPPER(generated_status) = 'GENERATED' AND generated_order_no NOT IN (:voided))";
        String inView = "attention".equalsIgnoreCase(view) ? attention
                : "pending".equalsIgnoreCase(view) ? pending : "live".equalsIgnoreCase(view) ? live : "TRUE";
        String search = "TRUE";
        if (StringUtils.hasText(q)) {
            String needle = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            p.addValue("q", "%" + needle + "%");
            search = "(" + String.join(" OR ", java.util.stream.Stream.of("order_ref", "reference", "recipient_name", "city",
                    "client_code", "generated_order_no", "generated_tracking_number")
                    .map(c -> "LOWER(COALESCE(" + c + ", '')) LIKE :q ESCAPE '\\'").toList()) + ")";
        }
        String where = " FROM import_batch_row WHERE import_batch_id = :b AND " + inView + " AND " + search;
        int pageSize = Math.max(size, 1);
        p.addValue("limit", pageSize).addValue("offset", (long) Math.max(page, 0) * pageSize);
        List<OrderImportRowDTO> rows = jdbc.query("SELECT row_number, " + COLUMN_LIST + where
                + " ORDER BY row_number, id LIMIT :limit OFFSET :offset", p, (rs, i) -> rowOf(rs));
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS all_rows, "
                + "COUNT(*) FILTER (WHERE " + attention + ") AS attention, "
                + "COUNT(*) FILTER (WHERE " + pending + ") AS pending "
                + "FROM import_batch_row WHERE import_batch_id = :b", p);
        List<String> clients = jdbc.queryForList("SELECT DISTINCT UPPER(TRIM(client_code)) AS c FROM import_batch_row "
                + "WHERE import_batch_id = :b AND client_code IS NOT NULL AND TRIM(client_code) <> '' ORDER BY c", p, String.class);
        return new Page(rows, total == null ? 0 : total, ((Number) counts.get("all_rows")).longValue(),
                ((Number) counts.get("attention")).longValue(), ((Number) counts.get("pending")).longValue(), clients);
    }

    /** For these order refs: the first row of each that has errors (it holds the order back). */
    public Map<String, Integer> firstBrokenRow(long batchId, Collection<String> orderRefs) {
        Map<String, Integer> out = new HashMap<>();
        if (orderRefs == null || orderRefs.isEmpty()) return out;
        jdbc.query("SELECT TRIM(order_ref) AS ref, MIN(row_number) AS n FROM import_batch_row "
                        + "WHERE import_batch_id = :b AND " + HAS_ERRORS + " AND TRIM(order_ref) IN (:refs) GROUP BY TRIM(order_ref)",
                new MapSqlParameterSource("b", batchId).addValue("refs", orderRefs),
                rs -> { out.put(rs.getString("ref"), rs.getInt("n")); });
        return out;
    }

    // ─── writes ────────────────────────────────────────────────────────────

    /**
     * Make the import's stored rows these rows: insert new row numbers, update
     * the rows whose values changed, delete the ones no longer there. An edit
     * to one cell of a 50k-row batch writes that row (and any whose errors
     * moved), not all 50k.
     *
     * @return rows written (inserted + updated + deleted)
     */
    public int store(long batchId, List<OrderImportRowDTO> rows) {
        return store(batchId, rows, true);
    }

    /**
     * Store only these rows (an order's lines after an edit): insert or update
     * them, leave every other row as it is.
     */
    public int storeSome(long batchId, List<OrderImportRowDTO> rows) {
        return rows.isEmpty() ? 0 : store(batchId, rows, false);
    }

    private int store(long batchId, List<OrderImportRowDTO> rows, boolean wholeBatch) {
        Map<Integer, Long> idOf = new HashMap<>();
        Map<Integer, Object[]> stored = new HashMap<>();
        List<Long> duplicates = new ArrayList<>();
        MapSqlParameterSource which = new MapSqlParameterSource("b", batchId);
        String only = "";
        if (!wholeBatch) {
            which.addValue("ns", rows.stream().map(OrderImportRowDTO::getRowNumber).distinct().toList());
            only = " AND row_number IN (:ns)";
        }
        jdbc.query("SELECT id, row_number, " + COLUMN_LIST + " FROM import_batch_row WHERE import_batch_id = :b" + only + " ORDER BY row_number, id",
                which, rs -> {
                    int n = rs.getInt("row_number");
                    if (idOf.containsKey(n)) { duplicates.add(rs.getLong("id")); return; }
                    idOf.put(n, rs.getLong("id"));
                    Object[] v = new Object[COLS.size()];
                    OrderImportRowDTO read = rowOf(rs);
                    for (int i = 0; i < COLS.size(); i++) v[i] = toDb(COLS.get(i), read);
                    stored.put(n, v);
                });

        List<MapSqlParameterSource> inserts = new ArrayList<>();
        List<MapSqlParameterSource> updates = new ArrayList<>();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        java.util.Set<Integer> kept = new java.util.HashSet<>();
        for (OrderImportRowDTO r : rows) {
            if (!kept.add(r.getRowNumber())) continue;   // one row per number
            MapSqlParameterSource p = new MapSqlParameterSource("b", batchId).addValue("n", r.getRowNumber()).addValue("now", now);
            Object[] was = stored.get(r.getRowNumber());
            boolean changed = was == null;
            for (int i = 0; i < COLS.size(); i++) {
                Object v = toDb(COLS.get(i), r);
                p.addValue(COLS.get(i).name(), v);
                if (!changed && !same(v, was[i])) changed = true;
            }
            if (was == null) inserts.add(p);
            else if (changed) updates.add(p.addValue("id", idOf.get(r.getRowNumber())));
        }
        List<Long> deletes = new ArrayList<>(duplicates);
        if (wholeBatch) for (Map.Entry<Integer, Long> e : idOf.entrySet()) if (!kept.contains(e.getKey())) deletes.add(e.getValue());

        String values = String.join(", ", COLS.stream().map(c -> ":" + c.name()).toList());
        String sets = String.join(", ", COLS.stream().map(c -> c.name() + " = :" + c.name()).toList());
        run("INSERT INTO import_batch_row (import_batch_id, row_number, created_at, updated_at, " + COLUMN_LIST + ") "
                + "VALUES (:b, :n, :now, :now, " + values + ")", inserts);
        run("UPDATE import_batch_row SET " + sets + ", updated_at = :now WHERE id = :id", updates);
        for (int i = 0; i < deletes.size(); i += BATCH) {
            jdbc.update("DELETE FROM import_batch_row WHERE id IN (:ids)",
                    Map.of("ids", deletes.subList(i, Math.min(i + BATCH, deletes.size()))));
        }
        return inserts.size() + updates.size() + deletes.size();
    }

    private void run(String sql, List<MapSqlParameterSource> params) {
        for (int i = 0; i < params.size(); i += BATCH) {
            jdbc.batchUpdate(sql, params.subList(i, Math.min(i + BATCH, params.size())).toArray(MapSqlParameterSource[]::new));
        }
    }

    /** Retention: drop the rows of file imports created before the cutoff (their metadata stays). */
    public int deleteFileImportRowsOlderThan(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM import_batch_row r USING import_batch b WHERE r.import_batch_id = b.id "
                + "AND b.created_at < :cutoff AND UPPER(COALESCE(b.source, 'BULK')) NOT IN ('WMS', 'API')",
                Map.of("cutoff", Timestamp.valueOf(cutoff)));
    }
}
