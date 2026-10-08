package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;
import com.multiship.backend.service.carriers.exceptions.CarrierException;
import com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import com.multiship.backend.util.LabelDates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PR-T3 (audit §5.1) — SERA {@code POST /sera/v1/rates} rate shopping.
 *
 * <p>Lives next to {@link StampsConnector} because every other SERA code
 * path lives there, but kept as its own {@code @Component} so a follow-up
 * one-line Edit on {@code StampsConnector.getRates} can route to this
 * helper when {@code isSeraFlavor()} is true:
 *
 * <pre>
 *   &#64;Autowired(required = false)
 *   private StampsSeraRates stampsSeraRates;
 *
 *   if (isSeraFlavor() &amp;&amp; stampsSeraRates != null) {
 *       return stampsSeraRates.getRates(request, accessToken, environment)
 *               .stream().map(SeraRateQuote::toRateOption).toList();
 *   }
 * </pre>
 *
 * <p>The split keeps the ~400 LoC of new SERA rate-shop code reviewable
 * in isolation and avoids blocking on the already-crowded 3,600-line
 * {@code StampsConnector.java}.
 *
 * <p>Returns {@link SeraRateQuote} rather than raw {@link RateOption} so
 * the SERA-specific {@code is_customs_required} signal (audit T-C4)
 * reaches the FE pre-commit banner without a schema change on the
 * carrier-neutral record. The {@code toRateOption} shim drops the field
 * for callers that only want the shared shape.
 */
@Slf4j
@Component
public class StampsSeraRates {

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    /** Field-injected so unit tests can construct the helper with the
     *  2-arg constructor; the StampsConnector delegate is only used to
     *  reuse the shared per-piece-aggregator helper. Field-null-tolerant
     *  via the flatten() fallback.
     *
     *  <p>{@code @Lazy} breaks the Spring bean cycle with
     *  StampsConnector#stampsSeraRates (Spring 4.x prohibits cycles by
     *  default). Proxied back-reference resolves on first call, by which
     *  time both beans are fully constructed. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private StampsConnector stampsConnector;

    private static final String CARRIER_CODE = "USPS";

    public StampsSeraRates(CarrierProperties carrierProperties, ObjectMapper objectMapper) {
        this.carrierProperties = carrierProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * SERA-flavored rate quote. Carries everything a {@link RateOption}
     * does plus the SERA-specific {@code is_customs_required} flag so
     * the FE can show a customs-required banner for the intl corridor
     * BEFORE the operator pays for a label.
     *
     * <p>Null {@code isCustomsRequired} means SERA didn't send the flag
     * (treat as "unknown", not "false").
     */
    public record SeraRateQuote(RateOption option, Boolean isCustomsRequired) {
        /** Projection to the shared record for callers that don't care
         *  about the customs-required flag. */
        public RateOption toRateOption() { return option; }
    }

    /**
     * SERA {@code POST /sera/v1/rates} path. Multi-package handled
     * client-side (SERA /rates is single-package per call), aggregated
     * via the shared {@link StampsConnector#aggregateStampsRates} across
     * the N per-piece quotes. {@code is_customs_required} is preserved
     * on every {@link SeraRateQuote} so the FE can show the
     * customs-missing banner before the operator pays for a label
     * (closes audit T-C4).
     */
    public List<SeraRateQuote> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            throw new IllegalStateException(
                    "Stamps.com SERA is not authorized on this account — complete the "
                            + "Stamps.com SERA authorization flow before rate-shopping USPS.");
        }
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "USPS rate-shop requires a recipient country code (order "
                            + request.getReferenceNumber() + ").");
        }
        List<PackageDetailDTO> pkgList = request.effectivePackages();
        if (pkgList.isEmpty()) {
            log.warn("Stamps SERA rate-shop skipped: request has no packages.");
            return List.of();
        }
        String baseUrl = seraApiBaseUrl(environment);
        LocalDate shipDate = LabelDates.today(request.getShipperTimezone());
        long startedAt = System.currentTimeMillis();
        List<List<SeraRateQuote>> perPackage = new ArrayList<>();
        for (int i = 0; i < pkgList.size(); i++) {
            String jsonBody = buildSeraRatesBody(request, pkgList.get(i), shipDate);
            try {
                // Audit: SERA requires Idempotency-Key on every POST. PR-T3 —
                // deterministic key keyed on (reference, shipDate) + piece
                // suffix so a crash-replay mid-rate-shop hits SERA's cached
                // response instead of counting as fresh quotes.
                String idemKey = StringUtils.hasText(request.getReferenceNumber())
                        ? IdempotencyKeys.forStampsRateQuote(request.getReferenceNumber(), shipDate)
                                + "-pkg" + (i + 1)
                        : UUID.randomUUID().toString();
                String response = HttpClients.newBuilder().baseUrl(baseUrl + "/rates").build()
                        .post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idemKey)
                        .body(jsonBody)
                        .retrieve()
                        .body(String.class);
                perPackage.add(parseSeraRatesResponse(response));
            } catch (CarrierException cex) {
                throw cex;
            } catch (Exception ex) {
                // PR-T1 — classify by SERA error_code when available; 429
                // surfaces via CarrierRateLimit so BulkRateShopService can
                // honour Retry-After rather than treating as generic 5xx.
                StampsSeraErrorCode.SeraError seraErr = ex instanceof RestClientResponseException resp
                        ? StampsSeraErrorCode.parse(resp.getResponseBodyAsString(), objectMapper)
                        : new StampsSeraErrorCode.SeraError(
                                StampsSeraErrorCode.UNKNOWN, null, ex.getMessage());
                if (ex instanceof RestClientResponseException rlex
                        && CarrierRateLimit.isRateLimited(rlex)) {
                    log.warn("Stamps SERA /rates {} for package {}/{} — {}",
                            CarrierRateLimit.describe(rlex), i + 1, pkgList.size(), seraErr.message());
                } else if (seraErr.code() != StampsSeraErrorCode.UNKNOWN) {
                    log.warn("Stamps SERA /rates failed for package {}/{}: {}",
                            i + 1, pkgList.size(), seraErr.describe());
                } else {
                    log.warn("Stamps SERA /rates failed for package {}/{}: {}",
                            i + 1, pkgList.size(), seraErr.message());
                }
                throw CarrierExceptionMapper.map("STAMPS", ex,
                        "getRatesSera[pkg " + (i + 1) + "/" + pkgList.size() + "]");
            }
        }
        List<SeraRateQuote> aggregated = aggregate(perPackage);
        if (pkgList.size() > 1) {
            log.info("Stamps SERA rate-shop for {}-pkg request: {} /rates calls, {} ms, {} aggregate services",
                    pkgList.size(), pkgList.size(), System.currentTimeMillis() - startedAt, aggregated.size());
        }
        return aggregated;
    }

    /** Aggregate per-piece quotes into one list — a service only survives
     *  when EVERY package quoted it (same logic as SWSIM's
     *  {@link StampsConnector#aggregateStampsRates}). isCustomsRequired
     *  propagates from the first piece's quote per service — SERA quotes
     *  the SAME customs requirement for every piece on a given lane. */
    private List<SeraRateQuote> aggregate(List<List<SeraRateQuote>> perPackage) {
        if (perPackage.isEmpty()) return List.of();
        if (perPackage.size() == 1) return perPackage.get(0);

        // Reuse StampsConnector.aggregateStampsRates for the price-sum logic
        // on the underlying RateOption list, then re-wrap each result with
        // the first-piece's isCustomsRequired per service.
        if (stampsConnector == null) {
            // No Spring wiring (unit test) → return first piece verbatim.
            return perPackage.get(0);
        }
        List<List<RateOption>> perPackageOptions = perPackage.stream()
                .map(quotes -> quotes.stream().map(SeraRateQuote::toRateOption).toList())
                .toList();
        List<RateOption> aggregatedOptions = stampsConnector.aggregateStampsRates(perPackageOptions);
        Map<String, Boolean> customsByService = new LinkedHashMap<>();
        for (SeraRateQuote q : perPackage.get(0)) {
            customsByService.putIfAbsent(q.option().serviceCode(), q.isCustomsRequired());
        }
        List<SeraRateQuote> out = new ArrayList<>();
        for (RateOption opt : aggregatedOptions) {
            out.add(new SeraRateQuote(opt, customsByService.get(opt.serviceCode())));
        }
        return out;
    }

    /** Resolve the SERA REST base URL for the given environment. */
    private String seraApiBaseUrl(String environment) {
        CarrierProperties.Stamps cfg = carrierProperties.getStamps();
        boolean sandbox = environment != null && "SANDBOX".equalsIgnoreCase(environment.trim());
        String base = sandbox ? cfg.getSeraSandboxApiBaseUrl() : cfg.getSeraApiBaseUrl();
        if (!StringUtils.hasText(base)) {
            throw new com.multiship.backend.exception.CarrierConnectionException(
                    "Stamps.com SERA is enabled but the "
                            + (sandbox ? "sandbox" : "production")
                            + " REST API base URL is not configured.");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * PR-T3 — build the JSON body for {@code POST /sera/v1/rates}. Mirrors
     * the SERA label body shape at the fields {@code /rates} shares
     * (from_address, to_address, package, service_type, ship_date,
     * is_return_label, customs) and drops label-specific fields
     * (insurance, label_options). Package-visible so tests can drive the
     * body builder directly.
     */
    String buildSeraRatesBody(ShipmentRequestDTO request,
                              PackageDetailDTO pkg,
                              LocalDate shipDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_address", seraAddress(
                request.getShipperName(), request.getShipperCompany(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(),
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getShipperPhone(), request.getShipperEmail()));
        body.put("to_address", seraAddress(
                request.getRecipientName(), request.getRecipientCompany(),
                request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                request.getRecipientCity(), request.getRecipientState(),
                request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                request.getRecipientPhone(), request.getRecipientEmail()));

        // service_type is OPTIONAL — supplying narrows quotes to that lane;
        // omitting returns everything the lane permits.
        String service = StampsConnector.mapSwsimServiceToSera(request.getServiceType());
        if (StringUtils.hasText(service)) body.put("service_type", service);

        Map<String, Object> pkgBlock = new LinkedHashMap<>();
        String packagingType = StampsConnector.mapPackagingTypeToSera(
                nonBlank(pkg.getPackageType(), request.getPackageType()));
        if (StringUtils.hasText(packagingType)) pkgBlock.put("packaging_type", packagingType);
        BigDecimal weight = pkg.getWeight();
        String weightUnit = nonBlank(pkg.getWeightUnit(), request.getWeightUnit());
        if (weight != null) {
            pkgBlock.put("weight", weight);
            pkgBlock.put("weight_unit", StampsConnector.normaliseSeraWeightUnit(weightUnit));
        }
        if (pkg.getLength() != null) pkgBlock.put("length", pkg.getLength());
        if (pkg.getWidth() != null) pkgBlock.put("width", pkg.getWidth());
        if (pkg.getHeight() != null) pkgBlock.put("height", pkg.getHeight());
        String dimUnit = nonBlank(pkg.getDimUnit(), request.getDimUnit());
        if (StringUtils.hasText(dimUnit)) pkgBlock.put("dimension_unit",
                StampsConnector.normaliseSeraDimUnit(dimUnit));
        body.put("package", pkgBlock);

        body.put("ship_date", shipDate.toString());
        if (Boolean.TRUE.equals(request.getIsReturn())) body.put("is_return_label", true);

        // Customs on rates lets SERA set is_customs_required accurately for
        // the corridor. Only emit when the intl block is complete enough.
        if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
            Map<String, Object> customs = buildSeraCustomsForRates(request.getIntl());
            if (!customs.isEmpty()) body.put("customs", customs);
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Unreachable in practice — body is pure String/Number/Map; re-throw
            // as unchecked so the caller's exception-mapper classifies uniformly.
            throw new IllegalStateException("Failed to serialize SERA rates body", ex);
        }
    }

    /** SERA address block — snake-case field names matching the SERA v1
     *  reference. Nulls omitted so the wire body is tight. */
    private Map<String, Object> seraAddress(String name, String company,
                                             String line1, String line2,
                                             String city, String state, String postal,
                                             String country, String phone, String email) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) a.put("name", name);
        if (StringUtils.hasText(company)) a.put("company_name", company);
        if (StringUtils.hasText(line1)) a.put("address_line1", line1);
        if (StringUtils.hasText(line2)) a.put("address_line2", line2);
        if (StringUtils.hasText(city)) a.put("city", city);
        if (StringUtils.hasText(state)) a.put("state_province", state);
        if (StringUtils.hasText(postal)) a.put("postal_code", postal);
        if (StringUtils.hasText(country)) a.put("country_code", country);
        if (StringUtils.hasText(phone)) a.put("phone", phone);
        if (StringUtils.hasText(email)) a.put("email", email);
        return a;
    }

    /** SERA customs block for rates. {@link StampsConnector#buildSeraCustoms}
     *  is private + throws on blank currency (PR-T10); on rates we silently
     *  omit instead because we're asking SERA to QUOTE, not SHIP. */
    private Map<String, Object> buildSeraCustomsForRates(IntlShipmentBlockDTO intl) {
        if (!StringUtils.hasText(intl.getCustomsCurrency())) return Map.of();
        Map<String, Object> customs = new LinkedHashMap<>();
        customs.put("contents_type", StampsConnector.mapContentsTypeToSera(intl.getReasonForExport()));
        customs.put("non_delivery_option", "return_to_sender");
        String currency = intl.getCustomsCurrency().trim().toLowerCase(Locale.ROOT);
        String weightUnit = StampsConnector.normaliseSeraWeightUnit(intl.getWeightUnit());
        List<Map<String, Object>> items = new ArrayList<>();
        if (intl.getCommodities() != null) {
            for (CustomsCommodityDTO c : intl.getCommodities()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("item_description", nonBlank(c.getDescription(), ""));
                row.put("quantity", c.getQuantity() != null ? c.getQuantity() : 1);
                if (c.getUnitValue() != null) {
                    Map<String, Object> uv = new LinkedHashMap<>();
                    uv.put("amount", c.getUnitValue());
                    uv.put("currency", currency);
                    row.put("unit_value", uv);
                }
                if (c.getUnitWeight() != null) {
                    row.put("item_weight", c.getUnitWeight());
                    row.put("weight_unit", weightUnit);
                }
                if (StringUtils.hasText(c.getHsCode())) row.put("harmonized_tariff_code", c.getHsCode());
                if (StringUtils.hasText(c.getCountryOfOrigin())) row.put("country_of_origin", c.getCountryOfOrigin());
                if (StringUtils.hasText(c.getSku())) row.put("sku", c.getSku());
                items.add(row);
            }
        }
        customs.put("customs_items", items);
        return customs;
    }

    /**
     * PR-T3 — parse a SERA {@code POST /sera/v1/rates} response into a list
     * of {@link SeraRateQuote}. Response envelope:
     * <pre>
     * {
     *   "rates": [
     *     {
     *       "carrier": "usps",
     *       "service_type": "usps_priority_mail",
     *       "packaging_type": "package",
     *       "estimated_delivery_days": 2,
     *       "estimated_delivery_date": "2026-10-11",
     *       "is_guaranteed_service": false,
     *       "trackable": true,
     *       "is_customs_required": true|false,
     *       "shipment_cost": {"total_amount": 8.50, "currency": "usd"}
     *     }, ...
     *   ]
     * }
     * </pre>
     *
     * <p>Empty / missing {@code rates[]} → empty list (same convention as
     * every other connector's getRates). Non-JSON / blank body → empty
     * list; the HTTP layer surfaces real failures as exceptions and the
     * caller's catch handles them before we parse.
     */
    List<SeraRateQuote> parseSeraRatesResponse(String responseJson) {
        if (!StringUtils.hasText(responseJson)) return List.of();
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception ex) {
            log.warn("Stamps SERA /rates returned non-JSON (first 200 chars): {}", safeHead(responseJson));
            return List.of();
        }
        JsonNode rates = root.path("rates");
        if (!rates.isArray() || rates.isEmpty()) return List.of();
        List<SeraRateQuote> out = new ArrayList<>();
        for (JsonNode rate : rates) {
            String service = rate.path("service_type").asText(null);
            if (!StringUtils.hasText(service)) continue;
            BigDecimal amount = null;
            JsonNode amountNode = rate.path("shipment_cost").path("total_amount");
            if (amountNode.isNumber()) {
                amount = amountNode.decimalValue();
            } else if (amountNode.isTextual()) {
                try { amount = new BigDecimal(amountNode.asText()); }
                catch (NumberFormatException ignored) { /* leave null */ }
            }
            String currency = rate.path("shipment_cost").path("currency").asText("usd");
            if (StringUtils.hasText(currency)) currency = currency.toUpperCase(Locale.ROOT);
            Integer transitDays = rate.hasNonNull("estimated_delivery_days")
                    ? rate.path("estimated_delivery_days").asInt()
                    : null;
            LocalDateTime estimatedDelivery = parseSeraTimestamp(
                    rate.path("estimated_delivery_date").asText(null));
            Boolean isCustomsRequired = rate.hasNonNull("is_customs_required")
                    ? rate.path("is_customs_required").asBoolean()
                    : null;
            RateOption option = new RateOption(CARRIER_CODE, service, service, amount, currency,
                    estimatedDelivery, transitDays);
            out.add(new SeraRateQuote(option, isCustomsRequired));
        }
        return out;
    }

    /** Expose the shared SERA error parser for callers that want the classified
     *  code (not just the human-readable message). */
    StampsSeraErrorCode.SeraError parseSeraError(String body) {
        return StampsSeraErrorCode.parse(body, objectMapper);
    }

    /** Parse SERA's ISO-8601 timestamps. Null-tolerant; returns null on
     *  parse failure so a weird date doesn't tank an otherwise-good quote. */
    private static LocalDateTime parseSeraTimestamp(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        try {
            return LocalDateTime.parse(trimmed);
        } catch (Exception ignored) {
            // Fall through to date-only shape.
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String safeHead(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
