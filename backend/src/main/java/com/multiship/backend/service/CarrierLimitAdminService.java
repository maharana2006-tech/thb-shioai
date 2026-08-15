package com.multiship.backend.service;

import com.multiship.backend.dto.CarrierShippingLimitRequest;
import com.multiship.backend.dto.CarrierShippingLimitResponse;
import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.repository.CarrierShippingLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprint 52 — CRUD service for the {@code /settings/carrier-limits} admin
 * page. Wraps the JPA repo with a thin translation layer so the controller
 * stays validation- and shaping-only.
 *
 * <p>Every write invalidates {@link CarrierLimitService}'s in-memory cache
 * so the resolver picks up the change immediately — otherwise an operator
 * edit takes up to 5 minutes to appear in the shipment-create path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierLimitAdminService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final CarrierShippingLimitRepository repository;
    private final CarrierLimitService resolverService;

    @Transactional(readOnly = true)
    public List<CarrierShippingLimitResponse> list(int page, int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(clampedPage, clampedSize,
                Sort.by(Sort.Direction.ASC, "carrierCode")
                        .and(Sort.by(Sort.Direction.ASC, "serviceCode"))
                        .and(Sort.by(Sort.Direction.ASC, "scope")));
        Page<CarrierShippingLimit> rows = repository.findAll(pageable);
        return rows.getContent().stream()
                .map(CarrierShippingLimitResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CarrierShippingLimitResponse> get(Long id) {
        return repository.findById(id).map(CarrierShippingLimitResponse::from);
    }

    @Transactional
    public CarrierShippingLimitResponse create(CarrierShippingLimitRequest req) {
        CarrierShippingLimit entity = CarrierShippingLimit.builder()
                .carrierCode(normalizeCarrier(req.getCarrierCode()))
                .serviceCode(blankToNull(req.getServiceCode()))
                .scope(normalizeUpper(req.getScope()))
                .direction(normalizeUpper(req.getDirection()))
                .maxPackages(req.getMaxPackages())
                .maxCommodities(req.getMaxCommodities())
                .maxTotalWeightLb(req.getMaxTotalWeightLb())
                .freeDeclaredValue(req.getFreeDeclaredValue())
                .effectiveFrom(LocalDateTime.now())
                .active(req.getActive() == null ? Boolean.TRUE : req.getActive())
                .notes(blankToNull(req.getNotes()))
                .build();
        CarrierShippingLimit saved = repository.save(entity);
        resolverService.invalidateCache();
        log.info("carrier_shipping_limit CREATED id={} carrier={} service={} scope={} direction={}",
                saved.getId(), saved.getCarrierCode(), saved.getServiceCode(),
                saved.getScope(), saved.getDirection());
        return CarrierShippingLimitResponse.from(saved);
    }

    @Transactional
    public Optional<CarrierShippingLimitResponse> update(Long id, CarrierShippingLimitRequest req) {
        return repository.findById(id).map(existing -> {
            existing.setCarrierCode(normalizeCarrier(req.getCarrierCode()));
            existing.setServiceCode(blankToNull(req.getServiceCode()));
            existing.setScope(normalizeUpper(req.getScope()));
            existing.setDirection(normalizeUpper(req.getDirection()));
            existing.setMaxPackages(req.getMaxPackages());
            existing.setMaxCommodities(req.getMaxCommodities());
            existing.setMaxTotalWeightLb(req.getMaxTotalWeightLb());
            existing.setFreeDeclaredValue(req.getFreeDeclaredValue());
            if (req.getActive() != null) existing.setActive(req.getActive());
            existing.setNotes(blankToNull(req.getNotes()));
            CarrierShippingLimit saved = repository.save(existing);
            resolverService.invalidateCache();
            log.info("carrier_shipping_limit UPDATED id={}", saved.getId());
            return CarrierShippingLimitResponse.from(saved);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) return false;
        repository.deleteById(id);
        resolverService.invalidateCache();
        log.info("carrier_shipping_limit DELETED id={}", id);
        return true;
    }

    private static String normalizeCarrier(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeUpper(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
