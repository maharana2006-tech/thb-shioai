package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.TenantScopeEnforcer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Business orchestrator for the NDS Shipment prefill feature.
 * Composes {@link NdsScanValueParser} + {@link NdsShipmentLookupRepository}
 * + {@link ClientShipviaCodeMapRepository} / {@link ShipViaMappingRepository}
 * into a single {@link NdsShipmentPrefill} response.
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

    /** Ops-provided default when no notify email is present on the order. */
    public static final String DEFAULT_NOTIFY_EMAIL = "support@thbred.com";

    private final NdsShipmentLookupRepository repository;
    private final ClientShipviaCodeMapRepository clientShipviaRepo;
    private final ShipViaMappingRepository shipviaMappingRepo;
    private final TenantScopeEnforcer tenantScopeEnforcer;

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
        NdsScanValue scan = NdsScanValueParser.parse(rawScan);
        return switch (scan.scope()) {
            case DIRECT -> lookupDirect(scan);
            case BATCH  -> lookupBatch(scan);
        };
    }

    // ═════════════════ .X — DIRECT container lookup ══════════════════

    private Optional<NdsShipmentPrefill> lookupDirect(NdsScanValue scan) {
        Optional<NdsShipmentLookupRepository.ContainerOwner> owner =
                repository.findContainerOwner(scan.stripped());
        if (owner.isEmpty()) {
            log.info("nds-lookup DIRECT: container {} not found", scan.stripped());
            return Optional.empty();
        }
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
        return Optional.of(buildResponse(scan, NdsShipmentPrefill.Scope.DIRECT, clientCode,
                null, header.get(), containers, scan.stripped()));
    }

    // ═════════════════ .Y — BATCH lookup ════════════════════════════

    private Optional<NdsShipmentPrefill> lookupBatch(NdsScanValue scan) {
        Optional<NdsShipmentLookupRepository.BatchOwner> owner =
                repository.findBatchOwner(scan.stripped());
        if (owner.isEmpty()) {
            log.info("nds-lookup BATCH: batch {} not found", scan.stripped());
            return Optional.empty();
        }
        List<NdsShipmentLookupRepository.BatchContainer> contents =
                repository.findBatchContents(scan.stripped());
        if (contents.isEmpty()) {
            log.info("nds-lookup BATCH: batch {} has no containers", scan.stripped());
            return Optional.empty();
        }
        String clientCode = tenantScopeEnforcer.clampClientCode(owner.get().primaryClientCode());
        // ShipX rule: address = order with the lowest container id.
        NdsShipmentLookupRepository.BatchContainer anchor = contents.get(0);
        Optional<NdsShipmentLookupRepository.OrderHeader> header =
                repository.findOrderHeader(clientCode, anchor.orderNo(), anchor.orderSuffix());
        if (header.isEmpty()) {
            log.info("nds-lookup BATCH: anchor order {}/{} missing for client {}",
                    anchor.orderNo(), anchor.orderSuffix(), clientCode);
            return Optional.empty();
        }
        List<String> containerIds = contents.stream().map(
                NdsShipmentLookupRepository.BatchContainer::containerId).toList();
        List<NdsShipmentLookupRepository.BatchPackage> pkgRows =
                repository.findBatchPackagesGrouped(clientCode, containerIds);
        // Reshape BatchPackage rows to ContainerRow so buildResponse handles both flows.
        List<NdsShipmentLookupRepository.ContainerRow> asContainers = pkgRows.stream()
                .map(b -> new NdsShipmentLookupRepository.ContainerRow(
                        b.containerId(), b.orderNo(), b.orderSuffix(),
                        b.billableWeightLb(), b.lengthIn(), b.widthIn(), b.heightIn(),
                        b.packageTypeCd()))
                .toList();
        return Optional.of(buildResponse(scan, NdsShipmentPrefill.Scope.BATCH, clientCode,
                scan.stripped(), header.get(), asContainers, /*scannedContainer*/ null));
    }

    // ═════════════════ shared DTO assembly ══════════════════════════

    private NdsShipmentPrefill buildResponse(NdsScanValue scan,
                                             NdsShipmentPrefill.Scope scope,
                                             String clientCode,
                                             String batchId,
                                             NdsShipmentLookupRepository.OrderHeader h,
                                             List<NdsShipmentLookupRepository.ContainerRow> containers,
                                             String scannedContainer) {
        List<NdsShipmentPrefill.Message> messages = new ArrayList<>();
        List<String> defaultedFields = new ArrayList<>();
        NdsShipmentPrefill.Status status = NdsShipmentPrefill.Status.OK;

        // Ship method resolution + hard blocks
        String shipvia = h.shipviaCd();
        Long mappedServiceId = null;
        if (shipvia == null || shipvia.isBlank()) {
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order has no ship method (SHIPVIA_CD blank)."));
            status = NdsShipmentPrefill.Status.BLOCKED;
        } else if ("HLD".equalsIgnoreCase(shipvia)) {
            messages.add(new NdsShipmentPrefill.Message(
                    NdsShipmentPrefill.Message.Severity.BLOCKED,
                    "Order is on hold (SHIPVIA_CD = HLD)."));
            status = NdsShipmentPrefill.Status.BLOCKED;
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
        // Already-shipped block — either OEHEAD or any container.
        if ("Y".equalsIgnoreCase(h.shippedFlag())
                || containers.stream().anyMatch(c -> c.orderSuffix() != null
                    && "Y".equalsIgnoreCase(safeShippedFlag(c)))) {
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
        NdsPhoneNormalizer.Result phone = NdsPhoneNormalizer.normalize(h.shipToPhone());
        if (phone.defaulted()) defaultedFields.add("recipient.phone");
        NdsShipmentPrefill.Recipient recipient = new NdsShipmentPrefill.Recipient(
                NdsAddressSanitizer.sanitize(h.shipToAttn()),
                NdsAddressSanitizer.sanitize(h.shipToName()),
                NdsAddressSanitizer.sanitize(h.shipToAddr1()),
                h.shipToAddr2(),
                h.shipToAddr3(),
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

        // Packages — one per container row
        List<NdsShipmentPrefill.Package> packages = new ArrayList<>(containers.size());
        int seq = 1;
        for (NdsShipmentLookupRepository.ContainerRow c : containers) {
            boolean isScanned = scannedContainer != null && scannedContainer.equals(c.containerId());
            BigDecimal weight = c.billableWeightLb();
            String weightSource = weight != null ? "OE_SHIP_CONTAINER.BILLABLE_WEIGHT_LB" : null;
            packages.add(new NdsShipmentPrefill.Package(
                    seq++,
                    c.containerId(),
                    List.of(safeParseLong(c.containerId())).stream().filter(v -> v != null).toList(),
                    List.of(parseIntOrNull(c.orderNo())).stream().filter(v -> v != null).toList(),
                    parseIntOrNull(c.orderSuffix()),
                    weight,
                    weightSource,
                    c.lengthIn(),
                    c.widthIn(),
                    c.heightIn(),
                    null,   // packDt — not queried in PR1
                    null,   // shippedFlag per container — not queried in PR1
                    isScanned));
            if (weight == null) {
                messages.add(new NdsShipmentPrefill.Message(
                        NdsShipmentPrefill.Message.Severity.WARNING,
                        "Container " + c.containerId() + " has no billable weight in NDS."));
                status = worse(status, NdsShipmentPrefill.Status.WARNING);
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
            sendTo = DEFAULT_NOTIFY_EMAIL;
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
                notifyBlock, international, defaultedFields);
    }

    // ═════════════════ helpers ═════════════════════════════════════

    /** Client-scoped code map first (exact match), fall back to global rule. */
    private Long resolveServiceId(String clientCode, String shipviaCd) {
        Optional<ClientShipviaCodeMap> perClient =
                clientShipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode, shipviaCd);
        if (perClient.isPresent()) return perClient.get().getServiceId();
        List<ShipViaMapping> global = shipviaMappingRepo.findByShipviaCdIgnoreCase(shipviaCd);
        if (global.isEmpty()) return null;
        // Prefer a client-narrowed rule when present, else the first any-client rule.
        return global.stream()
                .filter(r -> clientCode.equalsIgnoreCase(r.getClientCode()))
                .findFirst()
                .or(() -> global.stream().filter(r -> r.getClientCode() == null).findFirst())
                .or(() -> global.stream().findFirst())
                .map(ShipViaMapping::getServiceId)
                .orElse(null);
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
