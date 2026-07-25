package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ServicePackage;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ServicePackageRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.util.CountryRegions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The internal SHIP-METHOD engine: order ship-method rules (client +
 * destination aware, most-specific wins), the carrier service catalog, the
 * service↔package links, and the weight-based package auto-pick. Same
 * philosophy as the account cascade and customs resolution — everything
 * resolves automatically, most specific first.
 */
@Service
@RequiredArgsConstructor
public class ShippingConfigService {

    private final ShippingServiceRepository serviceRepository;
    private final ShipViaMappingRepository ruleRepository;
    private final PackagePresetRepository presetRepository;
    private final ServicePackageRepository servicePackageRepository;
    private final com.multiship.backend.repository.ShipMethodRulePackageRepository rulePackageRepository;
    /** The carrier connectors — the source of truth for what a carrier offers per origin. */
    private final List<CarrierConnector> carrierConnectors;
    /** Platform carrier accounts — their credentials authenticate the live availability call. */
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    /** A package chosen for a service. */
    public record PickedPackage(PackagePreset preset) {}

    /**
     * ERP connect code → canonical carrier (mirror of the private map in
     * CarrierServiceImpl — keep both in sync). Lets display surfaces run the
     * FULL service resolution incl. the scope fallback.
     */
    public static String canonicalCarrierFor(String shipviaCd) {
        if (shipviaCd == null || shipviaCd.isBlank()) return "";
        return switch (shipviaCd.trim().toUpperCase(Locale.ROOT)) {
            case "P80" -> "UPS";
            case "F77" -> "FEDEX";
            case "L01" -> "USPS";
            default -> shipviaCd.trim().toUpperCase(Locale.ROOT);
        };
    }

    // ===== Catalog =====

    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> catalog(String originCountry) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<ShippingService> services = StringUtils.hasText(originCountry)
                ? serviceRepository.findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc(originCountry.trim())
                : serviceRepository.findAllByOrderByCarrierAscSortOrderAsc();
        data.put("services", services);
        data.put("rules", ruleRepository.findAllByOrderByShipviaCdAsc());
        data.put("links", servicePackageRepository.findAll());
        // Phase 6: allowed-packages per ship-method rule, flat list; frontend
        // groups by rule_id for display.
        data.put("rulePackages", rulePackageRepository.findAll());
        data.put("originCountries", serviceRepository.findDistinctOriginCountries());
        return success("Shipping catalog retrieved.", data);
    }

    /**
     * Pull a carrier's available services for an origin country and upsert them
     * into the catalog. Authenticates with the carrier's PLATFORM-account
     * credentials and calls the connector's live availability lookup: with real
     * credentials it hits the carrier's API (source CARRIER_API, live=true);
     * without them it falls back to the built-in availability model (source
     * CARRIER_SYNC, live=false) and says so — nobody is misled into thinking
     * simulated data is a live carrier response. Existing (carrier, code,
     * origin) rows keep their enabled state; vanished services are NOT deleted
     * (rules may point at them) — surfaced as setup-health debt instead.
     */
    @Transactional
    public ApiResponse<Map<String, Object>> syncFromCarrier(String carrier, String originCountry) {
        String canonical = canonicalCarrierFor(carrier);
        if (!StringUtils.hasText(canonical)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "A carrier is required.");
        }
        String origin = StringUtils.hasText(originCountry)
                ? originCountry.trim().toUpperCase(Locale.ROOT) : "US";
        CarrierConnector connector = carrierConnectors.stream()
                .filter(c -> c.getCarrierCode().equalsIgnoreCase(canonical))
                .findFirst().orElse(null);
        if (connector == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Unknown carrier: " + carrier + ".");
        }

        // Authenticate with the platform account's real credentials (if any).
        String accessToken = platformAccessToken(connector, canonical);
        CarrierConnector.ServiceAvailability availability = connector.listServices(origin, accessToken);

        // Live-only policy: services are persisted ONLY from a genuinely verified,
        // live carrier call. If the connection didn't authenticate live (no/invalid
        // credentials, carrier unreachable), refuse rather than importing the
        // built-in fallback list — nothing is written.
        if (!availability.live()) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    canonical + " is not verified live (" + availability.via() + "). "
                    + "Connect and verify a live " + canonical + " account before syncing services — "
                    + "no services were added.");
        }

        List<CarrierConnector.ServiceOffering> offerings = availability.offerings();
        String source = "CARRIER_API";

        LocalDateTime now = LocalDateTime.now();
        int added = 0, updated = 0, sort = 0;
        for (CarrierConnector.ServiceOffering off : offerings) {
            // Standard carrier package limits for this service, filled in by default.
            com.multiship.backend.util.PackageMath.ServiceLimits lim =
                    com.multiship.backend.util.PackageMath.defaultLimits(canonical, off.serviceCode());
            ShippingService existing = serviceRepository
                    .findByCarrierIgnoreCaseAndServiceCodeIgnoreCaseAndOriginCountryIgnoreCase(
                            canonical, off.serviceCode(), origin)
                    .orElse(null);
            if (existing == null) {
                serviceRepository.save(ShippingService.builder()
                        .carrier(canonical).serviceCode(off.serviceCode()).name(off.name()).scope(off.scope())
                        .originCountry(origin).source(source).syncedAt(now)
                        .maxWeightLb(lim.maxWeightLb()).maxLengthIn(lim.maxLengthIn())
                        .maxLengthGirthIn(lim.maxLengthGirthIn()).surchargeLengthGirthIn(lim.surchargeLengthGirthIn())
                        .enabled(true).sortOrder(sort++).build());
                added++;
            } else {
                existing.setName(off.name());
                existing.setScope(off.scope());
                existing.setSource(source);
                existing.setSyncedAt(now);
                existing.setSortOrder(sort++);
                // Fill limits only where the admin hasn't set them (preserve overrides).
                if (existing.getMaxWeightLb() == null) existing.setMaxWeightLb(lim.maxWeightLb());
                if (existing.getMaxLengthIn() == null) existing.setMaxLengthIn(lim.maxLengthIn());
                if (existing.getMaxLengthGirthIn() == null) existing.setMaxLengthGirthIn(lim.maxLengthGirthIn());
                if (existing.getSurchargeLengthGirthIn() == null)
                    existing.setSurchargeLengthGirthIn(lim.surchargeLengthGirthIn());
                serviceRepository.save(existing);
                updated++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrier", canonical);
        data.put("originCountry", origin);
        data.put("added", added);
        data.put("updated", updated);
        data.put("total", offerings.size());
        data.put("live", availability.live());
        data.put("via", availability.via());
        String outcome = offerings.isEmpty()
                ? canonical + " offers no services from " + origin
                : "Synced " + canonical + " from " + origin + ": " + added + " new, " + updated + " refreshed";
        String msg = outcome + " (" + availability.via() + ").";
        return success(msg, data);
    }

    /**
     * Pull a carrier's PREDEFINED PACKAGING for an origin country and upsert it
     * as CARRIER-kind presets — the carrier's fixed dimensions, weight cap and
     * flat-rate nature are filled in automatically (USPS Flat Rate = US-only,
     * FedEx One Rate = US-domestic, 10/25KG boxes = international). Custom boxes
     * are never touched. Upserts by (carrier, code, origin); keeps enabled state.
     */
    @Transactional
    public ApiResponse<Map<String, Object>> syncPackagesFromCarrier(String carrier, String originCountry) {
        String canonical = canonicalCarrierFor(carrier);
        if (!StringUtils.hasText(canonical)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "A carrier is required.");
        }
        String origin = StringUtils.hasText(originCountry) ? originCountry.trim().toUpperCase(Locale.ROOT) : "US";
        CarrierConnector connector = carrierConnectors.stream()
                .filter(c -> c.getCarrierCode().equalsIgnoreCase(canonical)).findFirst().orElse(null);
        if (connector == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "Unknown carrier: " + carrier + ".");
        }

        String token = platformAccessToken(connector, canonical);
        CarrierConnector.PackageAvailability availability = connector.listPackages(origin, token);

        // Live-only policy (same as services): packaging is persisted ONLY from a
        // genuinely verified, live carrier account. If the connection didn't
        // authenticate live, refuse rather than importing built-in packaging.
        if (!availability.live()) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    canonical + " is not verified live (" + availability.via() + "). "
                    + "Connect and verify a live " + canonical + " account before syncing packaging — "
                    + "no packages were added.");
        }

        String source = "CARRIER_API";
        int added = 0, updated = 0, sort = 100;
        for (CarrierConnector.PackageOffering off : availability.offerings()) {
            PackagePreset existing = presetRepository
                    .findByCarrierIgnoreCaseAndCarrierPackageCodeIgnoreCaseAndOriginCountryIgnoreCase(
                            canonical, off.code(), origin).orElse(null);
            PackagePreset p = existing != null ? existing : new PackagePreset();
            p.setName(off.name());
            p.setKind("CARRIER");
            p.setCarrier(canonical);
            p.setCarrierPackageCode(off.code());
            p.setOriginCountry(origin);
            p.setSource(source);
            p.setScope(off.scope());
            p.setLength(off.length());
            p.setWidth(off.width());
            p.setHeight(off.height());
            p.setDimUnit("IN");
            p.setMaxWeight(off.maxWeight());
            p.setWeightUnit("LB");
            p.setFlatRate(off.flatRate());
            if (p.getEnabled() == null) p.setEnabled(true);
            if (p.getSortOrder() == null) p.setSortOrder(sort++);
            presetRepository.save(p);
            if (existing == null) added++; else updated++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrier", canonical);
        data.put("originCountry", origin);
        data.put("added", added);
        data.put("updated", updated);
        data.put("total", availability.offerings().size());
        data.put("live", availability.live());
        data.put("via", availability.via());
        String head = availability.offerings().isEmpty()
                ? canonical + " offers no packaging from " + origin
                : "Synced " + canonical + " packaging from " + origin + ": " + added + " new, " + updated + " refreshed";
        return success(head + " (" + availability.via() + ").", data);
    }

    /**
     * A real OAuth token for the carrier's platform account, or null when no
     * platform credentials are configured. The connector still returns a local
     * fallback token in dev — the availability lookup treats that as "not live".
     */
    private String platformAccessToken(CarrierConnector connector, String canonicalCarrier) {
        CarrierAccountRef account = carrierAccountRefRepository.findPlatformAccountsByCarrier(canonicalCarrier)
                .stream()
                .filter(a -> StringUtils.hasText(a.getClientId()) && StringUtils.hasText(a.getClientSecret()))
                .findFirst().orElse(null);
        if (account == null) {
            return null;
        }
        try {
            return connector.getAccessToken(account.getClientId(), account.getClientSecret(),
                    account.getAccountNumber());
        } catch (Exception ex) {
            return null;
        }
    }

    @Transactional
    public ApiResponse<ShippingService> setServiceEnabled(Long id, boolean enabled) {
        ShippingService svc = serviceRepository.findById(id).orElse(null);
        if (svc == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Service not found.");
        }
        svc.setEnabled(enabled);
        serviceRepository.save(svc);
        return success((enabled ? "Enabled " : "Disabled ") + svc.getName() + ".", svc);
    }

    // ===== Ship-method rules =====

    @Transactional
    public ApiResponse<ShipViaMapping> upsertRule(Long id, String shipviaCd, String clientCode,
                                                  String destType, String destValue, Long serviceId,
                                                  List<Long> allowedPresetIds) {
        String code = norm(shipviaCd);
        if (code.isEmpty()) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "An order ship-method code is required.");
        }
        ShippingService svc = serviceId != null ? serviceRepository.findById(serviceId).orElse(null) : null;
        if (svc == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "Pick a valid carrier service.");
        }
        String client = StringUtils.hasText(clientCode) ? clientCode.trim().toUpperCase(Locale.ROOT) : null;
        String type = StringUtils.hasText(destType) ? destType.trim().toUpperCase(Locale.ROOT) : "ANY";
        if (!List.of("ANY", "COUNTRIES", "REGION", "COUNTRY").contains(type)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Destination type must be ANY or COUNTRIES.");
        }
        String value;
        if ("ANY".equals(type)) {
            value = null;
        } else if ("COUNTRIES".equals(type)) {
            // Normalize the zone: uppercase, de-dupe, sorted, space-separated.
            value = !StringUtils.hasText(destValue) ? null
                    : java.util.Arrays.stream(destValue.trim().toUpperCase(Locale.ROOT).split("[\\s,]+"))
                            .filter(StringUtils::hasText).distinct().sorted()
                            .reduce((a, b) -> a + " " + b).orElse(null);
        } else {
            value = StringUtils.hasText(destValue)
                    ? ("COUNTRY".equals(type) ? destValue.trim().toUpperCase(Locale.ROOT) : destValue.trim())
                    : null;
        }
        if (!"ANY".equals(type) && value == null) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Pick at least one destination country for this rule.");
        }

        // One rule per (code, client, destination) — duplicates would make
        // resolution ambiguous.
        Optional<ShipViaMapping> clash = ruleRepository.findByShipviaCdIgnoreCase(code).stream()
                .filter(r -> Objects.equals(r.getClientCode(), client)
                        && normType(r).equals(type)
                        && Objects.equals(r.getDestValue(), value))
                .findFirst();
        if (clash.isPresent() && (id == null || !clash.get().getId().equals(id))) {
            return failure(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "A rule for this code + client + destination already exists — edit that one instead.");
        }

        ShipViaMapping rule = id != null ? ruleRepository.findById(id).orElse(null)
                : ShipViaMapping.builder().build();
        if (rule == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Rule not found.");
        }
        rule.setShipviaCd(code);
        rule.setClientCode(client);
        rule.setDestType(type);
        rule.setDestValue(value);
        rule.setServiceId(svc.getId());
        ruleRepository.save(rule);

        // Phase 6 — allowed packages on the rule. Diff-free replace: wipe
        // then insert. Flush the delete first so uq_rule_package doesn't
        // trip when a preset is re-linked in the same call.
        List<Long> ids = allowedPresetIds == null ? List.of()
                : allowedPresetIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .filter(presetRepository::existsById)
                        .toList();
        rulePackageRepository.deleteAllByRuleId(rule.getId());
        rulePackageRepository.flush();
        for (Long presetId : ids) {
            rulePackageRepository.save(com.multiship.backend.model.ShipMethodRulePackage.builder()
                    .ruleId(rule.getId()).presetId(presetId).build());
        }

        // Weight fit: warn (don't block) when a linked preset's max weight
        // exceeds the service's carrier cap. Normalise kg → lb.
        List<String> warnings = weightWarnings(svc, ids);
        String base = "Rule saved: " + code + " → " + svc.getName() + ".";
        String msg = warnings.isEmpty() ? base
                : base + " " + String.join(" ", warnings);
        return success(msg, rule);
    }

    @Transactional
    public ApiResponse<Void> deleteRule(Long id) {
        ruleRepository.findById(id).ifPresent(rule -> {
            // Cascade the rule's allowed-package rows so we don't leave
            // orphans that break the uq index on future re-adds.
            rulePackageRepository.deleteAllByRuleId(rule.getId());
            ruleRepository.delete(rule);
        });
        return success("Rule removed.", null);
    }

    /**
     * Compute human-readable warnings for presets whose max weight exceeds
     * the service's carrier cap. Advisory only — the save still persists.
     * Returns an empty list when everything fits or the service has no cap.
     */
    private List<String> weightWarnings(ShippingService svc, List<Long> presetIds) {
        Integer cap = svc.getMaxWeightLb();
        if (cap == null || presetIds.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Long pid : presetIds) {
            PackagePreset p = presetRepository.findById(pid).orElse(null);
            if (p == null || p.getMaxWeight() == null) continue;
            double lb = "KG".equalsIgnoreCase(p.getWeightUnit())
                    ? p.getMaxWeight().doubleValue() * 2.20462
                    : p.getMaxWeight().doubleValue();
            if (lb > cap) {
                out.add("Warning: '" + p.getName() + "' (" + Math.round(lb) + " lb) exceeds "
                        + svc.getName() + "'s " + cap + " lb cap.");
            }
        }
        return out;
    }

    // ===== Service ↔ package links =====

    /** Replace one service's allowed-package list (presetId + optional discount %). */
    @Transactional
    public ApiResponse<List<ServicePackage>> setServicePackages(Long serviceId, List<ServicePackage> links) {
        if (serviceRepository.findById(serviceId).isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Service not found.");
        }
        servicePackageRepository.deleteByServiceId(serviceId);
        // Hibernate flushes INSERTs before DELETEs — force the delete out first
        // or re-linking an existing preset trips uq_service_package mid-flush.
        servicePackageRepository.flush();
        List<ServicePackage> saved = links.stream()
                .filter(l -> l.getPresetId() != null && presetRepository.existsById(l.getPresetId()))
                .map(l -> servicePackageRepository.save(ServicePackage.builder()
                        .serviceId(serviceId).presetId(l.getPresetId()).build()))
                .toList();
        return success("Allowed packages saved (" + saved.size() + ").", saved);
    }

    // ===== Package presets (unchanged CRUD) =====

    @Transactional(readOnly = true)
    public ApiResponse<List<PackagePreset>> listPresets() {
        return success("Package presets retrieved.", presetRepository.findAllByOrderByIsDefaultDescNameAsc());
    }

    @Transactional
    public ApiResponse<PackagePreset> savePreset(Long id, PackagePreset request) {
        if (!StringUtils.hasText(request.getName())) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR, "A package name is required.");
        }
        boolean carrierKind = "CARRIER".equalsIgnoreCase(request.getKind());
        if (carrierKind && !StringUtils.hasText(request.getCarrierPackageCode())) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Carrier packaging needs the carrier's package code.");
        }
        if (!carrierKind && (request.getLength() == null || request.getWidth() == null || request.getHeight() == null)) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "A custom box needs length, width and height.");
        }
        Optional<PackagePreset> clash = presetRepository.findByNameIgnoreCase(request.getName().trim());
        if (clash.isPresent() && (id == null || !clash.get().getId().equals(id))) {
            return failure(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "A package named '" + request.getName().trim() + "' already exists.");
        }

        // Ownership invariants (Phase 5a): PLATFORM forbids owner_client_code,
        // CLIENT requires it. Absent ownerType defaults to PLATFORM so existing
        // callers that don't yet set it keep working.
        String ownerType = StringUtils.hasText(request.getOwnerType())
                ? request.getOwnerType().trim().toUpperCase(Locale.ROOT)
                : PackagePreset.OWNER_PLATFORM;
        String ownerClient = StringUtils.hasText(request.getOwnerClientCode())
                ? request.getOwnerClientCode().trim().toUpperCase(Locale.ROOT)
                : null;
        if (!PackagePreset.OWNER_PLATFORM.equals(ownerType) && !PackagePreset.OWNER_CLIENT.equals(ownerType)) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "ownerType must be PLATFORM or CLIENT.");
        }
        if (PackagePreset.OWNER_CLIENT.equals(ownerType) && ownerClient == null) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "CLIENT-owned package requires ownerClientCode.");
        }
        if (PackagePreset.OWNER_PLATFORM.equals(ownerType) && ownerClient != null) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.WAREHOUSE_OWNER_INVALID,
                    "PLATFORM package must not carry ownerClientCode.");
        }

        PackagePreset p = id != null ? presetRepository.findById(id).orElse(null) : new PackagePreset();
        if (p == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Package preset not found.");
        }
        // A hand-created box is real data authored by the admin — tag it CUSTOM so
        // it's never mistaken for demo/starter data (source=SEEDED) or carrier-synced
        // packaging (source=CARRIER_SYNC/CARRIER_API). Edits keep the existing source.
        if (id == null) {
            p.setSource("CUSTOM");
        }
        p.setOwnerType(ownerType);
        p.setOwnerClientCode(ownerClient);
        p.setName(request.getName().trim());
        p.setKind(carrierKind ? "CARRIER" : "CUSTOM");
        p.setCarrierPackageCode(carrierKind ? request.getCarrierPackageCode().trim() : null);
        p.setCarrier(StringUtils.hasText(request.getCarrier()) ? request.getCarrier().trim().toUpperCase(Locale.ROOT) : null);
        p.setLength(request.getLength());
        p.setWidth(request.getWidth());
        p.setHeight(request.getHeight());
        p.setDimUnit(StringUtils.hasText(request.getDimUnit()) ? request.getDimUnit().toUpperCase(Locale.ROOT) : "IN");
        p.setMaxWeight(request.getMaxWeight());
        p.setWeightUnit(StringUtils.hasText(request.getWeightUnit()) ? request.getWeightUnit().toUpperCase(Locale.ROOT) : "LB");
        p.setTareWeight(request.getTareWeight());
        p.setInternalLength(request.getInternalLength());
        p.setInternalWidth(request.getInternalWidth());
        p.setInternalHeight(request.getInternalHeight());
        p.setBoxCost(request.getBoxCost());
        p.setFlatRate(request.getFlatRate());
        p.setSortOrder(request.getSortOrder());
        p.setEnabled(request.getEnabled() == null || request.getEnabled());
        presetRepository.save(p);
        return success("Package '" + p.getName() + "' saved.", p);
    }

    @Transactional
    public ApiResponse<PackagePreset> setDefaultPreset(Long id) {
        PackagePreset target = presetRepository.findById(id).orElse(null);
        if (target == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Package preset not found.");
        }
        presetRepository.findByIsDefaultTrue().forEach(p -> {
            p.setIsDefault(false);
            presetRepository.save(p);
        });
        target.setIsDefault(true);
        target.setEnabled(true);
        presetRepository.save(target);
        return success("'" + target.getName() + "' is now the default package.", target);
    }

    @Transactional
    public ApiResponse<Void> deletePreset(Long id) {
        PackagePreset p = presetRepository.findById(id).orElse(null);
        if (p == null) {
            return success("Package preset removed.", null);
        }
        if (Boolean.TRUE.equals(p.getIsDefault())) {
            return failure(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "'" + p.getName() + "' is the default package — set another default first.");
        }
        presetRepository.delete(p);
        return success("Package preset removed.", null);
    }

    // ===== Resolution (used at label time) =====

    /**
     * The winning ship-method rule for (client, order ship-method, destination):
     * rules that don't match are excluded; among matches, specificity wins —
     * client match scores 4, COUNTRY destination 2, REGION 1 (so
     * client+country=6 beats client+any=4 beats global+country=2 beats global=0).
     */
    @Transactional(readOnly = true)
    public Optional<ShippingService> resolveRule(String clientCode, String orderService, String destCountry) {
        if (!StringUtils.hasText(orderService)) return Optional.empty();
        String client = StringUtils.hasText(clientCode) ? clientCode.trim().toUpperCase(Locale.ROOT) : null;
        String region = CountryRegions.regionOf(destCountry);

        String dest = destCountry != null ? destCountry.trim().toUpperCase(Locale.ROOT) : "";
        return ruleRepository.findByShipviaCdIgnoreCase(orderService.trim()).stream()
                .filter(r -> r.getClientCode() == null || r.getClientCode().equalsIgnoreCase(client == null ? "" : client))
                .filter(r -> switch (normType(r)) {
                    // zone membership: the ship-to country is one of the rule's set
                    case "COUNTRIES" -> !dest.isEmpty() && r.getDestValue() != null
                            && (" " + r.getDestValue() + " ").contains(" " + dest + " ");
                    case "COUNTRY" -> !dest.isEmpty() && dest.equalsIgnoreCase(r.getDestValue());
                    case "REGION" -> region.equalsIgnoreCase(r.getDestValue());
                    default -> true;
                })
                .max(Comparator
                        .comparingInt((ShipViaMapping r) -> (r.getClientCode() != null ? 4 : 0)
                                + (switch (normType(r)) {
                                    case "COUNTRIES", "COUNTRY" -> 2;
                                    case "REGION" -> 1;
                                    default -> 0;
                                }))
                        .thenComparing(Comparator.comparing(ShipViaMapping::getId).reversed()))
                .flatMap(r -> serviceRepository.findById(r.getServiceId()))
                .filter(ShippingService::isEnabled);
    }

    /**
     * The service a shipment rides: the winning rule when its service matches
     * the shipping carrier; otherwise the carrier's first enabled service
     * whose scope fits (international = COUNTRY difference). When an origin
     * country is known, services offered FROM that origin win; if none are
     * synced for that origin the resolution falls back to any enabled service
     * for the carrier so existing lanes keep generating.
     */
    @Transactional(readOnly = true)
    public Optional<ShippingService> resolveService(String canonicalCarrier, String clientCode, String orderService,
                                                    String destCountry, boolean international, String originCountry) {
        Optional<ShippingService> ruled = resolveRule(clientCode, orderService, destCountry)
                .filter(s -> s.getCarrier().equalsIgnoreCase(canonicalCarrier));
        if (ruled.isPresent()) {
            return ruled;
        }
        String neededScope = international ? "INTERNATIONAL" : "DOMESTIC";
        String origin = StringUtils.hasText(originCountry) ? originCountry.trim() : null;
        List<ShippingService> enabled =
                serviceRepository.findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(canonicalCarrier);
        java.util.function.Predicate<ShippingService> scopeFits = s ->
                "BOTH".equalsIgnoreCase(s.getScope()) || neededScope.equalsIgnoreCase(s.getScope());
        if (origin != null) {
            Optional<ShippingService> byOrigin = enabled.stream()
                    .filter(s -> origin.equalsIgnoreCase(s.getOriginCountry()))
                    .filter(scopeFits)
                    .findFirst();
            if (byOrigin.isPresent()) {
                return byOrigin;
            }
        }
        return enabled.stream().filter(scopeFits).findFirst();
    }

    /** Origin-agnostic overload (kept for callers without a known origin). */
    @Transactional(readOnly = true)
    public Optional<ShippingService> resolveService(String canonicalCarrier, String clientCode,
                                                    String orderService, String destCountry, boolean international) {
        return resolveService(canonicalCarrier, clientCode, orderService, destCountry, international, null);
    }

    /**
     * The package a shipment goes in — chosen by what the CARRIER WILL BILL,
     * not just what fits: among the service's linked packages that can carry
     * the order's weight and aren't over parcel limits, prefer non-surcharge
     * boxes, then minimize BILLABLE weight (max of actual+tare and DIM
     * weight; flat-rate boxes ignore DIM), then the sort-order tie-break.
     * Services with no links use the global default preset.
     */
    @Transactional(readOnly = true)
    public Optional<PickedPackage> pickPackage(Long serviceId, BigDecimal orderWeight) {
        BigDecimal actual = orderWeight != null ? orderWeight : BigDecimal.ONE;
        // The resolved service's own package limits (falling back to carrier
        // defaults) — so a box legal on UPS but over-max on USPS is excluded
        // when the shipment actually rides USPS.
        com.multiship.backend.util.PackageMath.ServiceLimits limits = serviceId != null
                ? serviceRepository.findById(serviceId)
                        .map(s -> com.multiship.backend.util.PackageMath.limitsOf(s.getCarrier(), s.getServiceCode(),
                                s.getMaxWeightLb(), s.getMaxLengthIn(), s.getMaxLengthGirthIn(), s.getSurchargeLengthGirthIn()))
                        .orElse(null)
                : null;
        List<ServicePackage> links = serviceId != null ? servicePackageRepository.findByServiceId(serviceId) : List.of();
        List<PickedPackage> candidates = links.stream()
                .map(l -> presetRepository.findById(l.getPresetId())
                        .filter(pp2 -> Boolean.TRUE.equals(pp2.getEnabled()))
                        .map(PickedPackage::new)
                        .orElse(null))
                .filter(Objects::nonNull)
                .filter(pp -> pp.preset().getMaxWeight() == null
                        || pp.preset().getMaxWeight().compareTo(actual) >= 0)
                .filter(pp -> com.multiship.backend.util.PackageMath.oversizeStatus(pp.preset(), limits)
                        != com.multiship.backend.util.PackageMath.OversizeStatus.OVER_MAX)
                .toList();
        // avoid the $220+ oversize surcharge whenever a normal box also fits
        List<PickedPackage> preferred = candidates.stream()
                .filter(pp -> com.multiship.backend.util.PackageMath.oversizeStatus(pp.preset(), limits)
                        == com.multiship.backend.util.PackageMath.OversizeStatus.OK)
                .toList();
        Optional<PickedPackage> linked = (preferred.isEmpty() ? candidates : preferred).stream()
                .min(Comparator
                        .comparing((PickedPackage pp) ->
                                com.multiship.backend.util.PackageMath.billableWeight(pp.preset(), actual))
                        .thenComparing(pp -> pp.preset().getSortOrder() != null
                                ? pp.preset().getSortOrder() : Integer.MAX_VALUE)
                        .thenComparing(pp -> pp.preset().getMaxWeight() != null
                                ? pp.preset().getMaxWeight() : new BigDecimal("999999")));
        if (linked.isPresent()) {
            return linked;
        }
        return presetRepository.findFirstByIsDefaultTrueAndEnabledTrue()
                .map(PickedPackage::new);
    }

    /** Default preset regardless of links (fallback + document display). */
    @Transactional(readOnly = true)
    public Optional<PackagePreset> defaultPreset() {
        return presetRepository.findFirstByIsDefaultTrueAndEnabledTrue();
    }

    /** Look up a service by id (for the manual-shipment flow's explicit service pick). */
    @Transactional(readOnly = true)
    public Optional<ShippingService> serviceById(Long id) {
        return id == null ? Optional.empty() : serviceRepository.findById(id);
    }

    /** Look up a package preset by id (for the manual-shipment flow's explicit package pick). */
    @Transactional(readOnly = true)
    public Optional<PackagePreset> presetById(Long id) {
        return id == null ? Optional.empty() : presetRepository.findById(id);
    }

    // ===== helpers =====

    private String normType(ShipViaMapping r) {
        return r.getDestType() == null || r.getDestType().isBlank() ? "ANY" : r.getDestType().toUpperCase(Locale.ROOT);
    }

    private String norm(String v) { return v != null ? v.trim().toUpperCase(Locale.ROOT) : ""; }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder().status("SUCCESS").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder().status("ERROR").code(status.value()).errorCode(errorCode.name())
                .message(message).timestamp(LocalDateTime.now()).build();
    }
}
