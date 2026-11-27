package com.multiship.backend.service.external;

import com.multiship.backend.dto.ShipmentValidationResult.ValidationIssue;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.dto.external.ExternalItem;
import com.multiship.backend.dto.external.ExternalParcel;
import com.multiship.backend.dto.external.ExternalShipmentRequest;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderLine;
import com.multiship.backend.service.AddressFormatValidator;
import com.multiship.backend.service.ai.OpenAiClient;
import com.multiship.backend.service.ai.ShipmentAiService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Pre-label validation for external API shipments and bulk label jobs:
 * deterministic rule checks (postal / state format) followed by an AI sanity
 * review ({@link ShipmentAiService#reviewShipment}).
 *
 * <p>Mode {@code ai.validation.mode}: {@code OFF} disables it; {@code ADVISORY}
 * (default) returns warnings only and never blocks a label. Fail-open: if the AI
 * is unconfigured, slow or erroring, the AI stage is skipped and a single
 * {@code AI_VALIDATION_SKIPPED} info entry is returned instead.
 *
 * <p>Each warning is a map {@code {source, severity, field, message}} where
 * source is {@code RULE} or {@code AI}.
 */
@Slf4j
@Service
public class ShipmentAiValidator {

    public enum Mode { OFF, ADVISORY }

    private final Mode mode;
    private final long timeoutMs;
    /** Fans batch items out. */
    private final ExecutorService pool;
    /** Runs the timed AI call; separate so batch tasks never wait on their own pool. */
    private final ExecutorService aiPool;

    @Autowired(required = false)
    private ShipmentAiService shipmentAiService;
    @Autowired(required = false)
    private OpenAiClient openAiClient;

    public ShipmentAiValidator(
            @Value("${ai.validation.mode:ADVISORY}") String mode,
            @Value("${ai.validation.timeout-ms:5000}") long timeoutMs,
            @Value("${ai.validation.concurrency:8}") int concurrency) {
        Mode m;
        try {
            m = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            log.warn("Unknown ai.validation.mode '{}', defaulting to ADVISORY", mode);
            m = Mode.ADVISORY;
        }
        this.mode = m;
        this.timeoutMs = timeoutMs;
        this.pool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "ai-shipment-validator");
            t.setDaemon(true);
            return t;
        });
        this.aiPool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "ai-shipment-review");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
        aiPool.shutdownNow();
    }

    public boolean isEnabled() {
        return mode != Mode.OFF;
    }

    // ───────────────────────────── external API ─────────────────────────────

    /** Advisory warnings for one external shipment request. Never throws. */
    public List<Map<String, Object>> validate(ExternalShipmentRequest req) {
        if (!isEnabled() || req == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        addressRules(out, req.getShipFrom(), "shipFrom");
        addressRules(out, req.getShipTo(), "shipTo");
        out.addAll(aiReview(toReview(req)));
        return out;
    }

    /** Validates a batch concurrently (bounded pool); result index matches input index. */
    public List<List<Map<String, Object>>> validateAll(List<ExternalShipmentRequest> reqs) {
        if (!isEnabled() || reqs == null || reqs.isEmpty()) {
            List<List<Map<String, Object>>> empty = new ArrayList<>();
            if (reqs != null) reqs.forEach(r -> empty.add(List.of()));
            return empty;
        }
        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>(reqs.size());
        for (ExternalShipmentRequest r : reqs) {
            futures.add(CompletableFuture.supplyAsync(() -> validate(r), pool)
                    .exceptionally(ex -> List.of(skipped(ex))));
        }
        List<List<Map<String, Object>>> out = new ArrayList<>(reqs.size());
        for (CompletableFuture<List<Map<String, Object>>> f : futures) out.add(f.join());
        return out;
    }

    // ───────────────────────────── bulk (orders) ────────────────────────────

    /** Advisory warnings for a stored order (bulk label path). Never throws. */
    public List<Map<String, Object>> validate(Order order) {
        if (!isEnabled() || order == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        rules(out, order.getShipFromCountryCd(), order.getShipFromZip(), order.getShipFromState(), "shipFrom");
        rules(out, order.getShiptoCountryCd(), order.getShiptoZip(), order.getShiptoState(), "shipTo");
        out.addAll(aiReview(toReview(order)));
        return out;
    }

    // ───────────────────────────── stages ───────────────────────────────────

    private void addressRules(List<Map<String, Object>> out, ExternalAddress a, String prefix) {
        if (a == null) return;
        rules(out, a.getCountryCode(), a.getPostalCode(), a.getState(), prefix);
    }

    private void rules(List<Map<String, Object>> out, String country, String postal, String state, String prefix) {
        try {
            for (ValidationIssue i : AddressFormatValidator.validate(country, postal, state, prefix)) {
                out.add(issue("RULE", "warning", i.getField(), i.getMessage()));
            }
        } catch (RuntimeException e) {
            log.debug("Rule validation failed for {}: {}", prefix, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aiReview(ReviewShipmentRequest review) {
        if (shipmentAiService == null || openAiClient == null || !openAiClient.isConfigured()) {
            return List.of(skipped(null));
        }
        try {
            Map<String, Object> res = CompletableFuture
                    .supplyAsync(() -> shipmentAiService.reviewShipment(review), aiPool)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            List<Map<String, Object>> out = new ArrayList<>();
            Object ws = res == null ? null : res.get("warnings");
            if (ws instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> w)) continue;
                    Object sev = w.get("severity");
                    out.add(issue("AI", sev == null ? "warning" : sev.toString(),
                            (String) ((Map<String, Object>) w).get("field"),
                            String.valueOf(w.get("message"))));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("AI shipment validation skipped: {}", e.toString());
            return List.of(skipped(e));
        }
    }

    // ───────────────────────────── mapping ──────────────────────────────────

    static ReviewShipmentRequest toReview(ExternalShipmentRequest req) {
        ReviewShipmentRequest r = new ReviewShipmentRequest();
        if (req.getShipFrom() != null) r.setFromCountry(req.getShipFrom().getCountryCode());
        if (req.getShipTo() != null) r.setToCountry(req.getShipTo().getCountryCode());
        ExternalParcel p = req.getParcel();
        if (p != null) {
            r.setWeightLb(toLb(p.getWeight(), p.getWeightUnit()));
            double dimFactor = "cm".equalsIgnoreCase(p.getDimUnit()) ? 1 / 2.54 : 1;
            r.setLengthIn(scale(p.getLength(), dimFactor));
            r.setWidthIn(scale(p.getWidth(), dimFactor));
            r.setHeightIn(scale(p.getHeight(), dimFactor));
            r.setPackageCode(p.getPackagingCode());
        }
        r.setIncoterm(req.getIncoterms());
        List<ReviewShipmentRequest.ReviewItem> items = new ArrayList<>();
        if (req.getItems() != null) {
            for (ExternalItem it : req.getItems()) {
                if (it == null) continue;
                ReviewShipmentRequest.ReviewItem ri = new ReviewShipmentRequest.ReviewItem();
                ri.setDescription(it.getDescription());
                ri.setHsCode(it.getHsCode());
                if (it.getUnitValue() != null) {
                    int qty = it.getQuantity() == null ? 1 : it.getQuantity();
                    ri.setValue(it.getUnitValue().doubleValue() * qty);
                }
                items.add(ri);
            }
        }
        r.setItems(items);
        return r;
    }

    static ReviewShipmentRequest toReview(Order o) {
        ReviewShipmentRequest r = new ReviewShipmentRequest();
        r.setFromCountry(o.getShipFromCountryCd());
        r.setToCountry(o.getShiptoCountryCd());
        r.setWeightLb(toLb(o.getWeight(), o.getWeightUnit()));
        List<ReviewShipmentRequest.ReviewItem> items = new ArrayList<>();
        if (o.getOrderLines() != null) {
            for (OrderLine l : o.getOrderLines()) {
                if (l == null) continue;
                ReviewShipmentRequest.ReviewItem ri = new ReviewShipmentRequest.ReviewItem();
                ri.setDescription(StringUtils.hasText(l.getItemDescription()) ? l.getItemDescription() : l.getDescription());
                ri.setHsCode(l.getHsCode());
                BigDecimal v = l.getCustomsDeclValue() != null ? l.getCustomsDeclValue() : l.getTotalPrice();
                if (v != null) ri.setValue(v.doubleValue());
                items.add(ri);
            }
        }
        r.setItems(items);
        return r;
    }

    private static Double toLb(BigDecimal w, String unit) {
        if (w == null) return null;
        return "kg".equalsIgnoreCase(unit) ? w.doubleValue() * 2.20462 : w.doubleValue();
    }

    private static Double scale(BigDecimal v, double f) {
        return v == null ? null : v.doubleValue() * f;
    }

    private static Map<String, Object> issue(String source, String severity, String field, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("severity", severity);
        m.put("field", field);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> skipped(Throwable cause) {
        return issue("AI", "info", null, cause == null
                ? "AI_VALIDATION_SKIPPED: AI service is not configured."
                : "AI_VALIDATION_SKIPPED: AI review unavailable, label generated without it.");
    }
}
