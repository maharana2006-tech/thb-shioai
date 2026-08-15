package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiship.backend.dto.ai.HsSuggestRequest;
import com.multiship.backend.dto.ai.PackagingSuggestRequest;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import com.multiship.backend.dto.ai.ServiceRecommendRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AI-assist for the non-address sections of the manual-shipment form. Each
 * method is suggestion-only — it returns data the operator confirms; nothing is
 * persisted. All calls funnel through {@link OpenAiClient}.
 */
@Service
@RequiredArgsConstructor
public class ShipmentAiService {

    private final OpenAiClient openAi;

    // ── Items · commercial invoice: HS-code suggestion ─────────────────────────
    private static final String HS_PROMPT = """
        You are a customs classification assistant. Given a product description
        (and optionally its country of origin), return the most likely 6-digit
        Harmonized System (HS) tariff code. Return ONLY JSON:
        { "hsCode": "######", "heading": "short description of the HS heading",
          "confidence": "high|medium|low", "rationale": "one short sentence" }
        Use exactly 6 digits, no dots. If the description is too vague to classify,
        set confidence "low" and give your best general chapter-level guess.
        """;

    public Map<String, Object> suggestHs(HsSuggestRequest req) {
        if (req == null || !StringUtils.hasText(req.getDescription())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "An item description is required.");
        }
        String user = "Product: " + req.getDescription().trim()
                + (StringUtils.hasText(req.getOriginCountry()) ? "\nCountry of origin: " + req.getOriginCountry().trim() : "");
        JsonNode a = openAi.completeJson(HS_PROMPT, user, "suggest-hs");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hsCode", digitsOnly(str(a, "hsCode")));
        out.put("heading", str(a, "heading"));
        out.put("confidence", lower(str(a, "confidence")));
        out.put("rationale", str(a, "rationale"));
        return out;
    }

    // ── Package & weight: packaging suggestion from the item list ───────────────
    private static final String PKG_PROMPT = """
        You are a packaging engineer. Given a list of items (with quantities) and
        an optional list of allowed package preset codes, recommend ONE package
        and an estimate. Return ONLY JSON:
        { "packageCode": "", "lengthIn": 0, "widthIn": 0, "heightIn": 0,
          "weightLb": 0, "rationale": "one short sentence" }
        Dimensions in inches, weight in pounds, realistic for the goods (include
        packaging). If an allowed-codes list is provided, packageCode MUST be one
        of them (exact string); otherwise use "" and rely on the dimensions.
        """;

    public Map<String, Object> suggestPackaging(PackagingSuggestRequest req) {
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Add at least one item before suggesting packaging.");
        }
        String itemsText = req.getItems().stream()
                .filter(i -> StringUtils.hasText(i.getDescription()))
                .map(i -> "- " + (i.getQuantity() == null ? 1 : i.getQuantity()) + "× " + i.getDescription().trim())
                .collect(Collectors.joining("\n"));
        if (!StringUtils.hasText(itemsText)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Item descriptions are required to suggest packaging.");
        }
        String allowed = (req.getAvailable() == null || req.getAvailable().isEmpty())
                ? "Allowed package codes: (none — recommend by dimensions only)"
                : "Allowed package codes (pick one): " + String.join(", ", req.getAvailable());
        JsonNode a = openAi.completeJson(PKG_PROMPT, "Items:\n" + itemsText + "\n" + allowed, "suggest-packaging");
        Map<String, Object> out = new LinkedHashMap<>();
        String code = str(a, "packageCode");
        // Never invent a code outside the allowed set.
        final String candidate = code;
        if (req.getAvailable() != null && !req.getAvailable().isEmpty()
                && (candidate == null || req.getAvailable().stream().noneMatch(c -> c.equalsIgnoreCase(candidate)))) {
            code = null;
        }
        out.put("packageCode", code);
        out.put("lengthIn", num(a, "lengthIn"));
        out.put("widthIn", num(a, "widthIn"));
        out.put("heightIn", num(a, "heightIn"));
        out.put("weightLb", num(a, "weightLb"));
        out.put("rationale", str(a, "rationale"));
        return out;
    }

    // ── Account & service: service + incoterm recommendation ────────────────────
    private static final String SVC_PROMPT = """
        You are a shipping operations advisor. Given an origin country, destination
        country, parcel weight (lb), a desired urgency, and an optional list of
        available service codes, recommend ONE service and an Incoterm.
        Return ONLY JSON:
        { "serviceCode": "", "serviceName": "", "incoterm": "DAP|DDP|EXW|FCA|CIP|...",
          "rationale": "one short sentence" }
        If an available-codes list is provided, serviceCode MUST be one of them
        (exact string). For international B2C default incoterm DAP; for prepaid-duty
        prefer DDP; for domestic leave incoterm "".
        """;

    public Map<String, Object> recommendService(ServiceRecommendRequest req) {
        if (req == null || !StringUtils.hasText(req.getToCountry())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A destination country is required to recommend a service.");
        }
        String user = "Origin: " + Optional.ofNullable(req.getFromCountry()).orElse("?")
                + "\nDestination: " + req.getToCountry().trim()
                + "\nWeight (lb): " + Optional.ofNullable(req.getWeightLb()).map(String::valueOf).orElse("?")
                + "\nUrgency: " + Optional.ofNullable(req.getUrgency()).filter(StringUtils::hasText).orElse("STANDARD")
                + "\n" + ((req.getAvailable() == null || req.getAvailable().isEmpty())
                    ? "Available service codes: (none listed)"
                    : "Available service codes (pick one): " + String.join(", ", req.getAvailable()));
        JsonNode a = openAi.completeJson(SVC_PROMPT, user, "recommend-service");
        Map<String, Object> out = new LinkedHashMap<>();
        String code = str(a, "serviceCode");
        final String candidate = code;
        if (req.getAvailable() != null && !req.getAvailable().isEmpty()
                && (candidate == null || req.getAvailable().stream().noneMatch(c -> c.equalsIgnoreCase(candidate)))) {
            code = null;
        }
        out.put("serviceCode", code);
        out.put("serviceName", str(a, "serviceName"));
        out.put("incoterm", upper(str(a, "incoterm")));
        out.put("rationale", str(a, "rationale"));
        return out;
    }

    // ── Shipment (top): pre-ship sanity review ──────────────────────────────────
    private static final String REVIEW_PROMPT = """
        You are a shipping QA reviewer. Given a shipment snapshot, list concrete
        problems that would cause a customs hold, a rejected label, or an overpay.
        Check: weight vs. dimensions plausibility, missing HS codes on international
        goods, missing importer of record for dutiable international shipments (any
        Incoterm — DAP delivers duty-unpaid and DDP duty-paid, both still need an
        importer of record), zero/blank declared values, and incoterm/route
        mismatches. When flagging a missing importer, phrase it for the actual
        Incoterm in the snapshot, not a hard-coded "DDP". Return ONLY JSON:
        { "warnings": [ { "severity": "high|medium|low", "field": "short field name",
          "message": "one clear sentence" } ] }
        Return an empty array if nothing is wrong. Do not invent issues; only flag
        what the data actually shows. Max 6 warnings, most important first.
        """;

    public Map<String, Object> reviewShipment(ReviewShipmentRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No shipment data to review.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("From country: ").append(orQ(req.getFromCountry())).append('\n');
        sb.append("To country: ").append(orQ(req.getToCountry())).append('\n');
        sb.append("Weight (lb): ").append(orQ(req.getWeightLb())).append('\n');
        sb.append("Dimensions (in) L×W×H: ").append(orQ(req.getLengthIn())).append(" × ")
                .append(orQ(req.getWidthIn())).append(" × ").append(orQ(req.getHeightIn())).append('\n');
        sb.append("Package: ").append(orQ(req.getPackageCode())).append('\n');
        sb.append("Incoterm: ").append(orQ(req.getIncoterm())).append('\n');
        sb.append("Importer of record provided: ").append(Boolean.TRUE.equals(req.getImporterProvided()) ? "yes" : "no").append('\n');
        boolean intl = req.getFromCountry() != null && req.getToCountry() != null
                && !req.getFromCountry().trim().equalsIgnoreCase(req.getToCountry().trim());
        sb.append("International: ").append(intl ? "yes" : "no").append('\n');
        sb.append("Items:\n");
        if (req.getItems() == null || req.getItems().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (ReviewShipmentRequest.ReviewItem it : req.getItems()) {
                sb.append("  - ").append(orQ(it.getDescription()))
                        .append(" | HS: ").append(orQ(it.getHsCode()))
                        .append(" | value: ").append(orQ(it.getValue())).append('\n');
            }
        }
        JsonNode a = openAi.completeJson(REVIEW_PROMPT, sb.toString(), "review-shipment");
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (JsonNode w : a.path("warnings")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", lower(str(w, "severity")));
            m.put("field", str(w, "field"));
            m.put("message", str(w, "message"));
            if (m.get("message") != null) warnings.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("warnings", warnings);
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────
    private static String str(JsonNode n, String key) {
        String v = n.path(key).asText("");
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static Double num(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) return null;
        double d = v.asDouble(0);
        return d > 0 ? d : null;
    }

    private static String digitsOnly(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return StringUtils.hasText(d) ? d : null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String orQ(Object o) {
        return o == null || (o instanceof String s && s.isBlank()) ? "?" : o.toString();
    }
}
