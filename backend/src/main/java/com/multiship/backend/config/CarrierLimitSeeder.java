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
 * {@code carrier_shipping_limit}. Sprint 48 B2, extended in Sprint 52 with
 * direction (FORWARD / RETURN) and max-commodity caps.
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
        // maxCommodities: FedEx accepts up to 999 commodity lines per
        // shipment (CustomsClearanceDetail.commodities cap).
        seed("FEDEX", "FEDEX_ENVELOPE", "BOTH", null, 1, 999, bd("1"), bd("100"),
                "FedEx Envelope: single-piece service, 1 lb max.", now);
        seed("FEDEX", "FEDEX_PAK", "BOTH", null, 1, 999, bd("5"), bd("100"),
                "FedEx Pak: single-piece service, 5 lb max.", now);
        seed("FEDEX", null, "DOMESTIC", null, 40, 999, bd("30000"), bd("100"),
                "FedEx MPS domestic default: 40 pkgs, 30000 lb total, $100 free coverage.", now);
        seed("FEDEX", null, "INTERNATIONAL", null, 25, 999, bd("30000"), bd("100"),
                "FedEx MPS international default: 25 pkgs.", now);

        // UPS — Sprint 52 correction: forward cap = 200 (was 20). The 20
        // cap is the RETURN MPS ceiling per UPS Ship API docs. UPS Paperless
        // Invoice caps ProductList at 50 commodity lines.
        // Ground Saver dropped freeDeclaredValue to $20 in April 2025.
        seed("UPS", "GROUND_SAVER", "DOMESTIC", null, 20, 50, bd("30000"), bd("20"),
                "UPS Ground Saver: $20 free coverage (Apr 2025 change).", now);
        seed("UPS", null, "BOTH", "FORWARD", 200, 50, bd("30000"), bd("100"),
                "UPS Ship API forward MPS: 200 pkgs, 30000 lb total, $100 free coverage.", now);
        seed("UPS", null, "BOTH", "RETURN", 20, 50, bd("30000"), bd("100"),
                "UPS Ship API return MPS: 20 pkgs (returns are capped lower than forward).", now);
        seed("UPS", "MAIL_INNOVATIONS", "BOTH", null, 1, 50, bd("70"), bd("100"),
                "UPS Mail Innovations: single-piece only.", now);
        seed("UPS", "SIMPLE_RATE", "BOTH", null, 1, 50, bd("50"), bd("100"),
                "UPS Simple Rate: single-piece flat-rate service.", now);

        // DHL Express — generous cap. No free-tier for Shipment Insurance
        // (paid VAS from dollar 1), so null freeDeclaredValue. DHL MyDHL API
        // accepts up to 999 commodity lines per shipment.
        seed("DHL", null, "BOTH", null, 999, 999, bd("22000"), null,
                "DHL Express default: 999 pkgs, 22000 lb total; insurance is paid VAS (no free tier).", now);

        // Stamps/USPS — SWSIM is single-piece by design (we already loop N calls).
        // USPS Priority Mail includes up to $100 insurance; Ground Advantage $100.
        // Customs form fits up to 999 commodity lines (PS 2976/2976-A/2976-B
        // are the same envelope; SWSIM validates the array size).
        seed("STAMPS", null, "BOTH", null, 1, 999, bd("70"), bd("100"),
                "SWSIM CreateIndicium: 1 piece per call; USPS 70 lb piece max, $100 built-in.", now);

        log.info("Seeded {} carrier_shipping_limit rows.", repository.count());
    }

    private void seed(String carrier, String service, String scope, String direction,
                      int maxPkg, int maxCommodities,
                      BigDecimal maxWeightLb, BigDecimal freeDeclared,
                      String notes, LocalDateTime now) {
        repository.save(CarrierShippingLimit.builder()
                .carrierCode(carrier)
                .serviceCode(service)
                .scope(scope)
                .direction(direction)
                .maxPackages(maxPkg)
                .maxCommodities(maxCommodities)
                .maxTotalWeightLb(maxWeightLb)
                .freeDeclaredValue(freeDeclared)
                .effectiveFrom(now)
                .active(true)
                .notes(notes)
                .build());
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
