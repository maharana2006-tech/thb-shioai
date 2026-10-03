package com.multiship.backend.config;

import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
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
 *
 * <p>V126 merge: seed rows now land on {@code client_shipvia_code_map}
 * (nullable clientCode = platform-wide rule); the pre-merge
 * shipvia_service_mapping is gone.
 */
@Component
@RequiredArgsConstructor
public class ShippingConfigSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ShippingConfigSeeder.class);

    private final ShippingServiceRepository services;
    private final ClientShipviaCodeMapRepository mappings;

    @Override
    public void run(String... args) {
        seedMappings();
    }

    private void seedMappings() {
        // Only seed if NO platform-wide (null clientCode) rows exist yet.
        // Per-client rows still beat these; they don't count as "already seeded".
        if (mappings.findAllByOrderByErpCodeAsc().stream().anyMatch(m -> m.getClientCode() == null)) return;
        map("P80", "UPS", "03");
        map("F77", "FEDEX", "FEDEX_GROUND");
        map("L01", "USPS", "PRIORITY");
        log.info("Seeded ERP ship-via mappings on client_shipvia_code_map.");
    }

    private void map(String shipvia, String carrier, String serviceCode) {
        services.findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(carrier, serviceCode).ifPresent(s ->
                mappings.save(ClientShipviaCodeMap.builder()
                        .erpCode(shipvia)
                        .serviceId(s.getId())
                        .isHold(false)
                        .build()));
    }
}
