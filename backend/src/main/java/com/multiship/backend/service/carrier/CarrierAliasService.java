package com.multiship.backend.service.carrier;

import com.multiship.backend.model.CarrierAliasEntity;
import com.multiship.backend.repository.CarrierAliasRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * C4 — canonical carrier vocabulary backed by {@code carrier_alias}.
 * Retires the three duplicated switch statements + FE closed-set check
 * flagged in the manual-shipment audit (H-06, H-28, H-07 partial).
 *
 * <p>Snapshots the whole table into memory at boot (10-ish rows for
 * years to come) so canonicalisation is a HashMap lookup on the hot
 * label / validation path. {@link #reload()} lets an admin refresh
 * after an INSERT without a restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierAliasService {

    private final CarrierAliasRepository repo;

    /** source → target. */
    private volatile Map<String, String> canonicalByCode = Map.of();
    /** source → display_label. */
    private volatile Map<String, String> displayByCode = Map.of();
    /** Rows where source == target, sorted by label — the "known carriers" list. */
    private volatile List<KnownCarrier> knownCarriers = List.of();

    @PostConstruct
    @Transactional(readOnly = true)
    public void reload() {
        List<CarrierAliasEntity> rows = repo.findAll();
        Map<String, String> canon = new HashMap<>(rows.size() * 2);
        Map<String, String> display = new HashMap<>(rows.size() * 2);
        List<KnownCarrier> known = new java.util.ArrayList<>();
        for (CarrierAliasEntity r : rows) {
            String src = r.getSourceCode().toUpperCase(Locale.ROOT);
            canon.put(src, r.getTargetCode().toUpperCase(Locale.ROOT));
            display.put(src, r.getDisplayLabel());
            if (src.equalsIgnoreCase(r.getTargetCode())) {
                known.add(new KnownCarrier(src, r.getDisplayLabel()));
            }
        }
        known.sort(Comparator.comparing(KnownCarrier::label));
        this.canonicalByCode = Map.copyOf(canon);
        this.displayByCode = Map.copyOf(display);
        this.knownCarriers = List.copyOf(known);
        CarrierAliasHolder.set(this);
        log.info("CarrierAliasService loaded {} rows ({} canonical)",
                rows.size(), known.size());
    }

    /**
     * Returns the canonical carrier for the given code, or null if unknown.
     * Callers that want a "pass-through if unknown" default can use
     * {@link #canonicalizeOr(String, String)}.
     */
    public String canonicalize(String code) {
        if (code == null || code.isBlank()) return null;
        return canonicalByCode.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public String canonicalizeOr(String code, String fallback) {
        String c = canonicalize(code);
        return c != null ? c : fallback;
    }

    /**
     * Returns the human-readable label ("FedEx", "UPS", ...) for a code,
     * or the code itself when unknown (so unmapped carriers still render).
     */
    public String display(String code) {
        if (code == null || code.isBlank()) return "";
        String label = displayByCode.get(code.trim().toUpperCase(Locale.ROOT));
        return label != null ? label : code.trim().toUpperCase(Locale.ROOT);
    }

    /** Canonical rows with their display labels — the /carriers/known list. */
    public List<KnownCarrier> knownCarriers() {
        return knownCarriers;
    }

    public record KnownCarrier(String code, String label) {}
}
