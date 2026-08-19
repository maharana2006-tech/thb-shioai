package com.multiship.backend.service.warehouse;

import com.multiship.backend.dto.WarehouseSelectionResult;
import com.multiship.backend.dto.WarehouseSelectionResult.Candidate;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WarehouseSelectorImpl implements WarehouseSelector {

    /** Base score for a same-country warehouse. Chosen so any same-country
     *  entry beats any different-country entry — even a partial postal match
     *  within-country + a huge no-match different-country. */
    private static final int COUNTRY_MATCH_BASE = 100_000;
    /** Bump per matching leading postal character. Cap at 12 so a 30-char
     *  garbage postal can't overflow. */
    private static final int POSTAL_CHAR_WEIGHT = 1_000;
    private static final int POSTAL_MAX_CHARS = 12;
    /** Small bonus for being the client's default warehouse — decides ties
     *  when country/postal are identical. */
    private static final int DEFAULT_TIEBREAK = 10;

    private final ClientWarehouseRepository clientWarehouseRepository;
    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional(readOnly = true)
    public WarehouseSelectionResult selectNearest(String clientCode, String destCountry, String destPostal) {
        String code = normalize(clientCode);
        String country = normalize(destCountry);
        String postal = normalizePostal(destPostal);

        List<ClientWarehouse> attached = clientWarehouseRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
        if (attached.isEmpty()) {
            return WarehouseSelectionResult.builder()
                    .matchReason("NONE").postalPrefixLength(0)
                    .candidates(List.of()).build();
        }

        List<Candidate> candidates = new ArrayList<>(attached.size());
        // N+1 fix (perf audit): one batch fetch of all attached warehouses,
        // not one SELECT per link (this runs on every shipment-routing hit).
        List<Long> whIds = attached.stream().map(ClientWarehouse::getWarehouseId).toList();
        java.util.Map<Long, Warehouse> whById = warehouseRepository.findAllById(whIds).stream()
                .collect(java.util.stream.Collectors.toMap(Warehouse::getId, w -> w));
        for (ClientWarehouse cw : attached) {
            Warehouse w = whById.get(cw.getWarehouseId());
            if (w == null) continue; // repo cleanup lag — skip

            String whCountry = w.getAddress() != null ? normalize(w.getAddress().getCountry()) : "";
            String whPostal = w.getAddress() != null ? normalizePostal(w.getAddress().getZip()) : "";
            boolean sameCountry = !country.isEmpty() && country.equals(whCountry);
            int postalPrefix = 0;
            int score = 0;
            String reason;

            if (sameCountry) {
                score = COUNTRY_MATCH_BASE;
                if (!postal.isEmpty() && !whPostal.isEmpty()) {
                    postalPrefix = commonPrefixLen(postal, whPostal);
                    score += Math.min(postalPrefix, POSTAL_MAX_CHARS) * POSTAL_CHAR_WEIGHT;
                }
                reason = postalPrefix > 0
                        ? "Same country + " + postalPrefix + "-char postal prefix"
                        : "Same country";
            } else {
                // Different (or unknown) country — score 0, but still eligible
                // as a fallback. The default warehouse gets a tiny nudge.
                reason = country.isEmpty()
                        ? "No destination country provided — fallback"
                        : "Different country (" + (whCountry.isEmpty() ? "?" : whCountry)
                                + " vs " + country + ")";
            }
            if (Boolean.TRUE.equals(cw.getIsDefault())) score += DEFAULT_TIEBREAK;

            candidates.add(Candidate.builder()
                    .warehouseId(w.getId())
                    .warehouseCode(w.getCode())
                    .warehouseName(w.getName())
                    .warehouseCountry(whCountry.isEmpty() ? null : whCountry)
                    .warehousePostal(whPostal.isEmpty() ? null : whPostal)
                    .isDefault(cw.getIsDefault())
                    .score(score)
                    .sameCountry(sameCountry)
                    .postalPrefixLength(postalPrefix)
                    .reason(reason)
                    .build());
        }

        if (candidates.isEmpty()) {
            return WarehouseSelectionResult.builder()
                    .matchReason("NONE").postalPrefixLength(0)
                    .candidates(List.of()).build();
        }

        // Score DESC; then repository order (default-first, oldest-first)
        // preserved for equal-score ties via a stable sort.
        candidates.sort(Comparator.comparing(Candidate::getScore).reversed());
        Candidate winner = candidates.get(0);
        String matchReason;
        if (winner.getScore() >= COUNTRY_MATCH_BASE + POSTAL_CHAR_WEIGHT) {
            matchReason = "COUNTRY_AND_POSTAL";
        } else if (winner.getScore() >= COUNTRY_MATCH_BASE) {
            matchReason = "COUNTRY";
        } else {
            matchReason = "ANY";
        }
        return WarehouseSelectionResult.builder()
                .matchReason(matchReason)
                .selectedWarehouseId(winner.getWarehouseId())
                .selectedWarehouseCode(winner.getWarehouseCode())
                .selectedWarehouseName(winner.getWarehouseName())
                .postalPrefixLength(winner.getPostalPrefixLength())
                .candidates(candidates)
                .build();
    }

    // ===== helpers =====

    private static String normalize(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static String normalizePostal(String value) {
        // Postals get an extra scrub: strip whitespace + hyphens so "SW1A 1AA"
        // and "SW1A-1AA" match "SW1A1AA" on the warehouse side.
        if (value == null) return "";
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static int commonPrefixLen(String a, String b) {
        int min = Math.min(a.length(), b.length());
        for (int i = 0; i < min; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return min;
    }
}
