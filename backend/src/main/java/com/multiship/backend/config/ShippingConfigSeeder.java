package com.multiship.backend.config;

import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds the shipping catalog once on an empty database: the real UPS/FedEx/
 * USPS service lists (enabled by default — the admin disables what the
 * platform won't offer), the known ERP ship-via mappings, and a starter set
 * of package presets. Never touches data that already exists.
 */
@Component
@RequiredArgsConstructor
public class ShippingConfigSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ShippingConfigSeeder.class);

    private final ShippingServiceRepository services;
    private final ShipViaMappingRepository mappings;
    private final PackagePresetRepository presets;

    @Override
    public void run(String... args) {
        // NOTE: the shipping SERVICE catalog is NO LONGER seeded — services come
        // exclusively from each carrier's availability API via the "Sync from
        // carrier" flow (ShippingConfigService.syncFromCarrier). This keeps the
        // Shipping Services page free of demo/starter data, per the client. The
        // ERP ship-via mappings still seed IF matching synced services exist.
        seedMappings();
        seedPresets();
    }

    private void seedMappings() {
        if (mappings.count() > 0) return;
        map("P80", "UPS", "03");
        map("F77", "FEDEX", "FEDEX_GROUND");
        map("L01", "USPS", "PRIORITY");
        log.info("Seeded ERP ship-via mappings ({}).", mappings.count());
    }

    private void map(String shipvia, String carrier, String serviceCode) {
        services.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(carrier, serviceCode).ifPresent(s ->
                mappings.save(ShipViaMapping.builder().shipviaCd(shipvia).serviceId(s.getId()).build()));
    }

    private void seedPresets() {
        if (presets.count() > 0) return;
        presets.save(PackagePreset.builder()
                .name("Small Box").kind("CUSTOM")
                .length(new BigDecimal("12")).width(new BigDecimal("9")).height(new BigDecimal("4"))
                .dimUnit("IN").maxWeight(new BigDecimal("5")).weightUnit("LB")
                .tareWeight(new BigDecimal("0.3")).enabled(true).build());
        presets.save(PackagePreset.builder()
                .name("Medium Box").kind("CUSTOM")
                .length(new BigDecimal("16")).width(new BigDecimal("12")).height(new BigDecimal("8"))
                .dimUnit("IN").maxWeight(new BigDecimal("20")).weightUnit("LB")
                .tareWeight(new BigDecimal("0.6")).isDefault(true).enabled(true).build());
        presets.save(PackagePreset.builder()
                .name("Large Box").kind("CUSTOM")
                .length(new BigDecimal("20")).width(new BigDecimal("16")).height(new BigDecimal("12"))
                .dimUnit("IN").maxWeight(new BigDecimal("50")).weightUnit("LB")
                .tareWeight(new BigDecimal("1.0")).enabled(true).build());
        presets.save(PackagePreset.builder()
                .name("UPS Letter").kind("CARRIER").carrierPackageCode("01").carrier("UPS")
                .dimUnit("IN").maxWeight(new BigDecimal("1")).weightUnit("LB").enabled(true).build());
        presets.save(PackagePreset.builder()
                .name("FedEx Pak").kind("CARRIER").carrierPackageCode("FEDEX_PAK").carrier("FEDEX")
                .dimUnit("IN").maxWeight(new BigDecimal("5.5")).weightUnit("LB").enabled(true).build());
        log.info("Seeded package presets ({}).", presets.count());
    }
}
