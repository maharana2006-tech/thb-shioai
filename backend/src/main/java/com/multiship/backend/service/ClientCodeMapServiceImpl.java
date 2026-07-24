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
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
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

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientCodeMapDTO>> list(String clientCode, ClientCodeMapDTO.Kind kind) {
        String code = normalize(clientCode);
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
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        if (!StringUtils.hasText(request.getErpCode())) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "erpCode is required.");
        }
        String erp = request.getErpCode().trim();

        return switch (kind) {
            case SHIPVIA -> upsertShipvia(code, erp, request.getTargetId());
            case SERVICE -> upsertService(code, erp, request.getTargetId());
            case DEST_COUNTRY -> upsertDest(code, erp, request.getIso2());
            case PACKAGE -> upsertPackage(code, erp, request.getTargetId());
        };
    }

    @Override
    @Transactional
    public ApiResponse<Void> remove(String clientCode, ClientCodeMapDTO.Kind kind, Long id) {
        String code = normalize(clientCode);
        switch (kind) {
            case SHIPVIA -> shipviaRepo.findById(id)
                    .filter(r -> code.equalsIgnoreCase(r.getClientCode()))
                    .ifPresent(shipviaRepo::delete);
            case SERVICE -> serviceRepo.findById(id)
                    .filter(r -> code.equalsIgnoreCase(r.getClientCode()))
                    .ifPresent(serviceRepo::delete);
            case DEST_COUNTRY -> destRepo.findById(id)
                    .filter(r -> code.equalsIgnoreCase(r.getClientCode()))
                    .ifPresent(destRepo::delete);
            case PACKAGE -> packageRepo.findById(id)
                    .filter(r -> code.equalsIgnoreCase(r.getClientCode()))
                    .ifPresent(packageRepo::delete);
        }
        return success("Code alias removed.", null);
    }

    // ===== per-kind upsert helpers =====

    private ApiResponse<ClientCodeMapDTO> upsertShipvia(String code, String erp, Long targetId) {
        if (targetId == null || shippingServiceRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing shipping service.");
        }
        ClientShipviaCodeMap row = shipviaRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(code, erp)
                .orElseGet(() -> ClientShipviaCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setServiceId(targetId);
        shipviaRepo.save(row);
        return success("Shipvia alias saved.", toShipviaDto(row));
    }

    private ApiResponse<ClientCodeMapDTO> upsertService(String code, String erp, Long targetId) {
        if (targetId == null || shippingServiceRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing shipping service.");
        }
        ClientServiceCodeMap row = serviceRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(code, erp)
                .orElseGet(() -> ClientServiceCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setServiceId(targetId);
        serviceRepo.save(row);
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
        destRepo.save(row);
        return success("Destination-country alias saved.", toDestDto(row));
    }

    private ApiResponse<ClientCodeMapDTO> upsertPackage(String code, String erp, Long targetId) {
        if (targetId == null || packagePresetRepository.findById(targetId).isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "targetId must reference an existing package preset.");
        }
        ClientPackageCodeMap row = packageRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(code, erp)
                .orElseGet(() -> ClientPackageCodeMap.builder().clientCode(code).erpCode(erp).build());
        row.setPresetId(targetId);
        packageRepo.save(row);
        return success("Package alias saved.", toPackageDto(row));
    }

    // ===== per-kind DTO mappers =====

    private ClientCodeMapDTO toShipviaDto(ClientShipviaCodeMap row) {
        String label = serviceLabel(row.getServiceId());
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.SHIPVIA)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .targetId(row.getServiceId()).targetLabel(label)
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt())
                .build();
    }

    private ClientCodeMapDTO toServiceDto(ClientServiceCodeMap row) {
        String label = serviceLabel(row.getServiceId());
        return ClientCodeMapDTO.builder()
                .id(row.getId()).kind(ClientCodeMapDTO.Kind.SERVICE)
                .clientCode(row.getClientCode()).erpCode(row.getErpCode())
                .targetId(row.getServiceId()).targetLabel(label)
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
