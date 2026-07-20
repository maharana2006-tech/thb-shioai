package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.OrderLineDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderLine;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.OrderLineRepository;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantCarrierServiceImpl implements TenantCarrierService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final CarrierConfigRepository carrierConfigRepository;

    @Override
    public ApiResponse<CarrierAccountDTO> getDefaultCarrierAccount(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return error("tenantId is required", "tenantId", "VALIDATION_ERROR");
        }

        try {
            Optional<CarrierConfig> config = carrierConfigRepository.findFirstByTenantIdAndIsDefaultTrueOrderByUpdatedAtDesc(tenantId)
                    .or(() -> carrierConfigRepository.findFirstByTenantIdOrderByUpdatedAtDesc(tenantId));

            if (config.isEmpty()) {
                log.info("No default carrier account found for tenantId={}", tenantId);
                return error("No carrier account found for tenant " + tenantId, "tenantId", "NOT_FOUND");
            }

            return success("Default carrier account retrieved successfully", toCarrierAccountDTO(config.get()));
        } catch (Exception ex) {
            log.error("Failed to load default carrier account for tenantId={}", tenantId, ex);
            return error("Unexpected error while loading default carrier account", "tenantId", "INTERNAL_ERROR");
        }
    }

    @Override
    public ApiResponse<List<CarrierAccountDTO>> getCarrierAccountsForTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return error("tenantId is required", "tenantId", "VALIDATION_ERROR");
        }

        try {
            List<CarrierAccountDTO> accounts = carrierConfigRepository.findByTenantIdOrderByIsDefaultDescUpdatedAtDesc(tenantId)
                    .stream()
                    .map(this::toCarrierAccountDTO)
                    .collect(Collectors.toList());

            return success("Tenant carrier accounts retrieved successfully", accounts);
        } catch (Exception ex) {
            log.error("Failed to load carrier accounts for tenantId={}", tenantId, ex);
            return error("Unexpected error while loading tenant carrier accounts", "tenantId", "INTERNAL_ERROR");
        }
    }

    @Override
    public ApiResponse<List<CarrierAccountDTO>> getAllTenantCarrierAccounts() {
        try {
            List<CarrierAccountDTO> accounts = carrierConfigRepository.findByTenantIdIsNotNullOrderByTenantIdAscIsDefaultDescUpdatedAtDesc()
                    .stream()
                    .map(this::toCarrierAccountDTO)
                    .collect(Collectors.toList());

            return success("All tenant carrier accounts retrieved successfully", accounts);
        } catch (Exception ex) {
            log.error("Failed to load all tenant carrier accounts", ex);
            return error("Unexpected error while loading tenant carrier accounts", null, "INTERNAL_ERROR");
        }
    }

    @Override
    public ApiResponse<List<OrderLineDTO>> getOrderLines(Integer orderNo) {
        if (orderNo == null) {
            return error("orderNo is required", "orderNo", "VALIDATION_ERROR");
        }

        try {
            List<OrderLineDTO> lines = orderLineRepository.findOrderLinesByOrderNo(orderNo)
                    .stream()
                    .map(this::toOrderLineDTO)
                    .collect(Collectors.toList());

            if (lines.isEmpty()) {
                log.info("No order lines found for orderNo={}", orderNo);
                return error("No order lines found for order " + orderNo, "orderNo", "NOT_FOUND");
            }

            return success("Order lines retrieved successfully", lines);
        } catch (Exception ex) {
            log.error("Failed to load order lines for orderNo={}", orderNo, ex);
            return error("Unexpected error while loading order lines", "orderNo", "INTERNAL_ERROR");
        }
    }

    @Override
    public ApiResponse<OrderWithLinesDTO> getOrderWithLines(Integer orderNo) {
        if (orderNo == null) {
            return error("orderNo is required", "orderNo", "VALIDATION_ERROR");
        }

        try {
            Optional<Order> order = orderRepository.findOrderWithLines(orderNo);
            if (order.isEmpty()) {
                log.info("Order not found for orderNo={}", orderNo);
                return error("Order not found: " + orderNo, "orderNo", "NOT_FOUND");
            }

            return success("Order with lines retrieved successfully", toOrderWithLinesDTO(order.get()));
        } catch (Exception ex) {
            log.error("Failed to load order with lines for orderNo={}", orderNo, ex);
            return error("Unexpected error while loading order with lines", "orderNo", "INTERNAL_ERROR");
        }
    }

    private CarrierAccountDTO toCarrierAccountDTO(CarrierConfig config) {
        return CarrierAccountDTO.builder()
                .id(config.getId())
                .tenantId(config.getTenantId())
                .carrierCode(config.getCarrierCode())
                .carrierName(config.getCarrierName())
                .accountNumber(config.getAccountNumber())
                .accountCode(config.getAccountCode())
                .isDefault(Boolean.TRUE.equals(config.getIsDefault()))
                .active(config.getActive())
                .environment(config.getEnvironment())
                .shipViaCd(config.getShipVia() != null ? config.getShipVia().getShipviaCd() : null)
                .shipViaDescription(config.getShipVia() != null ? config.getShipVia().getShipviaDesc() : null)
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private OrderLineDTO toOrderLineDTO(OrderLine line) {
        return OrderLineDTO.builder()
                .id(line.getId())
                .lineNo(line.getLineNo())
                .itemNo(line.getItemNo())
                .itemDescription(line.getItemDescription())
                .qtyShipped(line.getQtyShipped())
                .hsCode(line.getHsCode())
                .hsDesc(line.getHsDesc())
                .description(line.getDescription())
                .countryOfOrigin(line.getCountryOfOrigin())
                .customsDeclValue(line.getCustomsDeclValue())
                .unitPrice(line.getUnitPrice())
                .totalPrice(line.getTotalPrice())
                .build();
    }

    private OrderWithLinesDTO toOrderWithLinesDTO(Order order) {
        List<OrderLineDTO> lines = order.getOrderLines() == null
                ? Collections.emptyList()
                : order.getOrderLines().stream()
                .map(this::toOrderLineDTO)
                .collect(Collectors.toList());

        return OrderWithLinesDTO.builder()
                .orderNo(order.getOrderNo())
                .orderSuffix(order.getOrderSuffix())
                .orderStatus(order.getOrderStatus())
                .custNo(order.getCustNo())
                .shiptoCity(order.getShiptoCity())
                .shiptoState(order.getShiptoState())
                .shiptoZip(order.getShiptoZip())
                .shipviaCd(order.getShipviaCd())
                .tenantId(order.getTenantId())
                .weight(order.getWeight())
                .goodsDesc(order.getGoodsDesc())
                .createdDate(order.getCreatedDate())
                .orderLines(lines)
                .build();
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(200)
                .message(message)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    private <T> ApiResponse<T> error(String message, String field, String code) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .code(400)
                .errorCode(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .errors(ApiResponse.ErrorDetails.builder()
                        .field(field)
                        .code(code)
                        .message(message)
                        .build())
                .build();
    }
}
