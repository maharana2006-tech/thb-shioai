package com.multiship.backend.service.label;

import com.multiship.backend.dto.LabelArtifactDTO;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderCustomsRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.LabelFormatConverter;
import com.multiship.backend.service.LabelImageZplService;
import com.multiship.backend.service.LabelMainPageCropper;
import com.multiship.backend.util.ZplText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 1 (Shipment &amp; Label re-architecture) — the read side of a label.
 *
 * <p>Reads {@code label_package} and nothing else: Phase 0 proved every
 * labelled order has a row there, and it wrote an explicit {@code format}
 * and {@code source} on each. So this service never sniffs magic bytes. The
 * stored format decides, by column, whether a package's ZPL is served
 * verbatim ({@code CARRIER_NATIVE}) or wrapped into a {@code ^GFA} bitmap
 * ({@code CARRIER_RASTER}).
 *
 * <p>{@link #zpl(Integer, Integer)} is the single source every output
 * derives from: the preview is a render of it, the PDF is a render of it,
 * and the printer receives it unchanged.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabelArtifactService {

    private final LabelPackageRepository labelPackageRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final LabelImageZplService labelImageZplService;
    private final LabelFormatConverter labelFormatConverter;
    private final LabelMainPageCropper labelMainPageCropper;
    /** BUG-028 — null-tolerant so ctor-arg Mockito tests keep compiling.
     *  Null = treat as "no customs"; the FE hides the button, same effect
     *  as before the field existed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrderCustomsRepository orderCustomsRepository;

    /** The label screen's model. Empty when the order has no packages at all. */
    public Optional<LabelArtifactDTO> describe(Integer orderNo) {
        if (orderNo == null) return Optional.empty();
        List<LabelPackage> pkgs = labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(orderNo);
        Optional<OrderTracking> tracking = orderTrackingRepository.findByOrderNo(orderNo);
        if (pkgs.isEmpty() && tracking.isEmpty()) return Optional.empty();

        // Carrier + environment come from the account the order actually
        // shipped under, never from the live resolution cascade (which can
        // differ from what is printed on the label).
        String carrier = null;
        String environment = null;
        String accountNumber = tracking.map(OrderTracking::getAccountNumber).orElse(null);
        if (StringUtils.hasText(accountNumber)) {
            var ref = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(accountNumber.trim());
            if (ref.isPresent()) {
                carrier = ref.get().getCarrierCode();
                environment = ref.get().getEnvironment();
            }
        }

        List<LabelArtifactDTO.PackageArtifact> packages = pkgs.stream()
                .map(p -> new LabelArtifactDTO.PackageArtifact(
                        p.getSequenceNumber(), p.getFormat(), p.getSource(),
                        p.getTrackingNumber(), p.getTrackingUrl(), p.getCreatedAt()))
                .toList();

        boolean hasCustoms = orderCustomsRepository != null
                && orderCustomsRepository.findByOrderNoIgnoreCase(String.valueOf(orderNo)).isPresent();

        return Optional.of(new LabelArtifactDTO(
                orderNo,
                pkgs.isEmpty() ? "PENDING" : "GENERATED",
                carrier,
                environment,
                tracking.map(OrderTracking::getLabelGeneratedAt).orElse(null),
                packages,
                hasCustoms));
    }

    public Optional<LabelPackage> pkg(Integer orderNo, Integer seq) {
        if (orderNo == null || seq == null || seq < 1) return Optional.empty();
        return labelPackageRepository.findByOrderNoAndSequenceNumber(orderNo, seq);
    }

    /** Every package row for the order, in sequence order. */
    public List<LabelPackage> packages(Integer orderNo) {
        if (orderNo == null) return List.of();
        return labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(orderNo);
    }

    /**
     * The ZPL for one package — the bytes every other output derives from.
     * Native ZPL is returned verbatim (UPS's unsupported {@code ^DN} stripped,
     * as before). A raster is wrapped into {@code ^GFA}. Empty when the
     * package does not exist or its format is UNKNOWN; never a facsimile.
     */
    public Optional<byte[]> zpl(Integer orderNo, Integer seq) {
        return pkg(orderNo, seq).flatMap(this::zplOf);
    }

    /** All packages' ZPL blocks concatenated with a newline — thermal
     *  printers spool consecutive {@code ^XA…^XZ} blocks in order. */
    public Optional<byte[]> zplAll(Integer orderNo) {
        List<LabelPackage> pkgs = labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(orderNo);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        for (LabelPackage p : pkgs) {
            Optional<byte[]> z = zplOf(p);
            if (z.isEmpty()) continue;
            if (any) out.append('\n');
            out.append(new String(z.get(), StandardCharsets.ISO_8859_1));
            any = true;
        }
        return any ? Optional.of(out.toString().getBytes(StandardCharsets.ISO_8859_1)) : Optional.empty();
    }

    /** True when the package is a raster the preview should render from its
     *  image rather than from the {@code ^GFA} wrap. */
    public static boolean isRaster(LabelPackage p) {
        return LabelFormatPolicy.SOURCE_RASTER.equalsIgnoreCase(p.getSource());
    }

    /** The stored carrier bytes, decoded (raw {@code ^XA} text or base64). */
    public Optional<byte[]> storedBytes(LabelPackage p) {
        return Optional.ofNullable(decodeStored(p.getLabelFilePath()));
    }

    private Optional<byte[]> zplOf(LabelPackage p) {
        byte[] stored = decodeStored(p.getLabelFilePath());
        if (stored == null || stored.length == 0) return Optional.empty();
        String format = p.getFormat() == null ? "" : p.getFormat().trim().toUpperCase(Locale.ROOT);
        switch (format) {
            case "ZPL" -> {
                String text = new String(stored, StandardCharsets.ISO_8859_1);
                // ^GFB → ^GFA keeps the bytes copy/paste-safe; ^DN is UPS noise.
                String clean = ZplText.asciiSafe(ZplText.stripUnsupportedCommands(text));
                return Optional.of(clean.getBytes(StandardCharsets.ISO_8859_1));
            }
            case "GIF", "PNG" -> {
                return Optional.of(labelImageZplService.imageToZpl(stored).getBytes(StandardCharsets.ISO_8859_1));
            }
            case "PDF" -> {
                // Crop to the main label first: FedEx returns a Letter page
                // with the 4×6 label in one quadrant, and ^GFA of the whole
                // page would shrink the label into a corner.
                byte[] page = labelMainPageCropper.mainLabelOnly(stored);
                String z = labelFormatConverter.renderAll(page, "PDF").zpl();
                return StringUtils.hasText(z)
                        ? Optional.of(z.getBytes(StandardCharsets.ISO_8859_1))
                        : Optional.empty();
            }
            default -> { return Optional.empty(); }
        }
    }

    /** Raw {@code ^XA} text is stored as-is; everything else is base64. A
     *  URL or undecodable value yields null — the caller 404s honestly. */
    static byte[] decodeStored(String stored) {
        if (!StringUtils.hasText(stored)) return null;
        String t = stored.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return null;
        if (t.startsWith("^")) return t.getBytes(StandardCharsets.ISO_8859_1);  // raw ZPL, ^XA or ^FX-prefixed
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
