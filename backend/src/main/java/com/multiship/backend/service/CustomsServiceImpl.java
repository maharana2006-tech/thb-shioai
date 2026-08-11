package com.multiship.backend.service;

import com.multiship.backend.dto.AddressDTO;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OrderCustomsDTO;
import com.multiship.backend.dto.OrderCustomsItemDTO;
import com.multiship.backend.dto.OrderCustomsUpsertRequest;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderCustomsItem;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CustomsServiceImpl implements CustomsService {

    private final OrderCustomsRepository customsRepository;
    private final OrderRepository orderRepository;
    /**
     * Sprint 50 Tier 0.5 PR H - tenant guard so a scoped USER cannot read
     * or upsert the customs declaration for a foreign tenant's order. The
     * tenant discriminator (tenant_id / cust_no) lives on the order, not
     * on the customs row. Optional so pre-PR-H tests still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<OrderCustomsDTO> getCustoms(String orderNo) {
        ApiResponse<OrderCustomsDTO> orderCheck = requireOrder(orderNo);
        if (orderCheck != null) {
            return orderCheck;
        }
        return customsRepository.findByOrderNoIgnoreCase(orderNo.trim())
                .map(c -> success("Customs declaration retrieved.", toDTO(c)))
                // No declaration yet is a normal state — return an empty success.
                .orElseGet(() -> success("No customs declaration for this order yet.", null));
    }

    @Override
    @Transactional
    public ApiResponse<OrderCustomsDTO> upsertCustoms(String orderNo, OrderCustomsUpsertRequest request) {
        ApiResponse<OrderCustomsDTO> orderCheck = requireOrder(orderNo);
        if (orderCheck != null) {
            return orderCheck;
        }

        String code = orderNo.trim();
        OrderCustoms customs = customsRepository.findByOrderNoIgnoreCase(code)
                .orElseGet(() -> OrderCustoms.builder().orderNo(code).items(new ArrayList<>()).build());

        customs.setImporterAddress(toAddress(request.getImporter()));
        customs.setImporterCompany(trimOrNull(request.getImporterCompany()));
        customs.setImporterTaxId(trimOrNull(request.getImporterTaxId()));
        customs.setImporterVat(trimOrNull(request.getImporterVat()));
        customs.setImporterEori(trimOrNull(request.getImporterEori()));
        customs.setIncoterms(upperOrNull(request.getIncoterms()));
        customs.setReasonForExport(upperOrNull(request.getReasonForExport()));
        customs.setCurrency(upperOrNull(request.getCurrency()));
        customs.setWeightUnit(upperOrNull(request.getWeightUnit()));
        customs.setNotes(trimOrNull(request.getNotes()));

        // Replace the item set wholesale (orphanRemoval clears the old rows).
        customs.getItems().clear();
        if (request.getItems() != null) {
            for (OrderCustomsItemDTO dto : request.getItems()) {
                if (dto == null || !StringUtils.hasText(dto.getDescription())) {
                    continue;
                }
                OrderCustomsItem item = OrderCustomsItem.builder()
                        .orderCustoms(customs)
                        .description(dto.getDescription().trim())
                        .hsCode(trimOrNull(dto.getHsCode()))
                        .countryOfOrigin(upperOrNull(dto.getCountryOfOrigin()))
                        .quantity(dto.getQuantity() != null ? dto.getQuantity() : 1)
                        .unitValue(dto.getUnitValue())
                        .weight(dto.getWeight())
                        .sku(trimOrNull(dto.getSku()))
                        .boxSeq(dto.getBoxSeq())  // Sprint 48 B11
                        .build();
                customs.getItems().add(item);
            }
        }

        customsRepository.save(customs);
        return success("Customs declaration for order " + code + " saved.", toDTO(customs));
    }

    // ===== helpers =====

    /** Returns a failure response when the order does not exist, else null. */
    private ApiResponse<OrderCustomsDTO> requireOrder(String orderNo) {
        Integer parsed = parseOrderNo(orderNo);
        if (parsed == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND,
                    "Order " + orderNo + " was not found.");
        }
        var loaded = orderRepository.findByOrderNo(parsed);
        if (loaded.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND,
                    "Order " + orderNo + " was not found.");
        }
        // Sprint 50 Tier 0.5 PR H - belt-and-braces tenant guard: reject
        // a scoped USER trying to touch a foreign tenant's order customs.
        // Mirrors VoidServiceImpl:62. Prefer tenantId, fall back to custNo.
        if (tenantScope != null) {
            com.multiship.backend.model.Order order = loaded.get();
            tenantScope.requireTenantMatch(
                    StringUtils.hasText(order.getTenantId()) ? order.getTenantId() : order.getCustNo());
        }
        return null;
    }

    private Integer parseOrderNo(String orderNo) {
        try {
            return Integer.valueOf(orderNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Address toAddress(AddressDTO dto) {
        if (dto == null) {
            return null;
        }
        return Address.builder()
                .name(trimOrNull(dto.getName()))
                .line1(trimOrNull(dto.getLine1()))
                .line2(trimOrNull(dto.getLine2()))
                .city(trimOrNull(dto.getCity()))
                .state(trimOrNull(dto.getState()))
                .zip(trimOrNull(dto.getZip()))
                .country(upperOrNull(dto.getCountry()))
                .phone(trimOrNull(dto.getPhone()))
                .build();
    }

    private AddressDTO toAddressDTO(Address a) {
        if (a == null) {
            return null;
        }
        return AddressDTO.builder()
                .name(a.getName()).line1(a.getLine1()).line2(a.getLine2())
                .city(a.getCity()).state(a.getState()).zip(a.getZip())
                .country(a.getCountry()).phone(a.getPhone())
                .build();
    }

    private OrderCustomsDTO toDTO(OrderCustoms c) {
        List<OrderCustomsItemDTO> items = c.getItems().stream()
                .map(i -> OrderCustomsItemDTO.builder()
                        .id(i.getId())
                        .description(i.getDescription())
                        .hsCode(i.getHsCode())
                        .countryOfOrigin(i.getCountryOfOrigin())
                        .quantity(i.getQuantity())
                        .unitValue(i.getUnitValue())
                        .weight(i.getWeight())
                        .sku(i.getSku())
                        .boxSeq(i.getBoxSeq())  // Sprint 48 B11
                        .build())
                .toList();

        return OrderCustomsDTO.builder()
                .id(c.getId())
                .orderNo(c.getOrderNo())
                .importer(toAddressDTO(c.getImporterAddress()))
                .importerCompany(c.getImporterCompany())
                .importerTaxId(c.getImporterTaxId())
                .importerVat(c.getImporterVat())
                .importerEori(c.getImporterEori())
                .incoterms(c.getIncoterms())
                .reasonForExport(c.getReasonForExport())
                .currency(c.getCurrency())
                .weightUnit(c.getWeightUnit())
                .notes(c.getNotes())
                .items(items)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String upperOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
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
