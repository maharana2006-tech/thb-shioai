package com.multiship.backend.service;

import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * G6 — resolve the client's "STD" ship-via to a concrete shipping service,
 * plus a reverse-lookup ERP code for the NDS OEHEAD.SHIPVIA_CD writeback
 * per ShipX_NDS_Orders_and_Tracking.docx §5 + §6.
 *
 * <p>How the client configures this:
 * <ol>
 *   <li>Settings → Shipping Service Mapping → add a row with
 *       {@code erp_code = "STD"} and {@code client_code = <client>}.
 *   <li>Pick the target service (e.g. FEDEX_GROUND).
 * </ol>
 * The resolver looks that row up on every STD order. If no mapping row
 * exists, {@link #resolveStdForClient(String)} returns empty; the caller
 * fails the label with an actionable message.
 *
 * <p><b>Reverse ERP code for writeback:</b> NDS wants the operator's ERP
 * code on OEHEAD.SHIPVIA_CD (e.g. "P80" or "F03"), not our internal
 * service code. After resolving STD → serviceId, we scan the same
 * client's other ShipViaMapping rows for one where {@code service_id =
 * <resolved>} AND {@code shipvia_cd != 'STD'}. First deterministic hit
 * wins (ordered by id) so the same order → same ERP code across runs.
 * If no other mapping row exists, {@link Result#erpCodeForNds} is empty —
 * writer skips the OEHEAD update in that case (doc doesn't spec a
 * fallback and we don't want to guess wrong).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StdShipMethodResolver {

    public static final String STD = "STD";

    private final ShipViaMappingRepository mappingRepo;
    private final ShippingServiceRepository serviceRepo;

    public record Result(ShippingService service, String erpCodeForNds) {}

    /** Returns empty when the client has no STD mapping configured. */
    public Optional<Result> resolveStdForClient(String clientCode) {
        if (clientCode == null || clientCode.isBlank()) return Optional.empty();
        String normalizedClient = clientCode.trim().toUpperCase();
        Optional<ShipViaMapping> stdRow = mappingRepo.findByShipviaCdIgnoreCase(STD).stream()
                .filter(m -> normalizedClient.equalsIgnoreCase(
                        m.getClientCode() == null ? "" : m.getClientCode().trim()))
                .min(Comparator.comparing(ShipViaMapping::getId));
        if (stdRow.isEmpty()) {
            log.debug("STD resolver: no mapping for client {}", normalizedClient);
            return Optional.empty();
        }
        Optional<ShippingService> svc = serviceRepo.findById(stdRow.get().getServiceId());
        if (svc.isEmpty()) {
            log.warn("STD resolver: mapping row {} points at missing service {}",
                    stdRow.get().getId(), stdRow.get().getServiceId());
            return Optional.empty();
        }
        String erp = reverseErpCode(normalizedClient, stdRow.get().getServiceId());
        return Optional.of(new Result(svc.get(), erp));
    }

    /**
     * Find the client's non-STD ERP code that maps to the resolved service.
     * First deterministic hit wins (ordered by mapping id). Null when no
     * non-STD row exists for this (client, service) pair.
     */
    public String reverseErpCode(String clientCode, Long serviceId) {
        List<ShipViaMapping> candidates = mappingRepo.findByServiceId(serviceId);
        return candidates.stream()
                .filter(m -> clientCode.equalsIgnoreCase(
                        m.getClientCode() == null ? "" : m.getClientCode().trim()))
                .filter(m -> !STD.equalsIgnoreCase(m.getShipviaCd()))
                .min(Comparator.comparing(ShipViaMapping::getId))
                .map(ShipViaMapping::getShipviaCd)
                .orElse(null);
    }
}
