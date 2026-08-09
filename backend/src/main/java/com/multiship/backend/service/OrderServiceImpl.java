package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.OrderListResponseDTO;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithCarrierDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.PaginationRequestDTO;
import com.multiship.backend.dto.OrderLineDTO;
import com.multiship.backend.dto.OrderListFilters;
import com.multiship.backend.model.CarrierConfig;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderLine;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.CarrierConfigRepository;
import com.multiship.backend.repository.OrderRawCodesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CarrierConfigRepository carrierConfigRepository;

    @Autowired
    private CarrierService carrierService;

    @Autowired(required = false)
    private CustomFieldService customFieldService;

    @Autowired
    private OrderRawCodesRepository orderRawCodesRepository;

    @Autowired
    private com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "GENERATED", "ERROR");
    private static final Set<String> VALID_RESOLUTIONS = Set.of("READY", "NEEDS_DETAILS", "CHOOSE_ACCOUNT", "CLIENT_MISSING");

    // ===== UNIFIED LIST =====

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PageResponseDTO<OrderResponseDTO>> listOrders(
            OrderListFilters filters,
            boolean includeResolution,
            PaginationRequestDTO paginationRequest) {

        // Normalize filters; '' means "not set" (the SQL sentinel).
        String status = filters.getStatus();
        String resolution = filters.getResolution();
        String statusFilter = normalizeFilter(status);
        String resolutionFilter = normalizeFilter(resolution);
        String tenantFilter = normalizeFilter(filters.getTenantId());
        String keywordFilter = trimmed(filters.getSearch());
        String customerFilter = trimmed(filters.getCustomer());
        String cityFilter = trimmed(filters.getCity());
        String orderNoFilter = trimmed(filters.getOrderNo());
        String trackingFilter = trimmed(filters.getTracking());
        String createdFrom = trimmed(filters.getCreatedFrom());
        String createdTo = trimmed(filters.getCreatedTo());

        if (!isValidDateFilter(createdFrom) || !isValidDateFilter(createdTo)) {
            return ApiResponse.<PageResponseDTO<OrderResponseDTO>>builder()
                    .status("ERROR")
                    .code(400)
                    .errorCode(ErrorCode.VALIDATION_ERROR.name())
                    .message("Date filters must use the yyyy-MM-dd format.")
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        if (!statusFilter.isEmpty() && !VALID_STATUSES.contains(statusFilter)) {
            return ApiResponse.<PageResponseDTO<OrderResponseDTO>>builder()
                    .status("ERROR")
                    .code(400)
                    .errorCode(ErrorCode.VALIDATION_ERROR.name())
                    .message("Unknown status filter '" + status + "'. Valid: PENDING, GENERATED, ERROR.")
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        if (!resolutionFilter.isEmpty() && !VALID_RESOLUTIONS.contains(resolutionFilter)) {
            return ApiResponse.<PageResponseDTO<OrderResponseDTO>>builder()
                    .status("ERROR")
                    .code(400)
                    .errorCode(ErrorCode.VALIDATION_ERROR.name())
                    .message("Unknown resolution filter '" + resolution + "'. Valid: READY, NEEDS_DETAILS, CHOOSE_ACCOUNT, CLIENT_MISSING.")
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        int page = Math.max(paginationRequest.getPage(), 0);
        int size = Math.min(Math.max(paginationRequest.getSize(), 1), 100);
        String sortBy = paginationRequest.getSortBy() != null ? paginationRequest.getSortBy() : "orderNo";
        String sortDirection = paginationRequest.getSortDirection() != null ? paginationRequest.getSortDirection() : "ASC";

        List<Object[]> results = orderRepository.findOrdersUnified(
                statusFilter, tenantFilter, keywordFilter, resolutionFilter,
                customerFilter, cityFilter, orderNoFilter, trackingFilter,
                createdFrom, createdTo,
                page * size, size, sortBy, sortDirection);
        long totalRecords = orderRepository.countOrdersUnified(
                statusFilter, tenantFilter, keywordFilter, resolutionFilter,
                customerFilter, cityFilter, orderNoFilter, trackingFilter,
                createdFrom, createdTo);

        List<OrderResponseDTO> orders = results.stream()
                .map(this::mapToOrderResponseDTO)
                .collect(Collectors.toList());

        attachRefOrderNumbers(orders);

        if (includeResolution && !orders.isEmpty()) {
            attachResolutions(orders);
        }

        PageResponseDTO<OrderResponseDTO> pageResponse =
                PageResponseDTO.of(orders, page, size, totalRecords, sortBy, sortDirection);

        return ApiResponse.<PageResponseDTO<OrderResponseDTO>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Orders retrieved successfully")
                .timestamp(LocalDateTime.now())
                .data(pageResponse)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Long>> getQueueStats() {
        Object[] row = orderRepository.getQueueStats().get(0);

        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("ready", toLong(row[0]));
        stats.put("needsDetails", toLong(row[1]));
        stats.put("chooseAccount", toLong(row[2]));
        stats.put("clientMissing", toLong(row[3]));
        stats.put("failed", toLong(row[4]));
        stats.put("generated", toLong(row[5]));

        return ApiResponse.<Map<String, Long>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Queue statistics computed successfully")
                .timestamp(LocalDateTime.now())
                .data(stats)
                .build();
    }

    /** Stamps each order with the account the generation cascade would pick. */
    private void attachResolutions(List<OrderResponseDTO> orders) {
        List<Integer> orderNos = orders.stream()
                .map(order -> order.getOrderDetails().getOrderNo())
                .collect(Collectors.toList());

        ApiResponse<List<OrderAccountResolutionDTO>> resolutionResponse =
                carrierService.resolveOrderAccounts(orderNos);

        if (resolutionResponse.getData() == null) {
            return;
        }

        Map<Integer, OrderAccountResolutionDTO> byOrderNo = resolutionResponse.getData().stream()
                .collect(Collectors.toMap(OrderAccountResolutionDTO::getOrderNo, r -> r, (a, b) -> a));

        orders.forEach(order ->
                order.setAccountResolution(byOrderNo.get(order.getOrderDetails().getOrderNo())));
    }

    /** Stamps each order with the WMS's own order number, from the raw-codes audit sidecar. */
    private void attachRefOrderNumbers(List<OrderResponseDTO> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<Integer> orderNos = orders.stream()
                .map(order -> order.getOrderDetails().getOrderNo())
                .collect(Collectors.toList());

        Map<Integer, String> byOrderNo = orderRawCodesRepository.findAllById(orderNos).stream()
                .filter(raw -> raw.getRefOrderNumber() != null)
                .collect(Collectors.toMap(
                        com.multiship.backend.model.OrderRawCodes::getOrderNo,
                        com.multiship.backend.model.OrderRawCodes::getRefOrderNumber,
                        (a, b) -> a));

        orders.forEach(order ->
                order.getOrderDetails().setRefOrderNumber(byOrderNo.get(order.getOrderDetails().getOrderNo())));
    }

    private boolean isValidDateFilter(String value) {
        return value.isEmpty() || value.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private String trimmed(String value) {
        return value != null ? value.trim() : "";
    }

    private String normalizeFilter(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ===== EXISTING METHODS =====

    @Override
    public ApiResponse<OrderResponseDTO> getOrderWithTracking(Integer orderNo) {
        List<Object[]> results = orderRepository.findOrderWithTracking(orderNo);

        if (results.isEmpty()) {
            return ApiResponse.<OrderResponseDTO>builder()
                    .status("ERROR")
                    .code(404)
                    .errorCode(ErrorCode.ORDER_NOT_FOUND.name())
                    .message("Order not found")
                    .timestamp(LocalDateTime.now())
                    .data(null)
                    .errors(ApiResponse.ErrorDetails.builder()
                            .field("orderNo")
                            .code("NOT_FOUND")
                            .message("Order with number " + orderNo + " does not exist")
                            .build())
                    .build();
        }

        OrderResponseDTO order = mapToOrderResponseDTO(results.get(0));
        attachRefOrderNumbers(List.of(order));

        // Sprint 43 — hydrate custom-field values on single-order reads.
        if (customFieldService != null) {
            try {
                order.setCustomFields(customFieldService.loadValues(orderNo));
            } catch (Exception ignored) {
                // Best-effort — custom fields never block the order read.
            }
        }

        return ApiResponse.<OrderResponseDTO>builder()
                .status("SUCCESS")
                .code(200)
                .message("Order retrieved successfully")
                .timestamp(LocalDateTime.now())
                .data(order)
                .build();
    }

    @Override
    public ApiResponse<OrderWithLinesDTO> getOrderWithLines(Integer orderNo) {
        Optional<Order> order = orderRepository.findOrderWithLines(orderNo);

        if (order.isEmpty()) {
            return ApiResponse.<OrderWithLinesDTO>builder()
                    .status("ERROR")
                    .code(404)
                    .errorCode(ErrorCode.ORDER_NOT_FOUND.name())
                    .message("Order not found")
                    .timestamp(LocalDateTime.now())
                    .data(null)
                    .errors(ApiResponse.ErrorDetails.builder()
                            .field("orderNo")
                            .code("NOT_FOUND")
                            .message("Order with number " + orderNo + " does not exist")
                            .build())
                    .build();
        }

        Order entity = order.get();
        List<OrderLineDTO> lines = entity.getOrderLines() == null
                ? Collections.emptyList()
                : entity.getOrderLines().stream()
                .sorted(Comparator.comparing(OrderLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .map(this::mapToOrderLineDTO)
                .collect(Collectors.toList());

        OrderWithLinesDTO data = OrderWithLinesDTO.builder()
                .orderNo(entity.getOrderNo())
                .orderSuffix(entity.getOrderSuffix())
                .displayOrderNo(com.multiship.backend.util.OrderNumberFormatter.format(
                        entity.getOrderNo(), entity.getIsManual()))
                .orderStatus(entity.getOrderStatus())
                .custNo(entity.getCustNo())
                .shipName(entity.getShipName())
                .shipAttn(entity.getShipAttn())
                .shipAddr1(entity.getShipAddr1())
                .phone(entity.getPhone())
                .shiptoCity(entity.getShiptoCity())
                .shiptoState(entity.getShiptoState())
                .shiptoZip(entity.getShiptoZip())
                .shiptoCountryCd(entity.getShiptoCountryCd())
                .shipviaCd(entity.getShipviaCd())
                .tenantId(resolveTenantKey(entity))
                .weight(entity.getWeight())
                .goodsDesc(entity.getGoodsDesc())
                .createdDate(entity.getCreatedDate())
                .isReturn(entity.getIsReturn())
                .importerBrokerOverride(entity.getImporterBrokerOverride())
                .packageCount(entity.getPackageCount())
                .packages(labelPackageRepository
                        .findByOrderNoOrderBySequenceNumberAsc(entity.getOrderNo()).stream()
                        .map(p -> com.multiship.backend.dto.LabelPackageDTO.builder()
                                .sequenceNumber(p.getSequenceNumber())
                                .trackingNumber(p.getTrackingNumber())
                                .trackingUrl(p.getTrackingUrl())
                                .labelFilePath(p.getLabelFilePath())
                                .weight(p.getWeight())
                                .weightUnit(p.getWeightUnit())
                                .length(p.getLength())
                                .width(p.getWidth())
                                .height(p.getHeight())
                                .dimUnit(p.getDimUnit())
                                .packageType(p.getPackageType())
                                .declaredValue(p.getDeclaredValue())
                                .reference(p.getReference())
                                .description(p.getDescription())
                                .build())
                        .toList())
                .orderLines(lines)
                .build();

        return ApiResponse.<OrderWithLinesDTO>builder()
                .status("SUCCESS")
                .code(200)
                .message("Order with line items retrieved successfully")
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    @Override
    public ApiResponse<Map<String, Object>> getDashboardStats() {
        List<Object[]> results = orderRepository.getDashboardStats();

        if (results.isEmpty()) {
            return ApiResponse.<Map<String, Object>>builder()
                    .status("ERROR")
                    .code(404)
                    .errorCode(ErrorCode.ORDER_NOT_FOUND.name())
                    .message("No statistics available")
                    .timestamp(LocalDateTime.now())
                    .data(null)
                    .build();
        }

        Object[] row = results.get(0);

        List<Object[]> cityData = orderRepository.getCityDistribution();
        List<Map<String, Object>> cityDistribution = cityData.stream()
                .map(cityRow -> {
                    Map<String, Object> cityMap = new LinkedHashMap<>();
                    cityMap.put("city", cityRow[0]);
                    cityMap.put("count", cityRow[1]);
                    return cityMap;
                })
                .collect(Collectors.toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("orderStats", Map.of(
                "totalOrders", row[0] != null ? ((Number) row[0]).longValue() : 0L,
                "pendingOrders", row[1] != null ? ((Number) row[1]).longValue() : 0L,
                "generatedOrders", row[2] != null ? ((Number) row[2]).longValue() : 0L,
                "failedOrders", row[3] != null ? ((Number) row[3]).longValue() : 0L
        ));

        stats.put("weightStats", Map.of(
                "totalWeight", row[5] != null ? ((Number) row[5]).doubleValue() : 0.0,
                "averageWeight", calculateAverageWeight()
        ));

        stats.put("cityDistribution", cityDistribution);

        long totalOrders = row[0] != null ? ((Number) row[0]).longValue() : 0L;
        long generatedLabels = row[4] != null ? ((Number) row[4]).longValue() : 0L;

        stats.put("statusSummary", Map.of(
                "labelGenerated", generatedLabels,
                "pendingLabels", row[1] != null ? ((Number) row[1]).longValue() : 0L,
                "errorLabels", row[3] != null ? ((Number) row[3]).longValue() : 0L,
                "completionRate", totalOrders > 0
                        ? String.format("%.1f%%", (generatedLabels * 100.0) / totalOrders)
                        : "0%"
        ));

        return ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Dashboard statistics retrieved successfully")
                .timestamp(LocalDateTime.now())
                .data(stats)
                .build();
    }

    // ===== NEW PAGINATED & SORTED METHODS =====

    private OrderResponseDTO mapToOrderResponseDTO(Object[] row) {
        return OrderResponseDTO.builder()
                .orderDetails(OrderResponseDTO.OrderDetails.builder()
                        .orderNo((Integer) row[0])
                        .orderSuffix((Integer) row[1])
                        .status((String) row[2])
                        .customerCode((String) row[3])
                        .goodsDescription((String) row[9])
                        .createdDate(row[10] != null ? (LocalDate) row[10] : null)
                        .source((String) row[19])
                        .batchId((Integer) row[20])
                        .build())
                .shippingDetails(OrderResponseDTO.ShippingDetails.builder()
                        .city((String) row[4])
                        .state((String) row[5])
                        .zipCode((String) row[6])
                        .shipVia((String) row[7])
                        .weight(row[8] != null ? (BigDecimal) row[8] : BigDecimal.ZERO)
                        .shipViaDescription((String) row[18])
                        .build())
                .labelDetails(OrderResponseDTO.LabelDetails.builder()
                        .status(row[11] != null ? (String) row[11] : "PENDING")
                        .isGenerated(row[12] != null ? (Boolean) row[12] : false)
                        .trackingNumber((String) row[13])
                        .trackingUrl((String) row[14])
                        .labelFilePath((String) row[15])
                        .generatedAt((LocalDateTime) row[17])
                        .build())
                .errorDetails(OrderResponseDTO.ErrorDetails.builder()
                        .hasError(row[16] != null && row[16] != "")
                        .errorMessage((String) row[16])
                        .build())
                .build();
    }

    private OrderLineDTO mapToOrderLineDTO(OrderLine line) {
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

    private String resolveTenantId(Object[] row) {
        return resolveTenantKey(row);
    }

    private String resolveTenantKey(Order order) {
        if (order == null) {
            return null;
        }
        if (StringUtils.hasText(order.getTenantId())) {
            return order.getTenantId();
        }
        if (StringUtils.hasText(order.getCustNo())) {
            return order.getCustNo();
        }
        return null;
    }

    private String resolveTenantKey(Object[] row) {
        Object tenantValue = row[3];
        if (tenantValue instanceof String tenantId && !tenantId.isBlank()) {
            return tenantId;
        }
        return null;
    }

    private CarrierAccountDTO resolveTenantCarrierAccount(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }

        CarrierConfig config = carrierConfigRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .or(() -> carrierConfigRepository.findFirstByTenantIdOrderByUpdatedAtDesc(tenantId))
                .orElse(null);

        if (config == null) {
            return null;
        }

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

    private OrderListResponseDTO.Summary buildSummary(List<OrderResponseDTO> orders) {
        if (orders.isEmpty()) {
            return OrderListResponseDTO.Summary.builder()
                    .totalWeight(BigDecimal.ZERO)
                    .averageWeight(BigDecimal.ZERO)
                    .pendingLabels(0L)
                    .generatedLabels(0L)
                    .failedLabels(0L)
                    .cities(Collections.emptyList())
                    .statusCounts(Collections.emptyMap())
                    .build();
        }

        BigDecimal totalWeight = orders.stream()
                .map(o -> o.getShippingDetails().getWeight())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageWeight = totalWeight.divide(
                new BigDecimal(orders.size()),
                2,
                RoundingMode.HALF_UP
        );

        Map<String, Long> statusCounts = orders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.getLabelDetails().getStatus(),
                        Collectors.counting()
                ));

        List<String> cities = orders.stream()
                .map(o -> o.getShippingDetails().getCity())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return OrderListResponseDTO.Summary.builder()
                .totalWeight(totalWeight)
                .averageWeight(averageWeight)
                .pendingLabels(statusCounts.getOrDefault("PENDING", 0L))
                .generatedLabels(statusCounts.getOrDefault("GENERATED", 0L))
                .failedLabels(statusCounts.getOrDefault("ERROR", 0L))
                .cities(cities)
                .statusCounts(statusCounts)
                .build();
    }

    private double calculateAverageWeight() {
        List<Object[]> results = orderRepository.getAverageWeight();
        if (results.isEmpty() || results.get(0)[0] == null) {
            return 0.0;
        }
        return ((Number) results.get(0)[0]).doubleValue();
    }
}
