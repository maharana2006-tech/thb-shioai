package com.multiship.backend.service.carriers.platform;

import com.multiship.backend.model.CarrierPlatformEntity;
import com.multiship.backend.repository.CarrierPlatformRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * V112 — in-memory cache over the {@code carriers} platform table.
 * Loads at {@link ApplicationReadyEvent} and after every write via
 * {@link #reload()}. The admin API ({@code /admin/carriers/platform})
 * writes through here so toggles land immediately without a round-trip
 * through the DB on every carrier dispatch.
 *
 * <p>Null / blank carrier code returns the safe default (enabled=true,
 * family=null) so pre-V112 call sites that haven't been swapped yet
 * don't start failing closed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierPlatformService {

    private final CarrierPlatformRepository repo;

    /** Cached by uppercase carrier_code so the hot path is lock-free. */
    private final Map<String, CarrierPlatformEntity> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void initInvariantsIfAvailable() {
        // Defensive — bean-wiring happens before ApplicationReadyEvent in
        // tests that bypass the full Spring lifecycle. Swallow all errors.
        try { reload(); } catch (Exception ignore) { /* loaded by ARE instead */ }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        reload();
    }

    /** Public so the admin controller can trigger after a write. */
    public void reload() {
        try {
            List<CarrierPlatformEntity> rows = repo.findAll();
            Map<String, CarrierPlatformEntity> fresh = rows.stream()
                    .filter(r -> r.getCarrierCode() != null)
                    .collect(Collectors.toMap(
                            r -> r.getCarrierCode().trim().toUpperCase(Locale.ROOT),
                            r -> r,
                            (a, b) -> a));
            cache.clear();
            cache.putAll(fresh);
            log.info("carriers: loaded {} platform row(s)", fresh.size());
        } catch (Exception ex) {
            log.warn("carriers: could not load platform rows; cache keeps prior state: {}", ex.getMessage());
        }
    }

    /** True unless an admin has flipped the row to enabled=false. Unknown
     *  carriers (code not in table) default to true — zero behaviour change
     *  before the admin seeds a row. */
    public boolean isEnabled(String carrierCode) {
        CarrierPlatformEntity row = find(carrierCode);
        return row == null || !Boolean.FALSE.equals(row.getEnabled());
    }

    /** USPS | FEDEX | UPS | DHL | null. */
    public Optional<String> getFamily(String carrierCode) {
        CarrierPlatformEntity row = find(carrierCode);
        return Optional.ofNullable(row == null ? null : row.getFamily());
    }

    public boolean isUspsFamily(String carrierCode) {
        return getFamily(carrierCode).map("USPS"::equalsIgnoreCase).orElse(false);
    }

    public String getMode(String carrierCode) {
        CarrierPlatformEntity row = find(carrierCode);
        return row == null ? CarrierPlatformEntity.MODE_LIVE : row.getMode();
    }

    public List<CarrierPlatformEntity> list() {
        return List.copyOf(cache.values());
    }

    @Transactional
    public CarrierPlatformEntity updateEnabledAndMode(String carrierCode, Boolean enabled, String mode) {
        if (carrierCode == null || carrierCode.isBlank()) {
            throw new IllegalArgumentException("carrier_code required");
        }
        String key = carrierCode.trim().toUpperCase(Locale.ROOT);
        CarrierPlatformEntity row = repo.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("No carriers row for '" + key + "'"));
        if (enabled != null) row.setEnabled(enabled);
        if (mode != null) {
            String m = mode.trim().toUpperCase(Locale.ROOT);
            if (!CarrierPlatformEntity.MODE_LIVE.equals(m) && !CarrierPlatformEntity.MODE_TEST.equals(m)) {
                throw new IllegalArgumentException("mode must be LIVE or TEST");
            }
            row.setMode(m);
        }
        row.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        CarrierPlatformEntity saved = repo.save(row);
        reload();
        return saved;
    }

    private CarrierPlatformEntity find(String carrierCode) {
        if (carrierCode == null || carrierCode.isBlank()) return null;
        return cache.get(carrierCode.trim().toUpperCase(Locale.ROOT));
    }
}
