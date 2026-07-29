package com.multiship.backend.service.warehouse;

import com.multiship.backend.dto.WarehouseSelectionResult;

/**
 * G3 — pick the "nearest" attached warehouse for a client + destination.
 *
 * <p>Sprint 47's smart routing wants "for dest=CA, ship from the warehouse
 * closest to CA" but the codebase has no lat/lon on warehouses. This
 * selector approximates distance from the fields that ARE stored on the
 * warehouse address:
 *
 * <ol>
 *   <li>Same country as the destination — huge score.</li>
 *   <li>Postal prefix length within the same country — one bump per
 *       matching leading char.</li>
 *   <li>Otherwise fall back to any attached warehouse (default first).</li>
 * </ol>
 *
 * <p>The result includes a per-candidate trace so a dry-run UI can show
 * why the winner won.
 */
public interface WarehouseSelector {

    /**
     * Score every warehouse attached to the client and return the winner.
     *
     * @param clientCode the client whose attached warehouses are candidates.
     * @param destCountry ISO-2 destination country. Null/blank means the
     *                    selector can only fall back to "any attached".
     * @param destPostal  destination postal code. Null/blank disables the
     *                    postal-prefix bonus.
     * @return the resolved winner and per-candidate trace. When the client
     *         has no attached warehouses, {@link WarehouseSelectionResult#getMatchReason()}
     *         is {@code NONE} and the selected fields are null.
     */
    WarehouseSelectionResult selectNearest(String clientCode, String destCountry, String destPostal);
}
