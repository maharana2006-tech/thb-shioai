package com.multiship.backend.service.carrier;

import com.multiship.backend.model.StateZoneEntity;
import com.multiship.backend.repository.StateZoneRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * G2 — country + state → IANA zone lookup backed by {@code state_zone}.
 * Snapshots the whole table into memory at boot ({@code reload()} on
 * PostConstruct). ~55 US rows today; room for CA / AU / etc. when
 * needed. Phase B (admin controller) calls {@link #reload()} after an
 * upsert so a correction lands without a restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateZoneService {

    private final StateZoneRepository repo;

    /** key = "US:MI" → ZoneId. */
    private volatile Map<String, ZoneId> zonesByKey = Map.of();

    @PostConstruct
    @Transactional(readOnly = true)
    public void reload() {
        List<StateZoneEntity> rows = repo.findAll();
        Map<String, ZoneId> next = new HashMap<>(rows.size() * 2);
        int bad = 0;
        for (StateZoneEntity r : rows) {
            if (r.getId() == null) continue;
            String country = r.getId().getCountryCode();
            String state = r.getId().getStateCode();
            String zone = r.getZoneId();
            if (country == null || state == null || zone == null) continue;
            try {
                next.put(key(country, state), ZoneId.of(zone.trim()));
            } catch (DateTimeException ex) {
                bad++;
                log.warn("StateZoneService: skipping invalid zone id for {}/{}: '{}' ({})",
                        country, state, zone, ex.getMessage());
            }
        }
        this.zonesByKey = Map.copyOf(next);
        StateZoneHolder.set(this);
        log.info("StateZoneService loaded {} rows{}", next.size(),
                bad > 0 ? " (" + bad + " skipped)" : "");
    }

    /**
     * @return the configured zone for the given (country, state) pair.
     * Case-insensitive. Returns empty when either arg is blank or the
     * pair isn't in the table — caller decides the fallback.
     */
    public Optional<ZoneId> zoneFor(String countryCode, String stateCode) {
        if (countryCode == null || stateCode == null) return Optional.empty();
        String c = countryCode.trim();
        String s = stateCode.trim();
        if (c.isEmpty() || s.isEmpty()) return Optional.empty();
        return Optional.ofNullable(zonesByKey.get(key(c, s)));
    }

    /** Admin CRUD — all rows, sorted (country, state). */
    @Transactional(readOnly = true)
    public List<StateZoneEntity> list() {
        return repo.findAll().stream()
                .sorted((a, b) -> {
                    int cmp = a.getId().getCountryCode().compareTo(b.getId().getCountryCode());
                    return cmp != 0 ? cmp : a.getId().getStateCode().compareTo(b.getId().getStateCode());
                })
                .toList();
    }

    /**
     * Admin CRUD — insert-or-update one (country, state) row. Validates
     * {@code zoneId} parses as an IANA zone before persisting (so a
     * typo like "America/New_york" throws at the admin boundary, not
     * at the next FedEx pickup build). Triggers {@link #reload()} so
     * the hot path sees the change without a restart.
     */
    @Transactional
    public StateZoneEntity upsert(String countryCode, String stateCode, String zoneId,
                                   String displayName, String actor) {
        String country = requireNonBlank(countryCode, "countryCode").toUpperCase(Locale.ROOT);
        String state = requireNonBlank(stateCode, "stateCode").toUpperCase(Locale.ROOT);
        String zone = requireNonBlank(zoneId, "zoneId").trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("zoneId '" + zone + "' is not a valid IANA zone"
                    + " (expected e.g. 'America/New_York'): " + ex.getMessage());
        }
        StateZoneEntity.StateZoneId pk = StateZoneEntity.StateZoneId.builder()
                .countryCode(country).stateCode(state).build();
        StateZoneEntity row = repo.findById(pk).orElseGet(() ->
                StateZoneEntity.builder().id(pk).build());
        row.setZoneId(zone);
        row.setDisplayName(displayName != null ? displayName.trim() : null);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        row.setUpdatedBy(actor);
        StateZoneEntity saved = repo.save(row);
        reload();
        return saved;
    }

    /**
     * Admin CRUD — delete one row. Idempotent: deleting a non-existent
     * key is a no-op. Triggers {@link #reload()} so the hot path drops
     * the entry.
     */
    @Transactional
    public void delete(String countryCode, String stateCode) {
        String country = requireNonBlank(countryCode, "countryCode").toUpperCase(Locale.ROOT);
        String state = requireNonBlank(stateCode, "stateCode").toUpperCase(Locale.ROOT);
        StateZoneEntity.StateZoneId pk = StateZoneEntity.StateZoneId.builder()
                .countryCode(country).stateCode(state).build();
        if (repo.existsById(pk)) {
            repo.deleteById(pk);
            reload();
        }
    }

    private static String requireNonBlank(String v, String field) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return v.trim();
    }

    private static String key(String country, String state) {
        return country.toUpperCase(Locale.ROOT) + ":" + state.toUpperCase(Locale.ROOT);
    }
}
