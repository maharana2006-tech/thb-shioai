package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientBillingMarkup;
import com.multiship.backend.model.ClientDestinationRule;
import com.multiship.backend.model.ClientShippingPolicy;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientAllowedServiceDestinationRepository;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientAllowedServiceWarehouseRepository;
import com.multiship.backend.repository.ClientBillingMarkupRepository;
import com.multiship.backend.repository.ClientDestinationRuleRepository;
import com.multiship.backend.repository.ClientShippingPolicyRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShipmentResolutionServiceImpl implements ShipmentResolutionService {

    private final WarehouseRepository warehouseRepository;
    private final ClientWarehouseRepository clientWarehouseRepository;
    private final ClientAllowedServiceRepository allowedServiceRepository;
    private final ClientAllowedServiceDestinationRepository destinationGateRepository;
    private final ClientAllowedServiceWarehouseRepository warehouseGateRepository;
    private final ClientAllowedPackageRepository allowedPackageRepository;
    private final ClientDestinationRuleRepository destinationRuleRepository;
    private final ClientShippingPolicyRepository policyRepository;
    private final ClientBillingMarkupRepository markupRepository;
    private final PackagePresetRepository presetRepository;

    // ===== Warehouse =====

    @Override
    @Transactional(readOnly = true)
    public Optional<Warehouse> resolveWarehouse(String clientCode, String requestedWarehouseCode) {
        String code = normalize(clientCode);
        if (code.isEmpty()) return Optional.empty();

        // Explicit override — the requested warehouse must actually be
        // attached to this client (blocks CLIENT-owned warehouses of
        // other clients from being borrowed via the API).
        if (requestedWarehouseCode != null && !requestedWarehouseCode.isBlank()) {
            Warehouse w = warehouseRepository.findByCodeIgnoreCase(normalize(requestedWarehouseCode))
                    .orElseThrow(() -> new ShipmentResolutionException(ErrorCode.WAREHOUSE_NOT_FOUND,
                            "Warehouse " + normalize(requestedWarehouseCode) + " was not found."));
            boolean attached = clientWarehouseRepository
                    .findByClientCodeIgnoreCaseAndWarehouseId(code, w.getId()).isPresent();
            if (!attached) {
                throw new ShipmentResolutionException(ErrorCode.WAREHOUSE_ATTACH_FORBIDDEN,
                        "Warehouse " + w.getCode() + " is not attached to client " + code + ".");
            }
            return Optional.of(w);
        }

        // No override: default > oldest attached.
        List<ClientWarehouse> attached = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
        if (attached.isEmpty()) return Optional.empty();
        Long warehouseId = attached.get(0).getWarehouseId();
        return warehouseRepository.findById(warehouseId);
    }

    @Override
    public Warehouse assertWarehouse(String clientCode, String requestedWarehouseCode) {
        return resolveWarehouse(clientCode, requestedWarehouseCode)
                .orElseThrow(() -> new ShipmentResolutionException(ErrorCode.NO_DEFAULT_WAREHOUSE,
                        "Client " + normalize(clientCode) + " has no attached warehouses; specify a warehouse code or attach one."));
    }

    @Override
    @Transactional(readOnly = true)
    public Warehouse assertWarehouseById(String clientCode, Long warehouseId) {
        String code = normalize(clientCode);
        if (warehouseId == null) {
            throw new ShipmentResolutionException(ErrorCode.WAREHOUSE_NOT_FOUND,
                    "Warehouse id is required.");
        }
        Warehouse w = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new ShipmentResolutionException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "Warehouse " + warehouseId + " was not found."));
        boolean attached = clientWarehouseRepository
                .findByClientCodeIgnoreCaseAndWarehouseId(code, w.getId()).isPresent();
        if (!attached) {
            throw new ShipmentResolutionException(ErrorCode.WAREHOUSE_ATTACH_FORBIDDEN,
                    "Warehouse " + w.getCode() + " is not attached to client " + code + ".");
        }
        return w;
    }

    // ===== Ship-to =====

    @Override
    @Transactional(readOnly = true)
    public boolean canShipTo(String clientCode, String destCountryCode) {
        String country = normalize(destCountryCode);
        if (country.isEmpty()) return true; // caller should validate presence separately
        List<ClientDestinationRule> rules = destinationRuleRepository
                .findByClientCodeIgnoreCaseOrderByCountryAsc(normalize(clientCode));
        if (rules.isEmpty()) return true; // no rules = ship anywhere
        String mode = rules.get(0).getMode();
        boolean listed = rules.stream().anyMatch(r -> country.equals(normalize(r.getCountry())));
        return ClientDestinationRule.MODE_ALLOW.equalsIgnoreCase(mode) ? listed : !listed;
    }

    @Override
    public void assertShipToAllowed(String clientCode, String destCountryCode) {
        if (!canShipTo(clientCode, destCountryCode)) {
            throw new ShipmentResolutionException(ErrorCode.SHIP_TO_DENIED,
                    "Client " + normalize(clientCode) + " is not permitted to ship to "
                            + normalize(destCountryCode) + ".");
        }
    }

    // ===== Allowlists =====

    @Override
    @Transactional(readOnly = true)
    public Set<Long> allowedServiceIds(String clientCode) {
        return allowedServiceRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(normalize(clientCode))
                .stream()
                .map(row -> row.getServiceId())
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> allowedServiceIds(String clientCode, Long warehouseId) {
        var rows = allowedServiceRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(normalize(clientCode));
        if (warehouseId == null || rows.isEmpty()) {
            return rows.stream().map(row -> row.getServiceId()).collect(Collectors.toSet());
        }
        // For each allowlist row: allowed if the warehouse gate is empty OR
        // contains the requested warehouse. One bulk fetch to avoid N+1.
        List<Long> ids = rows.stream().map(row -> row.getId()).toList();
        var gates = warehouseGateRepository.findByAllowedServiceIdIn(ids);
        java.util.Map<Long, java.util.Set<Long>> gateByService = new java.util.HashMap<>();
        for (var g : gates) {
            gateByService.computeIfAbsent(g.getAllowedServiceId(), k -> new java.util.HashSet<>())
                    .add(g.getWarehouseId());
        }
        return rows.stream()
                .filter(row -> {
                    var gate = gateByService.get(row.getId());
                    return gate == null || gate.isEmpty() || gate.contains(warehouseId);
                })
                .map(row -> row.getServiceId())
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> allowedPackageIds(String clientCode) {
        return allowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(normalize(clientCode))
                .stream()
                .map(row -> row.getPresetId())
                .collect(Collectors.toSet());
    }

    @Override
    public void assertServiceAllowed(String clientCode, Long serviceId, String destCountry) {
        assertServiceAllowed(clientCode, serviceId, destCountry, null);
    }

    @Override
    public void assertServiceAllowed(String clientCode, Long serviceId, String destCountry, Long warehouseId) {
        if (serviceId == null) return; // caller validates presence separately
        String code = normalize(clientCode);
        var allowedRow = allowedServiceRepository
                .findByClientCodeIgnoreCaseAndServiceId(code, serviceId);
        if (allowedRow.isEmpty()) {
            // Backward-compat: no rows means "not configured", treat as
            // unrestricted so onboarding doesn't wedge shipments.
            if (allowedServiceIds(code).isEmpty()) return;
            throw new ShipmentResolutionException(ErrorCode.SERVICE_NOT_ALLOWED,
                    "Service " + serviceId + " is not on client " + code + "'s allowlist.");
        }
        Long allowedRowId = allowedRow.get().getId();

        // Destination gate (Phase 5d): row on the allowlist may restrict which
        // destinations the client may use it for.
        String dest = destCountry == null ? "" : destCountry.trim().toUpperCase(Locale.ROOT);
        if (!dest.isEmpty()) {
            var destinations = destinationGateRepository
                    .findByAllowedServiceIdOrderByCountryAsc(allowedRowId);
            if (!destinations.isEmpty()) {
                boolean listed = destinations.stream()
                        .anyMatch(d -> dest.equalsIgnoreCase(d.getCountry()));
                if (!listed) {
                    throw new ShipmentResolutionException(ErrorCode.SERVICE_NOT_ALLOWED_FOR_DEST,
                            "Client " + code + " may not ship on service " + serviceId
                                    + " to " + dest + " (destination not on the service's gate).");
                }
            }
        }

        // Warehouse gate (G1): row on the allowlist may restrict which
        // warehouses the client may use it FROM. Only consulted when the
        // caller supplied a warehouseId — the internal manual-shipment path
        // may not resolve one, in which case we skip the gate to preserve
        // legacy behaviour.
        if (warehouseId == null) return;
        var warehouses = warehouseGateRepository
                .findByAllowedServiceIdOrderByWarehouseIdAsc(allowedRowId);
        if (warehouses.isEmpty()) return; // unrestricted
        boolean listedWh = warehouses.stream()
                .anyMatch(w -> warehouseId.equals(w.getWarehouseId()));
        if (!listedWh) {
            throw new ShipmentResolutionException(ErrorCode.SERVICE_NOT_ALLOWED_FOR_WAREHOUSE,
                    "Client " + code + " may not ship on service " + serviceId
                            + " from warehouse " + warehouseId + " (warehouse not on the service's gate).");
        }
    }

    @Override
    public void assertPackageAllowed(String clientCode, Long presetId) {
        if (presetId == null) return;
        String code = normalize(clientCode);
        // Ownership cascade (Phase 5d): a CLIENT-owned preset is auto-allowed
        // for its owner, no ClientAllowedPackage row required.
        var preset = presetRepository.findById(presetId).orElse(null);
        if (preset != null
                && com.multiship.backend.model.PackagePreset.OWNER_CLIENT.equalsIgnoreCase(preset.getOwnerType())
                && code.equalsIgnoreCase(preset.getOwnerClientCode())) {
            return;
        }
        Set<Long> allowed = allowedPackageIds(code);
        if (allowed.isEmpty() || allowed.contains(presetId)) return;
        throw new ShipmentResolutionException(ErrorCode.PACKAGE_NOT_ALLOWED,
                "Package " + presetId + " is not on client " + code + "'s allowlist.");
    }

    // ===== Policy + rate strategy =====

    @Override
    @Transactional(readOnly = true)
    public Optional<ClientShippingPolicy> policyFor(String clientCode) {
        return policyRepository.findByClientCodeIgnoreCase(normalize(clientCode));
    }

    @Override
    public Optional<RateOption> pickService(String clientCode, List<RateOption> candidates) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        ClientShippingPolicy policy = policyFor(clientCode)
                // Absent row = platform default (CHEAPEST, no cutoff).
                .orElseGet(() -> ClientShippingPolicy.builder()
                        .clientCode(normalize(clientCode))
                        .rateStrategy(ClientShippingPolicy.STRATEGY_CHEAPEST)
                        .build());

        String strategy = policy.getRateStrategy() != null
                ? policy.getRateStrategy().toUpperCase(Locale.ROOT)
                : ClientShippingPolicy.STRATEGY_CHEAPEST;

        switch (strategy) {
            case ClientShippingPolicy.STRATEGY_FIXED -> {
                Long fixed = policy.getFixedServiceId();
                if (fixed == null) return Optional.empty();
                return candidates.stream()
                        .filter(r -> fixed.equals(r.serviceId()))
                        .findFirst();
            }
            case ClientShippingPolicy.STRATEGY_FASTEST -> {
                // Prefer options with a known ETA. When none of them have one,
                // fall back to cheapest so we still return a pick.
                Optional<RateOption> byEta = candidates.stream()
                        .filter(r -> r.estimatedDeliveryDays() != null)
                        .min(Comparator.comparingInt(RateOption::estimatedDeliveryDays));
                if (byEta.isPresent()) return byEta;
                return candidates.stream()
                        .filter(r -> r.price() != null)
                        .min(Comparator.comparing(RateOption::price));
            }
            default /* CHEAPEST */ -> {
                return candidates.stream()
                        .filter(r -> r.price() != null)
                        .min(Comparator.comparing(RateOption::price));
            }
        }
    }

    // ===== Markup =====

    @Override
    @Transactional(readOnly = true)
    public MarkupApplied applyMarkup(String clientCode, BigDecimal carrierRate, String carrierCurrency) {
        BigDecimal safeRate = carrierRate != null ? carrierRate : BigDecimal.ZERO;
        String normalizedClient = normalize(clientCode);
        Optional<ClientBillingMarkup> row = markupRepository.findByClientCodeIgnoreCase(normalizedClient);

        // Sprint 50 Tier 1 finding #11 — no more silent 0% margin. When a
        // client is named but has no markup row configured, refuse and
        // prompt the admin to set one on the Clients page. Ad-hoc / manual
        // shipments (blank clientCode) legitimately have no markup owner
        // and keep pass-through so ops-issued labels still generate.
        if (row.isEmpty()) {
            if (normalizedClient != null && !normalizedClient.isBlank()) {
                throw new ShipmentResolutionException(ErrorCode.MARKUP_REQUIRED_FOR_CLIENT,
                        "Client " + normalizedClient + " has no billing markup configured. "
                                + "An admin must set one on the Clients page before this "
                                + "client's labels can be billed.");
            }
            String currency = carrierCurrency != null ? carrierCurrency.toUpperCase(Locale.ROOT) : "USD";
            return new MarkupApplied(round(safeRate), round(safeRate),
                    ClientBillingMarkup.KIND_PERCENT, BigDecimal.ZERO, currency);
        }
        ClientBillingMarkup m = row.get();

        String storedCurrency = m.getCurrency() != null ? m.getCurrency().toUpperCase(Locale.ROOT) : "USD";
        String rateCurrency = carrierCurrency != null ? carrierCurrency.toUpperCase(Locale.ROOT) : storedCurrency;
        if (!storedCurrency.equals(rateCurrency)) {
            throw new ShipmentResolutionException(ErrorCode.MARKUP_INVALID,
                    "Client " + normalize(clientCode) + " markup currency " + storedCurrency
                            + " does not match carrier rate currency " + rateCurrency + ".");
        }

        BigDecimal value = m.getValue() != null ? m.getValue() : BigDecimal.ZERO;
        BigDecimal billable = ClientBillingMarkup.KIND_FLAT.equalsIgnoreCase(m.getKind())
                ? safeRate.add(value)
                : safeRate.multiply(BigDecimal.ONE.add(value.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));

        return new MarkupApplied(round(safeRate), round(billable), m.getKind(), value, storedCurrency);
    }

    // ===== Cutoff =====

    @Override
    @Transactional(readOnly = true)
    public boolean isPastCutoff(String clientCode, Instant now) {
        if (now == null) now = Instant.now();
        Optional<ClientShippingPolicy> row = policyRepository.findByClientCodeIgnoreCase(normalize(clientCode));
        if (row.isEmpty()) return false;
        ClientShippingPolicy p = row.get();
        LocalTime cutoff = p.getCutoffTime();
        String tz = p.getCutoffTz();
        if (cutoff == null || tz == null || tz.isBlank()) return false;
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            // Corrupt zone id = ignore the cutoff rather than pretending we know.
            return false;
        }
        ZonedDateTime localNow = now.atZone(zone);
        LocalTime nowTime = localNow.toLocalTime();
        return !nowTime.isBefore(cutoff);
    }

    // ===== helpers =====

    private static String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    /** DECIMAL(12,4) target — round HALF_UP per shipment. */
    private static BigDecimal round(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }
}
