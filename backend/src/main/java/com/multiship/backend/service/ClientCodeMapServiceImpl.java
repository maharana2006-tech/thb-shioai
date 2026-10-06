package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCodeMapDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpsertClientCodeMapRequest;
import com.multiship.backend.model.ClientDestCountryMap;
import com.multiship.backend.model.ClientPackageCodeMap;
import com.multiship.backend.model.ClientServiceCodeMap;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.model.ClientShipviaCodeMapPackage;
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapPackageRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClientCodeMapServiceImpl implements ClientCodeMapService {

    private final ClientRepository clientRepository;
    private final ClientShipviaCodeMapRepository shipviaRepo;
    private final ClientServiceCodeMapRepository serviceRepo;
    private final ClientDestCountryMapRepository destRepo;
    private final ClientPackageCodeMapRepository packageRepo;
    private final ShippingServiceRepository shippingServiceRepository;
    private final PackagePresetRepository packagePresetRepository;
    /** V126 — SHIPVIA tab exposes a per-row warehouse scope. */
    private final WarehouseRepository warehouseRepository;
    /** V127 — SHIPVIA tab exposes a per-row packaging allowlist via sidecar. */
    private final ClientShipviaCodeMapPackageRepository shipviaPackageRepository;
    /**
     * Sprint 50 Tier 0.5 PR E - Pattern B on every path-param clientCode
     * so a scoped USER hitting /clients/OTHER/code-maps/... gets a 403.
     */
    private final TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientCodeMapDTO>> list(String clientCode, ClientCodeMapDTO.Kind kind) {
        String code = normalize(clientCode);
        tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        List<ClientCodeMapDTO> rows = switch (kind) {
            case SHIPVIA -> shipviaRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(code).stream()
                    .map(this::toShipviaDto).toList();
            case SERVICE -> serviceRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(code).stream()
                    .map(this::toServiceDto).toList();
            case DEST_COUNTRY -> destRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(code).stream()
                    .map(this::toDestDto).toList();
            case PACKAGE -> packageRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(code).stream()
                    .map(this::toPackageDto).toList();
        };
        return success("Code aliases retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientCodeMapDTO> upsert(
            String clientCode, ClientCodeMapDTO.Kind kind, UpsertClientCodeMapRequest request) {
        String code = normalize(clientCode);
        tenantScope.requireTenantMatch(code);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        if (!StringUtils.hasText(request.getErpCode())) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "erpCode is required.");
        }
        // Audit B3 (#372) — normalise erp casing so sequential saves of
        // "p80" + "P80" don't drift (the equalsIgnoreCase match said the
        // row existed, but the stored casing came from whichever write
        // landed first). Case-sensitive audit queries now stay consistent.
        String erp = normalize(request.getErpCode());

        String destCountry = normaliseDest(request.getDestCountry());
        String destRegion = normaliseDest(request.getDestRegion());

        return switch (kind) {
            case SHIPVIA -> upsertShipvia(code, erp, request.getTargetId(), destCountry, destRegion,
                    request.getWarehouseId(), request.getAllowedPresetIds());
            case SERVICE -> upsertService(code, erp, request.getTargetId(), destCountry, destRegion);
            case DEST_COUNTRY -> upsertDest(code, erp, request.getIso2());
            case PACKAGE -> upsertPackage(code, erp, request.getTargetId(), destCountry, destRegion);
        };
    }

    /**
     * Normalise a destination field for storage. Blank -> null (fallback
     * "any destination"). Country codes are upper-cased.
     */
    private static String normaliseDest(String v) {
        if (!StringUtils.hasText(v)) return null;
        String trimmed = v.trim();
        if (trimmed.length() == 2) return trimmed.toUpperCase(Locale.ROOT);
        return trimmed;
    }

    @Override
    @Transactional
    public ApiResponse<Void> remove(String clientCode, ClientCodeMapDTO.Kind kind, Long id) {
        String code = normalize(clientCode);
        tenantScope.requireTenantMatch(code);
        // Audit B1 + B5 — pre-fix, all four branches used
        // `findById(id).filter(sameClient).ifPresent(delete)` which:
        //   1. B1: silently 200'd on cross-tenant delete (id belonged to
        //      a different client, filter dropped, delete no-op'd)
        //   2. B5: silently 200'd on unknown id
        // Now each branch returns a boolean "did we actually delete?",
        // and mismatches surface as 400 CROSS_TENANT / 404 NOT_FOUND.
        // Same class of bug as routing-rules #349.
        Outcome outcome = switch (kind) {
            case SHIPVIA -> tryDelete(shipviaRepo.findById(id).orElse(null), code,
                    r -> r.getClientCode(), shipviaRepo::delete);
            case SERVICE -> tryDelete(serviceRepo.findById(id).orElse(null), code,
                    r -> r.getClientCode(), serviceRepo::delete);
            case DEST_COUNTRY -> tryDelete(destRepo.findById(id).orElse(null), code,
                    r -> r.getClientCode(), destRepo::delete);
            case PACKAGE -> tryDelete(packageRepo.findById(id).orElse(null), code,
                    r -> r.getClientCode(), packageRepo::delete);
        };
        return switch (outcome) {
            case DELETED -> success("Code alias removed.", null);
            case NOT_FOUND -> failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR,
                    "Alias " + id + " not found for " + kind + ".");
            case CROSS_TENANT -> failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Alias " + id + " does not belong to client " + code + ".");
        };
    }

    private enum Outcome { DELETED, NOT_FOUND, CROSS_TENANT }

    /**
     * Audit B1 + B5 helper: encapsulates the three-way outcome so every
     * per-kind branch shapes the response identically.
     */
    private static <T> Outcome tryDelete(T row, String expectedClientCode,
                                          java.util.function.Function<T, String> clientCodeOf,
                                          java.util.function.Consumer<T> deleteFn) {
        if (row == null) return Outcome.NOT_FOUND;
        if (!expectedClientCode.equalsIgnoreCase(clientCodeOf.apply(row))) return Outcome.CROSS_TENANT;
        deleteFn.accept(row);
        return Outcome.DELETED;
    }

    // ===== per-kind upsert helpers =====

    private ApiResponse<ClientCodeMapDTO> upsertShipvia(
            String code, String erp, Long targetId, String destCountry, String destRegion,
            Long warehouseId, List<Long> allowedPresetIds) {
        if (targetId == null || shippingServiceRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing shipping service.");
        }
        if (warehouseId != null && warehouseRepository.findById(warehouseId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "warehouseId must reference an existing warehouse.");
        }
        if (allowedPresetIds != null) {
            for (Long presetId : allowedPresetIds) {
                if (presetId == null || packagePresetRepository.findById(presetId).isEmpty()) {
                    return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                            "allowedPresetIds must reference existing package presets.");
                }
            }
        }
        // Audit B2 (#371) — targeted finder replaces the prior full-list
        // fetch + stream().filter. O(1) index-eq lookup on a 500-row
        // catalog instead of pulling + materialising every alias.
        ClientShipviaCodeMap row = shipviaRepo
                .findForUpsert(code, erp, destCountry, destRegion)
                .orElseGet(() -> ClientShipviaCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setServiceId(targetId);
        row.setDestCountry(destCountry);
        row.setDestRegion(destRegion);
        row.setWarehouseId(warehouseId);
        shipviaRepo.saveAndFlush(row);  // flush so @UpdateTimestamp lands before we build the response DTO
        // V127 sidecar: replace-in-place semantics. Null in the request
        // means "don't touch" so partial-update callers can't accidentally
        // wipe the allowlist; empty list means "clear it".
        if (allowedPresetIds != null) {
            shipviaPackageRepository.deleteByMapId(row.getId());
            for (Long presetId : allowedPresetIds) {
                ClientShipviaCodeMapPackage link = new ClientShipviaCodeMapPackage();
                link.setMapId(row.getId());
                link.setPresetId(presetId);
                shipviaPackageRepository.save(link);
            }
        }
        return success("Shipvia alias saved.", toShipviaDto(row));
    }

    private ApiResponse<ClientCodeMapDTO> upsertService(
            String code, String erp, Long targetId, String destCountry, String destRegion) {
        if (targetId == null || shippingServiceRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing shipping service.");
        }
        // Audit B2 (#371) — targeted finder; see upsertShipvia above.
        ClientServiceCodeMap row = serviceRepo
                .findForUpsert(code, erp, destCountry, destRegion)
                .orElseGet(() -> ClientServiceCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setServiceId(targetId);
        row.setDestCountry(destCountry);
        row.setDestRegion(destRegion);
        serviceRepo.saveAndFlush(row);  // flush so @UpdateTimestamp lands before we build the response DTO
        return success("Service-code alias saved.", toServiceDto(row));
    }

    private ApiResponse<ClientCodeMapDTO> upsertDest(String code, String erp, String iso2) {
        if (iso2 == null || iso2.trim().length() != 2) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "iso2 must be a 2-letter country code.");
        }
        String canonical = iso2.trim().toUpperCase(Locale.ROOT);
        ClientDestCountryMap row = destRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(code, erp)
                .orElseGet(() -> ClientDestCountryMap.builder().clientCode(code).erpCode(erp).build());
        row.setIso2(canonical);
        destRepo.saveAndFlush(row);  // flush so @UpdateTimestamp lands before we build the response DTO
        return success("Destination-country alias saved.", toDestDto(row));
    }

    private ApiResponse<ClientCodeMapDTO> upsertPackage(
            String code, String erp, Long targetId, String destCountry, String destRegion) {
        if (targetId == null || packagePresetRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing package preset.");
        }
        // Audit B2 (#371) — targeted finder; see upsertShipvia above.
        ClientPackageCodeMap row = packageRepo
                .findForUpsert(code, erp, destCountry, destRegion)
                .orElseGet(() -> ClientPackageCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setPresetId(targetId);
        row.setDestCountry(destCountry);
        row.setDestRegion(destRegion);
        packageRepo.saveAndFlush(row);  // flush so @UpdateTimestamp lands before we build the response DTO
        return success("Package alias saved.", toPackageDto(row));
    }

    // ===== per-kind DTO mappers =====

    private ClientCodeMapDTO toShipviaDto(ClientShipviaCodeMap row) {
        String label = serviceLabel(row.getServiceId());
        List<Long> presetIds = row.getId() == null ? List.of()
                : shipviaPackageRepository.findByMapId(row.getId()).stream()
                        .map(ClientShipviaCodeMapPackage::getPresetId).toList();
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.SHIPVIA)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .targetId(row.getServiceId()).targetLabel(label)
                .destCountry(row.getDestCountry()).destRegion(row.getDestRegion())
                .warehouseId(row.getWarehouseId()).warehouseLabel(warehouseLabel(row.getWarehouseId()))
                .allowedPresetIds(presetIds)
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt())
                .build();
    }

    private String warehouseLabel(Long id) {
        if (id == null) return null;
        return warehouseRepository.findById(id)
                .map(w -> w.getCode() + " — " + w.getName())
                .orElse(null);
    }

    private ClientCodeMapDTO toServiceDto(ClientServiceCodeMap row) {
        String label = serviceLabel(row.getServiceId());
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.SERVICE)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .targetId(row.getServiceId()).targetLabel(label)
                .destCountry(row.getDestCountry()).destRegion(row.getDestRegion())
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt())
                .build();
    }

    private ClientCodeMapDTO toDestDto(ClientDestCountryMap row) {
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.DEST_COUNTRY)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .iso2(row.getIso2()).targetLabel(row.getIso2())
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt())
                .build();
    }

    private ClientCodeMapDTO toPackageDto(ClientPackageCodeMap row) {
        String label = packageLabel(row.getPresetId());
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.PACKAGE)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .targetId(row.getPresetId()).targetLabel(label)
                .destCountry(row.getDestCountry()).destRegion(row.getDestRegion())
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt())
                .build();
    }

    private String serviceLabel(Long id) {
        Optional<ShippingService> s = shippingServiceRepository.findById(id);
        return s.map(v -> v.getCarrier() + " · " + v.getServiceCode() + " — " + v.getName()).orElse(null);
    }

    private String packageLabel(Long id) {
        Optional<PackagePreset> p = packagePresetRepository.findById(id);
        return p.map(v -> v.getName() + " · " + v.getKind()
                + (StringUtils.hasText(v.getCarrier()) ? " · " + v.getCarrier() : ""))
                .orElse(null);
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private static <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR").code(status.value()).errorCode(errorCode.name())
                .message(message).timestamp(LocalDateTime.now()).build();
    }
}
