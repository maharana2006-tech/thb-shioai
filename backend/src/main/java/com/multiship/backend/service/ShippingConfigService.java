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
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.util.CountryRegions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /** A package chosen for a service: preset + the link's negotiated discount. */
    public record PickedPackage(PackagePreset preset, BigDecimal discountPct) {}

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
    public ApiResponse<Map<String, Object>> catalog() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("services", serviceRepository.findAllByOrderByCarrierAscSortOrderAsc());
        data.put("rules", ruleRepository.findAllByOrderByShipviaCdAsc());
        data.put("links", servicePackageRepository.findAll());
        return success("Shipping catalog retrieved.", data);
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
                                                  String destType, String destValue, Long serviceId) {
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
        return success("Rule saved: " + code + " → " + svc.getName() + ".", rule);
    }

    @Transactional
    public ApiResponse<Void> deleteRule(Long id) {
        ruleRepository.findById(id).ifPresent(ruleRepository::delete);
        return success("Rule removed.", null);
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
                        .serviceId(serviceId).presetId(l.getPresetId()).discountPct(l.getDiscountPct()).build()))
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

        PackagePreset p = id != null ? presetRepository.findById(id).orElse(null) : new PackagePreset();
        if (p == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, "Package preset not found.");
        }
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
     * whose scope fits (international = COUNTRY difference).
     */
    @Transactional(readOnly = true)
    public Optional<ShippingService> resolveService(String canonicalCarrier, String clientCode,
                                                    String orderService, String destCountry, boolean international) {
        Optional<ShippingService> ruled = resolveRule(clientCode, orderService, destCountry)
                .filter(s -> s.getCarrier().equalsIgnoreCase(canonicalCarrier));
        if (ruled.isPresent()) {
            return ruled;
        }
        String neededScope = international ? "INTERNATIONAL" : "DOMESTIC";
        return serviceRepository.findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(canonicalCarrier).stream()
                .filter(s -> "BOTH".equalsIgnoreCase(s.getScope()) || neededScope.equalsIgnoreCase(s.getScope()))
                .findFirst();
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
        List<ServicePackage> links = serviceId != null ? servicePackageRepository.findByServiceId(serviceId) : List.of();
        List<PickedPackage> candidates = links.stream()
                .map(l -> presetRepository.findById(l.getPresetId())
                        .filter(pp2 -> Boolean.TRUE.equals(pp2.getEnabled()))
                        .map(p -> new PickedPackage(p, l.getDiscountPct()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .filter(pp -> pp.preset().getMaxWeight() == null
                        || pp.preset().getMaxWeight().compareTo(actual) >= 0)
                .filter(pp -> com.multiship.backend.util.PackageMath.oversizeStatus(pp.preset())
                        != com.multiship.backend.util.PackageMath.OversizeStatus.OVER_MAX)
                .toList();
        // avoid the $220+ oversize surcharge whenever a normal box also fits
        List<PickedPackage> preferred = candidates.stream()
                .filter(pp -> com.multiship.backend.util.PackageMath.oversizeStatus(pp.preset())
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
                .map(p -> new PickedPackage(p, null));
    }

    /** Default preset regardless of links (fallback + document display). */
    @Transactional(readOnly = true)
    public Optional<PackagePreset> defaultPreset() {
        return presetRepository.findFirstByIsDefaultTrueAndEnabledTrue();
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
