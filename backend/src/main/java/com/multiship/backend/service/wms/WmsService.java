package com.multiship.backend.service.wms;

import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.dto.wms.WmsShippableOrderDTO;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls "shippable" orders from the external WMS and imports each as a PENDING
 * {@link Order} (source = WMS) so it shows up in the Orders workspace ready to
 * label. Idempotent: an order already imported (matched on {@code
 * wmsExternalId}) is skipped on re-pull.
 *
 * <p>Config-gated via {@link WmsClient}: with no WMS_BASE_URL / WMS_API_KEY the
 * pull is a no-op that reports {@code configured = false}.
 */
@Service
@RequiredArgsConstructor
public class WmsService {

    private static final Logger log = LoggerFactory.getLogger(WmsService.class);

    private final WmsClient wmsClient;
    private final OrderRepository orderRepository;

    public boolean isConfigured() {
        return wmsClient.isConfigured();
    }

    @Transactional
    public WmsPullResultDTO pullShippable(String requestedBy) {
        if (!wmsClient.isConfigured()) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of(
                            "WMS is not configured. Set WMS_BASE_URL and WMS_API_KEY to enable the pull."))
                    .importedOrderNos(List.of())
                    .build();
        }

        List<WmsShippableOrderDTO> shippable = wmsClient.fetchShippable();
        int imported = 0, skipped = 0, failed = 0;
        List<Integer> importedNos = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        for (WmsShippableOrderDTO src : shippable) {
            String externalId = src == null ? null : src.getExternalId();
            if (!StringUtils.hasText(externalId)) {
                failed++;
                messages.add("Skipped a WMS order with no externalId.");
                continue;
            }
            if (orderRepository.existsByWmsExternalId(externalId.trim())) {
                skipped++;
                continue;
            }
            try {
                Order order = toOrder(src);
                order = orderRepository.save(order);
                imported++;
                importedNos.add(order.getOrderNo());
            } catch (Exception e) {
                failed++;
                messages.add("WMS order " + externalId + " failed to import: " + e.getMessage());
                log.warn("WMS order {} import failed: {}", externalId, e.getMessage());
            }
        }

        log.info("WMS pull ({}): fetched {}, imported {}, skipped {}, failed {}",
                requestedBy, shippable.size(), imported, skipped, failed);
        return WmsPullResultDTO.builder()
                .configured(true)
                .fetched(shippable.size())
                .imported(imported)
                .skipped(skipped)
                .failed(failed)
                .importedOrderNos(importedNos)
                .messages(messages)
                .build();
    }

    /** Map a WMS shippable order to a PENDING, WMS-sourced Order (label_batch). */
    private Order toOrder(WmsShippableOrderDTO src) {
        Order o = new Order();
        o.setOrderNo(orderRepository.nextManualOrderNo());
        o.setOrderSuffix(0);
        o.setOrderStatus("PENDING");   // shippable, awaiting label
        o.setSource("WMS");
        o.setIsManual("N");
        o.setIsReturn("N");
        o.setWmsExternalId(src.getExternalId().trim());
        o.setCustNo(trimOr(src.getClientCode(), "WMS"));
        o.setTenantId(StringUtils.hasText(src.getClientCode()) ? src.getClientCode().trim() : null);
        o.setShipName(src.getRecipientName());
        o.setShipAttn(src.getRecipientCompany());
        o.setShipAddr1(src.getAddressLine1());
        o.setShiptoCity(src.getCity());
        o.setShiptoState(src.getState());
        o.setShiptoZip(src.getPostalCode());
        o.setShiptoCountryCd(src.getCountryCode());
        o.setShipviaCd(StringUtils.hasText(src.getServiceType()) ? src.getServiceType() : src.getCarrierCode());
        o.setGoodsDesc(src.getReference());
        if (src.getWeight() != null) {
            o.setWeight(java.math.BigDecimal.valueOf(src.getWeight()));
        }
        o.setCreatedDate(LocalDate.now());
        return o;
    }

    private static String trimOr(String v, String fallback) {
        return StringUtils.hasText(v) ? v.trim() : fallback;
    }
}
