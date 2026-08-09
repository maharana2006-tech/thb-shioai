package com.multiship.backend.config;

import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.repository.CarrierShippingLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Seeds default per-carrier / per-service MPS + total-weight caps into
 * {@code carrier_shipping_limit}. Sprint 48 B2.
 *
 * <p>Values are best-effort defaults from public carrier docs (as of
 * mid-2026) and MUST be verified against each carrier's current API
 * spec before production. Ops can override any row via SQL or a future
 * admin UI without redeploying.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarrierLimitSeeder implements CommandLineRunner {

    private final CarrierShippingLimitRepository repository;

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.debug("carrier_shipping_limit already seeded ({} rows), skipping.", repository.count());
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        // FedEx — per-service caps, then defaults.
        // freeDeclaredValue: FedEx includes first $100 of coverage free
        // on every service (source: fedex.com/en-us/shipping/declared-value.html).
        seed("FEDEX", "FEDEX_ENVELOPE", "BOTH", 1, bd("1"), bd("100"),
                "FedEx Envelope: single-piece service, 1 lb max.", now);
        seed("FEDEX", "FEDEX_PAK", "BOTH", 1, bd("5"), bd("100"),
                "FedEx Pak: single-piece service, 5 lb max.", now);
        seed("FEDEX", null, "DOMESTIC", 40, bd("30000"), bd("100"),
                "FedEx MPS domestic default: 40 pkgs, 30000 lb total, $100 free coverage.", now);
        seed("FEDEX", null, "INTERNATIONAL", 25, bd("30000"), bd("100"),
                "FedEx MPS international default: 25 pkgs.", now);

        // UPS — 20 pkgs is safe across all services. First $100 free
        // coverage on most services; Ground Saver dropped to $20 in
        // April 2025 (source: refundretriever.com/blog/ups-declared-value).
        seed("UPS", "GROUND_SAVER", "DOMESTIC", 20, bd("30000"), bd("20"),
                "UPS Ground Saver: $20 free coverage (Apr 2025 change).", now);
        seed("UPS", null, "BOTH", 20, bd("30000"), bd("100"),
                "UPS Ship API default: 20 pkgs, 30000 lb total, $100 free coverage.", now);

        // DHL Express — generous cap. No free-tier for Shipment Insurance
        // (paid VAS from dollar 1), so null freeDeclaredValue.
        seed("DHL", null, "BOTH", 999, bd("22000"), null,
                "DHL Express default: 999 pkgs, 22000 lb total; insurance is paid VAS (no free tier).", now);

        // Stamps/USPS — SWSIM is single-piece by design (we already loop N calls).
        // USPS Priority Mail includes up to $100 insurance; Ground Advantage $100.
        seed("STAMPS", null, "BOTH", 1, bd("70"), bd("100"),
                "SWSIM CreateIndicium: 1 piece per call; USPS 70 lb piece max, $100 built-in.", now);

        log.info("Seeded {} carrier_shipping_limit rows.", repository.count());
    }

    private void seed(String carrier, String service, String scope, int maxPkg,
                      BigDecimal maxWeightLb, BigDecimal freeDeclared,
                      String notes, LocalDateTime now) {
        repository.save(CarrierShippingLimit.builder()
                .carrierCode(carrier)
                .serviceCode(service)
                .scope(scope)
                .maxPackages(maxPkg)
                .maxTotalWeightLb(maxWeightLb)
                .freeDeclaredValue(freeDeclared)
                .effectiveFrom(now)
                .active(true)
                .notes(notes)
                .build());
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
