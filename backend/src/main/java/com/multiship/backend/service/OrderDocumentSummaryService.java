package com.multiship.backend.service;

import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderCustoms;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The unified "documents" view: one row per LABELLED order carrying every
 * artifact the label generation produced — tracking number, label (PDF/ZPL,
 * served by the existing /orders/{n}/label/* endpoints), commercial invoice
 * (when the order has customs data), and the billing statement figures
 * (carrier cost / markup / billable, straight off the tracking row).
 *
 * <p>Assembled from what the pipeline already persists — no duplicate blob
 * store; the row tells the UI which per-order download endpoints are live.
 */
@Service
@RequiredArgsConstructor
public class OrderDocumentSummaryService {

    private final OrderTrackingRepository orderTrackingRepository;
    private final OrderRepository orderRepository;
    private final OrderCustomsRepository orderCustomsRepository;

    /** Tenant clamp — a scoped USER sees only their own orders' documents. */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    /** One Documents-table row. */
    @Builder
    public record DocumentRow(
            Integer orderNo,
            String custNo,
            String recipientName,
            String city,
            String countryCode,
            String carrier,
            String trackingNumber,
            LocalDateTime generatedAt,
            /** true → customs data exists → the commercial invoice renders. */
            boolean hasInvoice,
            /** true → the label was cancelled at the carrier; downloads remain
             *  for record-keeping but the charge is reversed. */
            boolean voided,
            Integer packageCount,
            String accountNumber,
            BigDecimal carrierAmount,
            BigDecimal billableAmount,
            String markupKind,
            BigDecimal markupValue,
            String markupCurrency) {}

    @Transactional(readOnly = true)
    public List<DocumentRow> list(int limit) {
        int capped = Math.min(Math.max(limit, 1), 500);
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();

        List<OrderTracking> tracks = orderTrackingRepository
                .findGeneratedNewestFirst(PageRequest.of(0, capped));
        List<Integer> orderNos = tracks.stream()
                .map(OrderTracking::getOrderNo)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderNos.isEmpty()) return List.of();

        Map<Integer, Order> ordersByNo = new HashMap<>();
        for (Order o : orderRepository.findByOrderNoIn(orderNos)) {
            ordersByNo.putIfAbsent(o.getOrderNo(), o);
        }
        Set<String> customsOrderNos = new HashSet<>();
        for (OrderCustoms c : orderCustomsRepository.findByOrderNoIn(
                orderNos.stream().map(String::valueOf).toList())) {
            if (c.getOrderNo() != null) customsOrderNos.add(c.getOrderNo().trim());
        }

        List<DocumentRow> rows = new ArrayList<>(tracks.size());
        Set<Integer> seen = new HashSet<>();
        for (OrderTracking t : tracks) {
            Integer no = t.getOrderNo();
            if (no == null || !seen.add(no)) continue;
            Order order = ordersByNo.get(no);
            if (order == null) continue;
            // Tenant clamp — same owner rule as everywhere else: tenantId
            // first, custNo as the legacy fallback.
            if (scope.isPresent()) {
                String owner = StringUtils.hasText(order.getTenantId())
                        ? order.getTenantId() : order.getCustNo();
                if (owner == null || !owner.trim().equalsIgnoreCase(scope.get())) continue;
            }
            rows.add(DocumentRow.builder()
                    .orderNo(no)
                    .custNo(order.getCustNo())
                    .recipientName(order.getShipName())
                    .city(order.getShiptoCity())
                    .countryCode(order.getShiptoCountryCd())
                    .carrier(canonicalCarrier(t.getShipViaCd()))
                    .trackingNumber(t.getTrackingNumber())
                    .generatedAt(t.getLabelGeneratedAt())
                    .hasInvoice(customsOrderNos.contains(String.valueOf(no)))
                    .voided("VOIDED".equalsIgnoreCase(t.getStatus() == null ? "" : t.getStatus().trim()))
                    .packageCount(order.getPackageCount())
                    .accountNumber(t.getAccountNumber())
                    .carrierAmount(t.getCarrierAmount())
                    .billableAmount(t.getBillableAmount())
                    .markupKind(t.getMarkupKind())
                    .markupValue(t.getMarkupValue())
                    .markupCurrency(t.getMarkupCurrency())
                    .build());
        }
        return rows;
    }

    private static String canonicalCarrier(String shipViaCd) {
        String canonical = TrackingServiceImpl.canonicalizeCarrierCode(shipViaCd);
        return StringUtils.hasText(canonical)
                ? canonical.toUpperCase(Locale.ROOT)
                : (shipViaCd == null ? null : shipViaCd.trim().toUpperCase(Locale.ROOT));
    }
}
