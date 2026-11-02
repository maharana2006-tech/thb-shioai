package com.multiship.backend.service.carrier;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * G2 — static bridge for static-method call sites (FedExConnector's
 * {@code formatFedExPickupTimestamp}, future UPS/DHL EOD slot
 * timestamps) that can't take an @Autowired injection. Same pattern
 * as {@link CarrierAliasHolder}: set once by
 * {@link StateZoneService#reload()} at @PostConstruct; callers get
 * {@link Optional#empty()} until then, which is fine because none
 * of these methods run during Spring bean construction.
 */
public final class StateZoneHolder {

    private static volatile StateZoneService svc;

    private StateZoneHolder() {}

    static void set(StateZoneService s) {
        svc = s;
    }

    /** @return the configured IANA zone for the (country, state) pair, or empty. */
    public static Optional<ZoneId> zoneFor(String countryCode, String stateCode) {
        StateZoneService s = svc;
        return s == null ? Optional.empty() : s.zoneFor(countryCode, stateCode);
    }

    /**
     * @return the DST-aware offset at {@code moment} for the configured zone,
     * or empty when (country, state) isn't mapped. Callers emit
     * {@code offset.getId()} to get the canonical ISO 8601 {@code ±hh:mm}
     * form.
     */
    public static Optional<ZoneOffset> offsetFor(String countryCode, String stateCode, LocalDateTime moment) {
        return zoneFor(countryCode, stateCode).map(z -> z.getRules().getOffset(moment));
    }
}
