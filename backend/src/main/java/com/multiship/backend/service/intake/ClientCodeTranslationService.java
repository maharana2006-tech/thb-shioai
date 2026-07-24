package com.multiship.backend.service.intake;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * Order-intake step 1: translate raw ERP codes on an incoming shipment into
 * canonical platform values via the per-client alias tables.
 *
 * <p>Design contract for each translate* method:
 * <ul>
 *   <li>{@code null}/blank input → returns empty {@code Optional} (nothing to
 *       do; caller keeps whatever value it already has).</li>
 *   <li>Non-blank input with a matching alias row → returns the canonical
 *       value.</li>
 *   <li>Non-blank input with NO alias row → throws
 *       {@link ShipmentResolutionException} carrying the appropriate
 *       {@code UNKNOWN_*} code. The strict-rejection policy chosen in the
 *       Phase-5 design.</li>
 * </ul>
 *
 * <p>Clients without a client_code (ad-hoc shipments) skip translation
 * entirely — the caller just doesn't invoke us in that case.
 */
@Service
@RequiredArgsConstructor
public class ClientCodeTranslationService {

    private final ClientShipviaCodeMapRepository shipviaRepo;
    private final ClientServiceCodeMapRepository serviceRepo;
    private final ClientDestCountryMapRepository destRepo;
    private final ClientPackageCodeMapRepository packageRepo;

    @Transactional(readOnly = true)
    public Optional<Long> translateShipvia(String clientCode, String rawCode) {
        if (!hasBoth(clientCode, rawCode)) return Optional.empty();
        // Empty alias table for this client → they're not using shipvia
        // aliases; caller falls through to whatever legacy resolution it has
        // (typically ShipViaMapping rules).
        if (shipviaRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(clientCode.trim()).isEmpty()) {
            return Optional.empty();
        }
        return shipviaRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), rawCode.trim())
                .map(row -> Optional.of(row.getServiceId()))
                .orElseThrow(() -> unknown(ErrorCode.UNKNOWN_SHIPVIA_CODE,
                        "No shipvia alias configured for client " + normalize(clientCode)
                                + " and ERP code '" + rawCode.trim() + "'."));
    }

    @Transactional(readOnly = true)
    public Optional<Long> translateServiceCode(String clientCode, String rawCode) {
        if (!hasBoth(clientCode, rawCode)) return Optional.empty();
        if (serviceRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(clientCode.trim()).isEmpty()) {
            return Optional.empty();
        }
        return serviceRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), rawCode.trim())
                .map(row -> Optional.of(row.getServiceId()))
                .orElseThrow(() -> unknown(ErrorCode.UNKNOWN_SERVICE_CODE,
                        "No service-code alias configured for client " + normalize(clientCode)
                                + " and ERP code '" + rawCode.trim() + "'."));
    }

    @Transactional(readOnly = true)
    public Optional<String> translateDestCountry(String clientCode, String rawCode) {
        if (!hasBoth(clientCode, rawCode)) return Optional.empty();
        String raw = rawCode.trim();
        // Fast path: incoming code already looks canonical (2 letters). Skip
        // the lookup so ERPs that already send ISO-2 don't need alias rows.
        if (raw.length() == 2) return Optional.of(raw.toUpperCase(Locale.ROOT));
        if (destRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(clientCode.trim()).isEmpty()) {
            return Optional.empty();
        }
        return destRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), raw)
                .map(row -> Optional.of(row.getIso2()))
                .orElseThrow(() -> unknown(ErrorCode.UNKNOWN_DEST_CODE,
                        "No destination-country alias configured for client " + normalize(clientCode)
                                + " and ERP code '" + raw + "'."));
    }

    @Transactional(readOnly = true)
    public Optional<Long> translatePackage(String clientCode, String rawCode) {
        if (!hasBoth(clientCode, rawCode)) return Optional.empty();
        if (packageRepo.findByClientCodeIgnoreCaseOrderByErpCodeAsc(clientCode.trim()).isEmpty()) {
            return Optional.empty();
        }
        return packageRepo
                .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), rawCode.trim())
                .map(row -> Optional.of(row.getPresetId()))
                .orElseThrow(() -> unknown(ErrorCode.UNKNOWN_PACKAGE_CODE,
                        "No package alias configured for client " + normalize(clientCode)
                                + " and ERP code '" + rawCode.trim() + "'."));
    }

    // ===== helpers =====

    private static boolean hasBoth(String client, String erp) {
        return StringUtils.hasText(client) && StringUtils.hasText(erp);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static ShipmentResolutionException unknown(ErrorCode code, String msg) {
        return new ShipmentResolutionException(code, msg);
    }
}
