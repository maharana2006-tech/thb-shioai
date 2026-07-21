package com.multiship.backend.config;

import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds only the known ERP ship-via mappings, once, IF matching synced services
 * already exist. Nothing else is seeded:
 *  - the shipping SERVICE catalog comes exclusively from each carrier's
 *    availability API via "Sync from carrier" (ShippingConfigService.syncFromCarrier);
 *  - PACKAGE presets come exclusively from carrier packaging sync
 *    (syncPackagesFromCarrier) plus any custom boxes the admin creates by hand.
 * This keeps the Shipping Services and Packages pages free of demo/starter data,
 * per the client. Never touches data that already exists.
 */
@Component
@RequiredArgsConstructor
public class ShippingConfigSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ShippingConfigSeeder.class);

    private final ShippingServiceRepository services;
    private final ShipViaMappingRepository mappings;

    @Override
    public void run(String... args) {
        seedMappings();
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
}
