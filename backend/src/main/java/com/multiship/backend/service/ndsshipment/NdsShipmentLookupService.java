package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.service.StdShipMethodResolver;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.TenantScopeEnforcer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Business orchestrator for the NDS Shipment prefill feature.
 * Composes {@link NdsScanValueParser} + {@link NdsShipmentLookupRepository}
 * + {@link ClientShipviaCodeMapRepository} into a single
 * {@link NdsShipmentPrefill} response.
 *
 * <p>Rules implemented here (mirroring the ShipX legacy behavior):
 * <ul>
 *   <li>Blank / hold ship-method → {@code BLOCKED}.</li>
 *   <li>Already-shipped container → {@code BLOCKED}.</li>
 *   <li>Unmapped ship-method → {@code WARNING} (FE can still ship
 *       once operator picks a service).</li>
 *   <li>Phone normalization via {@link NdsPhoneNormalizer}
 *       (defaults to ops fallback + surfaces in {@code defaultedFields}).</li>
 *   <li>SHIP_NAME/ATTN/ADDR1 special-char stripping via
 *       {@link NdsAddressSanitizer}.</li>
 *   <li>Email default to {@code support@thbred.com} when notify
 *       emails empty + surfaces in {@code defaultedFields}.</li>
 *   <li>International = country != {@code US} OR US territory
 *       ({@code PR VI GU AS MP UM}).</li>
 *   <li>{@code .Y} address safety: pick the address from the order
 *       owning the lowest container id — silently ignore differences
 *       across other orders in the batch (matches ShipX).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NdsShipmentLookupService {

    /** ISO-3166-1 alpha-2 codes for US territories treated as international. */
    public static final Set<String> US_TERRITORY_CODES = Set.of("PR", "VI", "GU", "AS", "MP", "UM");

    /** Platform-default when no per-tenant notify-email fallback is set. */
    public static final String DEFAULT_NOTIFY_EMAIL = "support@thbred.com";

    /** Platform-default when no per-tenant weight fallback is set. */
    public static final BigDecimal DEFAULT_WEIGHT_LB = BigDecimal.ONE;

    // Keys mirror the ones the FE writes via /api/v1/tenants/{code}/settings/{key}.
    static final String KEY_FALLBACK_PHONE = "nds.fallback_phone";
    static final String KEY_FALLBACK_NOTIFY_EMAIL = "nds.fallback_notify_email";
    static final String KEY_DEFAULT_WEIGHT_LB = "nds.default_weight_lb";
    static final String KEY_ON_MISSING_WEIGHT = "nds.on_missing_weight";

    private final NdsShipmentLookupRepository repository;
    private final ClientShipviaCodeMapRepository clientShipviaRepo;
    /** G6 — STD ship method resolver. Nullable in reduced-args test wiring. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.StdShipMethodResolver stdResolver;
    private final TenantScopeEnforcer tenantScopeEnforcer;
    /** X1/X2/X4 — nullable so pure-Mockito tests can construct without stubbing. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.TenantSettingsService tenantSettingsService;

    /**
     * Look up prefill data for a raw scan value. Callers pass the
     * exact scanner input (including the leading {@code .X}/{@code .Y}
     * marker). Returns {@link Optional#empty()} on "not found" — the
     * controller maps that to 404.
     *
     * @throws IllegalArgumentException on unparseable input (→ 422)
     * @throws ExternalSystemException  on registry/connectivity issues (→ 503)
     */
    public Optional<NdsShipmentPrefill> lookup(String rawScan) {
        return lookup(rawScan, null);
    }

    /**
     * Lookup with an operator-supplied client-code gate. When
     * {@code expectedClientCode} is non-blank, the value NDS reports for
     * the scan's owning tenant (TENANT_ID for .X, FF_SCHEMA for .Y) must
     * match — mismatch throws {@link IllegalArgumentException} (→ 422)
     * with a message naming both codes. Null / blank skips the check.
     */
    public Optional<NdsShipmentPrefill> lookup(String rawScan, String expectedClientCode) {
        NdsScanValue scan = NdsScanValueParser.parse(rawScan);
        String expected = expectedClientCode == null ? null : expectedClientCode.trim();
        return switch (scan.scope()) {
            case DIRECT -> lookupDirect(scan, expected);
            case BATCH  -> lookupBatch(scan, expected);
        };
    }

    // ═════════════════ .X — DIRECT container lookup ══════════════════

    private Optional<NdsShipmentPrefill> lookupDirect(NdsScanValue scan, String expectedClientCode) {
        Optional<NdsShipmentLookupRepository.ContainerOwner> owner =
                repository.findContainerOwner(scan.stripped());
        if (owner.isEmpty()) {
            log.info("nds-lookup DIRECT: container {} not found", scan.stripped());
            return Optional.empty();
        }
        assertClientCodeMatches(expectedClientCode, owner.get().clientCode(), "container", scan.stripped());
        String clientCode = tenantScopeEnforcer.clampClientCode(owner.get().clientCode());
        NdsShipmentLookupRepository.ContainerOwner o = owner.get();

        Optional<NdsShipmentLookupRepository.OrderHeader> header =
                repository.findOrderHeader(clientCode, o.orderNo(), o.orderSuffix());
        if (header.isEmpty()) {
            log.info("nds-lookup DIRECT: order {}/{} for client {} not found",
                    o.orderNo(), o.orderSuffix(), clientCode);
            return Optional.empty();
        }
        List<NdsShipmentLookupRepository.ContainerRow> containers =
                repository.findContainers(clientCode, o.orderNo(), o.orderSuffix());
        List<NdsShipmentPrefill.Package> packages = buildDirectPackages(containers, scan.stripped(), clientCode);
        return Optional.of(buildResponse(scan, NdsShipmentPrefill.Scope.DIRECT, clientCode,
                null, header.get(), packages));
    }

    private List<NdsShipmentPrefill.Package> buildDirectPackages(
            List<NdsShipmentLookupRepository.ContainerRow> containers,
            String scannedContainer,
            String clientCode) {
        BigDecimal defaultWeight = tenantDefaultWeight(clientCode);
        List<NdsShipmentPrefill.Package> out = new ArrayList<>(containers.size());
        int seq = 1;
        for (NdsShipmentLookupRepository.ContainerRow c : containers) {
            // G8 — carriers reject zero-weight shipments; default to
            // tenant setting (nds.default_weight_lb) or platform 1 lb.
            BigDecimal rawWeight = c.billableWeightLb();
            boolean weightDefaulted = rawWeight == null || rawWeight.signum() <= 0;
            out.add(new NdsShipmentPrefill.Package(
                    seq++,
                    c.containerId(),
                    Stream.of(safeParseLong(c.containerId())).filter(Objects::nonNull).toList(),
                    Stream.of(parseIntOrNull(c.orderNo())).filter(Objects::nonNull).toList(),
                    parseIntOrNull(c.orderSuffix()),
                    weightDefaulted ? defaultWeight : rawWeight,
                    weightDefaulted ? "DEFAULT_ONE_LB" : "OE_SHIP_CONTAINER.GROSS_WT",
                    c.lengthIn(), c.widthIn(), c.heightIn(),
                    null, null,
                    scannedContainer != null && scannedContainer.equals(c.containerId())));
        }
        return out;
    }

    // ═════════════════ .Y — BATCH lookup ════════════════════════════

    private Optional<NdsShipmentPrefill> lookupBatch(NdsScanValue scan, String expectedClientCode) {
        String batchId = scan.stripped();
        // Discover client code if the caller didn't supply one (legacy tests).
        String discoveredClient = expectedClientCode;
        if (discoveredClient == null || discoveredClient.isBlank()) {
            Optional<NdsShipmentLookupRepository.BatchOwner> owner =
                    repository.findBatchOwner(batchId);
            if (owner.isEmpty()) {
                log.info("nds-lookup BATCH: batch {} not found (no client hint)", batchId);
                return Optional.empty();
            }
            discoveredClient = owner.get().primaryClientCode();
        }
        String clientCode = tenantScopeEnforcer.clampClientCode(discoveredClient);
        // Legacy Step 2 — one row per (batch, container_no, order_suffix)
        // via the ShipX GetMultiContainerByOrders join. Runs on client login.
        List<NdsShipmentLookupRepository.BillableBatchPackage> rows =
                repository.findBillableBatchPackages(batchId, clientCode);
        if (rows.isEmpty()) {
            log.info("nds-lookup BATCH: batch {} has no packages for client {}", batchId, clientCode);
            return Optional.empty();
        }
        // Anchor = lowest CONTAINER_NO (SQL ORDER BY b.Container_No ASC) →
        // its first order in the CSV supplies the ship-to header.
        NdsShipmentLookupRepository.BillableBatchPackage anchor = rows.get(0);
        Integer anchorOrder = firstFromCsv(anchor.orderNosCsv());
        if (anchorOrder == null) {
            log.info("nds-lookup BATCH: anchor row missing order_no for batch {}", batchId);
            return Optional.empty();
        }
        Optional<NdsShipmentLookupRepository.OrderHeader> header = repository.findOrderHeader(
                clientCode, String.valueOf(anchorOrder),
                anchor.orderSuffix() == null ? "0" : String.valueOf(anchor.orderSuffix()));
        if (header.isEmpty()) {
            log.info("nds-lookup BATCH: anchor order {}/{} missing for client {}",
                    anchorOrder, anchor.orderSuffix(), clientCode);
            return Optional.empty();
        }
        List<NdsShipmentPrefill.Package> packages = buildBatchPackages(rows, clientCode);
        return Optional.of(buildResponse(scan, NdsShipmentPrefill.Scope.BATCH, clientCode,
                batchId, header.get(), packages));
    }

    private List<NdsShipmentPrefill.Package> buildBatchPackages(
            List<NdsShipmentLookupRepository.BillableBatchPackage> rows,
            String clientCode) {
        BigDecimal defaultWeight = tenantDefaultWeight(clientCode);
        List<NdsShipmentPrefill.Package> out = new ArrayList<>(rows.size());
        int seq = 1;
        for (NdsShipmentLookupRepository.BillableBatchPackage r : rows) {
            List<Long> containerIds = parseCsvLongs(r.containerIdsCsv());
            List<Integer> orderNos = parseCsvInts(r.orderNosCsv());
            BigDecimal rawWeight = r.weight();
            boolean weightDefaulted = rawWeight == null || rawWeight.signum() <= 0;
            out.add(new NdsShipmentPrefill.Package(
                    seq++,
                    r.containerNo() == null ? null : String.valueOf(r.containerNo()),
                    containerIds,
                    orderNos,
                    r.orderSuffix(),
                    weightDefaulted ? defaultWeight : rawWeight,
                    weightDefaulted ? "DEFAULT_ONE_LB" : "TB_BILLABLE_CONTAINERS.WEIGHT",
                    null, null, null,           // dims not carried on the billable path
                    null, null,
                    false));
        }
        return out;
    }

    /** First integer token from a CSV like {@code "999999,888888,"}; null if none. */
    private static Integer firstFromCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        String[] parts = csv.split(",");
        for (String s : parts) {
            Integer v = parseIntOrNull(s);
            if (v != null) return v;
        }
        return null;
    }

    private static List<Long> parseCsvLongs(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            Long v = safeParseLong(s);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static List<Integer> parseCsvInts(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            Integer v = parseIntOrNull(s);
            if (v != null) out.add(v);
        }
        return out;
    }

    // ═════════════════ shared DTO assembly ══════════════════════════

    private NdsShipmentPrefill buildResponse(NdsScanValue scan,
                                             NdsShipmentPrefill.Scope scope,
                                             String clientCode,
                                             String batchId,
                                             NdsShipmentLookupRepository.OrderHeader h,
                                             List<NdsShipmentPrefill.Package> packages) {
        List<NdsShipmentPrefill.Message> messages = new ArrayList<>();
        List<String> defaultedFields = new ArrayList<>();
        NdsShipmentPrefill.Status status = NdsShipmentPrefill.Status.OK;

        // Ship method resolution + hard blocks
        String shipvia = h.shipviaCd();
        Long mappedServiceId = null;
        // G6 — remember the ERP code to write back to OEHEAD.SHIPVIA_CD when
        // the original was STD. Non-null triggers the writer's OEHEAD update
        // on label success. See ShipX_NDS_Orders_and_Tracking.docx §5 + §6.
        String stdReplacementErpCode = null;
        if (shipvia == null || shipvia.isBlank()) {
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order has no ship method (SHIPVIA_CD blank)."));
            status = NdsShipmentPrefill.Status.BLOCKED;
        } else if (isHoldShipvia(clientCode, shipvia)) {
            // X3 — hold detection is now data-driven via
            // client_shipvia_code_map.is_hold; the "HLD" literal is
            // only the last-resort fallback for tenants with no mapping.
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order is on hold (SHIPVIA_CD = " + shipvia + ")."));
            status = NdsShipmentPrefill.Status.BLOCKED;
        } else if (StdShipMethodResolver.STD.equalsIgnoreCase(shipvia)) {
            // G6 — STD ship method: substitute with the client's
            // Shipping-Service-Mapping row keyed by shipvia_cd='STD'.
            var resolved = stdResolver == null
                    ? java.util.Optional.<StdShipMethodResolver.Result>empty()
                    : stdResolver.resolveStdForClient(clientCode);
            if (resolved.isEmpty()) {
                messages.add(new NdsShipmentPrefill.Message(
                        NdsShipmentPrefill.Message.Severity.BLOCKED,
                        "Order ship method is STD but no STD mapping exists for "
                                + clientCode + ". Add one in Settings → Shipping Service Mapping "
                                + "(erp_code=STD, client=" + clientCode + ")."));
                status = NdsShipmentPrefill.Status.BLOCKED;
            } else {
                mappedServiceId = resolved.get().service().getId();
                stdReplacementErpCode = resolved.get().erpCodeForNds();
                messages.add(new NdsShipmentPrefill.Message(
                        NdsShipmentPrefill.Message.Severity.INFO,
                        "STD ship method resolved to "
                                + resolved.get().service().getName()
                                + (stdReplacementErpCode != null
                                        ? " (NDS SHIPVIA_CD will update to " + stdReplacementErpCode + " after label success)"
                                        : "")
                                + "."));
            }
        } else {
            mappedServiceId = resolveServiceId(clientCode, shipvia);
            if (mappedServiceId == null) {
                messages.add(new NdsShipmentPrefill.Message(
                        NdsShipmentPrefill.Message.Severity.WARNING,
                        "Ship method '" + shipvia + "' is not mapped to a shipping service. "
                                + "Operator must pick a service before shipping."));
                status = worse(status, NdsShipmentPrefill.Status.WARNING);
            }
        }
        // Already-shipped block — OEHEAD flag (per-container shipped flag not
        // currently projected on the prebuilt Package DTO).
        if ("Y".equalsIgnoreCase(h.shippedFlag())) {
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order or container already marked shipped in NDS."));
            status = NdsShipmentPrefill.Status.BLOCKED;
        }
        if ("Y".equalsIgnoreCase(h.holdFlag())) {
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order has HOLD_FLAG = Y."));
            status = NdsShipmentPrefill.Status.BLOCKED;
        }

        // Recipient assembly (with sanitizer + phone normalizer)
        // X1 — fallback phone from tenant_settings (nds.fallback_phone);
        // falls through to NdsPhoneNormalizer.DEFAULT_PHONE if unset.
        NdsPhoneNormalizer.Result phone = NdsPhoneNormalizer.normalize(
                h.shipToPhone(), tenantSetting(clientCode, KEY_FALLBACK_PHONE));
        if (phone.defaulted()) defaultedFields.add("recipient.phone");
        NdsShipmentPrefill.Recipient recipient = new NdsShipmentPrefill.Recipient(
                NdsAddressSanitizer.sanitize(h.shipToAttn()),
                NdsAddressSanitizer.sanitize(h.shipToName()),
                NdsAddressSanitizer.sanitize(h.shipToAddr1()),
                h.shipToAddr2(),
                null,
                h.shipToCity(),
                h.shipToState(),
                h.shipToPostal(),
                h.shipToCountry(),
                phone.phone(),
                phone.defaulted(),
                parseIntOrNull(h.orderNo()));

        // Ship method DTO
        Optional<NdsShipmentLookupRepository.ShipMethod> methodDesc =
                (shipvia == null || shipvia.isBlank())
                        ? Optional.empty()
                        : repository.findShipMethod(clientCode, shipvia);
        NdsShipmentPrefill.ShipMethod shipMethod = (shipvia == null || shipvia.isBlank())
                ? null
                : new NdsShipmentPrefill.ShipMethod(shipvia,
                        methodDesc.map(NdsShipmentLookupRepository.ShipMethod::description).orElse(null),
                        mappedServiceId);

        // Weight-defaulted WARNING (X4): derived from Package.weightSource so
        // both .X (per container_id) and .Y (per container_no) share it.
        // Policy nds.on_missing_weight=BLOCK promotes to BLOCKED.
        boolean blockOnMissing = tenantBlocksOnMissingWeight(clientCode);
        for (NdsShipmentPrefill.Package p : packages) {
            if ("DEFAULT_ONE_LB".equals(p.weightSource())) {
                var severity = blockOnMissing
                        ? NdsShipmentPrefill.Message.Severity.BLOCKED
                        : NdsShipmentPrefill.Message.Severity.WARNING;
                messages.add(new NdsShipmentPrefill.Message(severity,
                        "Container " + p.containerNo() + " has no billable weight in NDS"
                                + (blockOnMissing ? " (blocked by tenant policy)." : " — defaulted to " + p.weight() + " lb.")));
                status = blockOnMissing
                        ? NdsShipmentPrefill.Status.BLOCKED
                        : worse(status, NdsShipmentPrefill.Status.WARNING);
            }
        }

        // Notify — pick first non-null email, default if none
        List<NdsShipmentLookupRepository.NotifyEmail> notifyRows =
                repository.findNotifyEmails(clientCode, h.orderNo(), h.orderSuffix());
        String sendTo = notifyRows.stream()
                .map(NdsShipmentLookupRepository.NotifyEmail::email)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
        boolean emailDefaulted = sendTo == null;
        if (emailDefaulted) {
            // X2 — per-tenant fallback; platform DEFAULT_NOTIFY_EMAIL used
            // only when the tenant hasn't set nds.fallback_notify_email.
            String tenantFallback = tenantSetting(clientCode, KEY_FALLBACK_NOTIFY_EMAIL);
            sendTo = (tenantFallback != null && !tenantFallback.isBlank())
                    ? tenantFallback.trim()
                    : DEFAULT_NOTIFY_EMAIL;
            defaultedFields.add("notify.sendTo");
        }
        NdsShipmentPrefill.Notify notifyBlock = new NdsShipmentPrefill.Notify(sendTo, null, emailDefaulted);

        // International detection
        boolean isInternational = isInternational(h.shipToCountry());
        NdsShipmentPrefill.International international = null;
        if (isInternational) {
            List<NdsShipmentLookupRepository.InternationalItem> items =
                    repository.findInternationalItems(clientCode, h.orderNo(), h.orderSuffix());
            List<NdsShipmentPrefill.Item> mapped = new ArrayList<>(items.size());
            int lineNo = 1;
            for (NdsShipmentLookupRepository.InternationalItem it : items) {
                mapped.add(new NdsShipmentPrefill.Item(
                        parseIntOrNull(h.orderNo()),
                        lineNo++,
                        null, null,
                        it.unitValue(),
                        it.unitValue(),
                        it.description(),
                        it.quantity(),
                        it.countryOfOrigin(),
                        it.hsCode(),
                        null,
                        null));
            }
            BigDecimal totalWeight = packages.stream()
                    .map(NdsShipmentPrefill.Package::weight)
                    .filter(w -> w != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            international = new NdsShipmentPrefill.International(
                    true, packages.size(), totalWeight, null, mapped);
        }

        // Order refs
        List<NdsShipmentPrefill.Order> orders = List.of(new NdsShipmentPrefill.Order(
                parseIntOrNull(h.orderNo()),
                parseIntOrNull(h.orderSuffix()),
                null,
                null));

        return new NdsShipmentPrefill(status, messages, scope, scan.scannedRaw(),
                clientCode, batchId, orders, recipient, shipMethod, packages,
                notifyBlock, international, defaultedFields, stdReplacementErpCode);
    }

    // ═════════════════ helpers ═════════════════════════════════════

    /**
     * X3 — hold detection driven by client_shipvia_code_map.is_hold OR
     * the legacy "HLD" literal. Additive so existing "HLD" behaviour is
     * preserved regardless of mapping state; the DB column lets other
     * ERP codes (HOLD, ONHOLD, etc.) opt in per client.
     */
    private boolean isHoldShipvia(String clientCode, String shipvia) {
        if (shipvia == null) return false;
        if ("HLD".equalsIgnoreCase(shipvia)) return true;
        if (clientCode == null || clientCode.isBlank()) return false;
        return clientShipviaRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode, shipvia)
                .map(m -> Boolean.TRUE.equals(m.getIsHold()))
                .orElse(false);
    }

    /** X1/X2 — raw tenant setting; null when service missing or key unset. */
    private String tenantSetting(String tenantCode, String key) {
        if (tenantSettingsService == null || tenantCode == null || tenantCode.isBlank()) return null;
        return tenantSettingsService.getSetting(tenantCode, key).orElse(null);
    }

    /**
     * X4 — resolve the tenant's default package weight when NDS is
     * missing. Values are numeric strings; falls back to
     * {@link #DEFAULT_WEIGHT_LB} on missing / unparseable rows.
     */
    private BigDecimal tenantDefaultWeight(String tenantCode) {
        String raw = tenantSetting(tenantCode, KEY_DEFAULT_WEIGHT_LB);
        if (raw == null || raw.isBlank()) return DEFAULT_WEIGHT_LB;
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() > 0 ? v : DEFAULT_WEIGHT_LB;
        } catch (NumberFormatException ex) {
            log.warn("nds-lookup: tenant {} has invalid {}={}, using platform default",
                    tenantCode, KEY_DEFAULT_WEIGHT_LB, raw);
            return DEFAULT_WEIGHT_LB;
        }
    }

    /** X4 — {@code nds.on_missing_weight}: BLOCK promotes the weight-defaulted
     *  WARNING to BLOCKED; DEFAULT (or unset) keeps legacy WARNING behavior. */
    private boolean tenantBlocksOnMissingWeight(String tenantCode) {
        return "BLOCK".equalsIgnoreCase(tenantSetting(tenantCode, KEY_ON_MISSING_WEIGHT));
    }

    /**
     * Cross-check operator-picked client-code against the tenant NDS
     * reports for the scan. Blank expected = skip (legacy callers).
     * Mismatch → 422 with both codes named so the operator can pick the
     * right client and rescan.
     */
    private static void assertClientCodeMatches(String expected, String actual,
                                                String kind, String scanned) {
        if (expected == null || expected.isBlank()) return;
        if (actual == null || !expected.equalsIgnoreCase(actual.trim())) {
            throw new IllegalArgumentException(
                    "Scanned " + kind + " " + scanned + " belongs to client "
                            + (actual == null ? "(unknown)" : actual)
                            + ", not the picked client " + expected + ".");
        }
    }

    /** V126 merge — single specificity-ordered lookup. The finder puts
     *  per-client rows ahead of platform-wide (null client) rows. */
    private Long resolveServiceId(String clientCode, String shipviaCd) {
        List<ClientShipviaCodeMap> matches =
                clientShipviaRepo.findMatches(clientCode, shipviaCd, null, null, null);
        return matches.isEmpty() ? null : matches.get(0).getServiceId();
    }

    private static boolean isInternational(String countryCd) {
        if (countryCd == null || countryCd.isBlank()) return false;
        String upper = countryCd.trim().toUpperCase();
        if (US_TERRITORY_CODES.contains(upper)) return true;
        return !"US".equals(upper);
    }

    private static NdsShipmentPrefill.Status worse(NdsShipmentPrefill.Status a,
                                                   NdsShipmentPrefill.Status b) {
        if (a == NdsShipmentPrefill.Status.BLOCKED || b == NdsShipmentPrefill.Status.BLOCKED)
            return NdsShipmentPrefill.Status.BLOCKED;
        if (a == NdsShipmentPrefill.Status.WARNING || b == NdsShipmentPrefill.Status.WARNING)
            return NdsShipmentPrefill.Status.WARNING;
        return NdsShipmentPrefill.Status.OK;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return null; }
    }

    private static Long safeParseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException ex) { return null; }
    }

    /** PR1: OE_SHIP_CONTAINER.SHIPPED_FLAG is not part of the projected row. */
    private static String safeShippedFlag(NdsShipmentLookupRepository.ContainerRow ignored) {
        return null;
    }
}
