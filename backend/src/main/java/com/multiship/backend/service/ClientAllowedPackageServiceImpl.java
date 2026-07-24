package com.multiship.backend.service;

import com.multiship.backend.dto.AllowPackageRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedPackageDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientAllowedPackage;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientAllowedPackageServiceImpl implements ClientAllowedPackageService {

    private final ClientAllowedPackageRepository repo;
    private final ClientRepository clientRepository;
    private final PackagePresetRepository packagePresetRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientAllowedPackageDTO>> listForClient(String clientCode) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        List<ClientAllowedPackageDTO> rows = repo
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                .stream().map(this::toDTO).toList();
        return success("Allowed packages retrieved successfully.", rows);
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedPackageDTO> allow(String clientCode, AllowPackageRequest request) {
        String code = normalize(clientCode);
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }
        PackagePreset preset = packagePresetRepository.findById(request.getPresetId()).orElse(null);
        if (preset == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR,
                    "Package preset " + request.getPresetId() + " was not found.");
        }
        if (repo.existsByClientCodeIgnoreCaseAndPresetId(code, preset.getId())) {
            return failure(HttpStatus.CONFLICT, ErrorCode.ALLOWLIST_ALREADY_EXISTS,
                    "Package " + preset.getName() + " is already allowed for client " + code + ".");
        }

        boolean makeDefault = Boolean.TRUE.equals(request.getMakeDefault());
        boolean isFirst = repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code).isEmpty();
        boolean shouldBeDefault = makeDefault || isFirst;

        if (shouldBeDefault) {
            clearExistingDefault(code);
        }

        ClientAllowedPackage link = ClientAllowedPackage.builder()
                .clientCode(code)
                .presetId(preset.getId())
                .isDefault(shouldBeDefault)
                .build();
        repo.save(link);

        return success("Package " + preset.getName() + " allowed for client " + code
                + (shouldBeDefault ? " as the default." : "."), toDTO(link));
    }

    @Override
    @Transactional
    public ApiResponse<Void> remove(String clientCode, Long presetId) {
        String code = normalize(clientCode);
        ClientAllowedPackage link = repo.findByClientCodeIgnoreCaseAndPresetId(code, presetId).orElse(null);
        if (link == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Package " + presetId + " is not allowed for client " + code + ".");
        }
        boolean wasDefault = Boolean.TRUE.equals(link.getIsDefault());
        repo.delete(link);

        if (wasDefault) {
            repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code)
                    .stream().findFirst().ifPresent(next -> {
                        next.setIsDefault(true);
                        repo.save(next);
                    });
        }
        return success("Package " + presetId + " removed from client " + code + ".", null);
    }

    @Override
    @Transactional
    public ApiResponse<ClientAllowedPackageDTO> setDefault(String clientCode, Long presetId) {
        String code = normalize(clientCode);
        ClientAllowedPackage target = repo.findByClientCodeIgnoreCaseAndPresetId(code, presetId).orElse(null);
        if (target == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND,
                    "Package " + presetId + " is not allowed for client " + code + ".");
        }
        clearExistingDefault(code);
        target.setIsDefault(true);
        repo.save(target);
        return success("Package " + presetId + " is now the default for client " + code + ".", toDTO(target));
    }

    // ===== helpers =====

    private void clearExistingDefault(String clientCode) {
        repo.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode).ifPresent(existing -> {
            existing.setIsDefault(false);
            repo.save(existing);
        });
    }

    private ClientAllowedPackageDTO toDTO(ClientAllowedPackage link) {
        PackagePreset p = packagePresetRepository.findById(link.getPresetId()).orElse(null);
        return ClientAllowedPackageDTO.builder()
                .id(link.getId())
                .clientCode(link.getClientCode())
                .presetId(link.getPresetId())
                .name(p == null ? null : p.getName())
                .kind(p == null ? null : p.getKind())
                .carrier(p == null ? null : p.getCarrier())
                .carrierPackageCode(p == null ? null : p.getCarrierPackageCode())
                .originCountry(p == null ? null : p.getOriginCountry())
                .scope(p == null ? null : p.getScope())
                .length(p == null ? null : p.getLength())
                .width(p == null ? null : p.getWidth())
                .height(p == null ? null : p.getHeight())
                .dimUnit(p == null ? null : p.getDimUnit())
                .maxWeight(p == null ? null : p.getMaxWeight())
                .weightUnit(p == null ? null : p.getWeightUnit())
                .isDefault(link.getIsDefault())
                .createdAt(link.getCreatedAt())
                .updatedAt(link.getUpdatedAt())
                .build();
    }

    private String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR").code(status.value()).errorCode(errorCode.name())
                .message(message).timestamp(LocalDateTime.now()).build();
    }
}
