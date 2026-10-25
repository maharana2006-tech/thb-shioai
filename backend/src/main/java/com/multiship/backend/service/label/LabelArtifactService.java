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

        // BUG-049 — once an order is voided, state must read VOIDED on
        // the Label API so the FE stops offering Download/Print on it
        // and GET /orders/{n} agrees with GET /labels/{n}.
        boolean voided = tracking.map(t -> "VOIDED".equalsIgnoreCase(t.getStatus())).orElse(false);
        String state = voided ? "VOIDED" : (pkgs.isEmpty() ? "PENDING" : "GENERATED");
        // BUG-028 — flag an order as having customs data so the FE can
        // gate the Commercial-invoice button on international orders.
        boolean hasCustoms = orderCustomsRepository != null
                && orderCustomsRepository.findByOrderNoIgnoreCase(String.valueOf(orderNo)).isPresent();
        return Optional.of(new LabelArtifactDTO(
                orderNo,
                state,
                carrier,
                environment,
                tracking.map(OrderTracking::getLabelGeneratedAt).orElse(null),
                packages,
                hasCustoms));
    }

    /**
     * BUG-049 — true when the order's tracking row has been voided.
     * Public so {@link LabelPrintableService} and any other label-byte
     * caller can short-circuit before returning the stored bytes; the
     * stored label stays on disk (audit) but no new copy ships out of
     * the download or print paths once the carrier has reversed it.
     */
    public boolean isVoided(Integer orderNo) {
        if (orderNo == null) return false;
        return orderTrackingRepository.findByOrderNo(orderNo)
                .map(t -> "VOIDED".equalsIgnoreCase(t.getStatus()))
                .orElse(false);
    }

    public Optional<LabelPackage> pkg(Integer orderNo, Integer seq) {
        if (orderNo == null || seq == null || seq < 1) return Optional.empty();
        if (isVoided(orderNo)) return Optional.empty();
        return labelPackageRepository.findByOrderNoAndSequenceNumber(orderNo, seq);
    }

    /** Every package row for the order, in sequence order. */
    public List<LabelPackage> packages(Integer orderNo) {
        if (orderNo == null) return List.of();
        if (isVoided(orderNo)) return List.of();
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
        if (isVoided(orderNo)) return Optional.empty();
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
        // BUG-019 / BUG-024 — pre-"label-package change" UPS orders have
        // format=UNKNOWN on their label_package row even though the
        // stored bytes are legit ZPL / GIF / PNG / PDF. Sniff the magic
        // and dispatch via the known-format branch so artifact endpoints
        // start resolving. Avoids a Flyway backfill: rows the operator
        // actually views self-heal implicitly, legacy rows stay as-is.
        if (!"ZPL".equals(format) && !"GIF".equals(format)
                && !"PNG".equals(format) && !"PDF".equals(format)) {
            String sniffed = sniffFormat(stored);
            if (sniffed != null) format = sniffed;
        }
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

    /** BUG-019 / BUG-024 — magic-byte format detection for legacy rows
     *  whose {@code format} column is {@code UNKNOWN}. Mirrors
     *  {@link com.multiship.backend.service.LabelArtifactResolver#sniffFormat},
     *  which can't be called directly from here (its scope is the live
     *  resolver). Returns null when nothing matches. */
    static String sniffFormat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        int i = 0;
        while (i < bytes.length && (bytes[i] == '\n' || bytes[i] == '\r'
                || bytes[i] == ' ' || bytes[i] == '\t')) i++;
        if (startsWithAt(bytes, MAGIC_ZPL, i)) return "ZPL";
        if (startsWithAt(bytes, MAGIC_PDF, i)) return "PDF";
        if (startsWithAt(bytes, MAGIC_GIF, i)) return "GIF";
        if (startsWithAt(bytes, MAGIC_PNG, i)) return "PNG";
        // USPS writer prepends `^FX … ^FS` before `^XA`; scan the whole
        // buffer. PDF/GIF/PNG magic can only occur at byte 0 so once we
        // reach here only ZPL is possible.
        if (indexOf(bytes, MAGIC_ZPL) >= 0) return "ZPL";
        return null;
    }

    private static final byte[] MAGIC_ZPL = { '^', 'X', 'A' };
    private static final byte[] MAGIC_PDF = { '%', 'P', 'D', 'F', '-' };
    private static final byte[] MAGIC_GIF = { 'G', 'I', 'F', '8' };
    private static final byte[] MAGIC_PNG = { (byte) 0x89, 'P', 'N', 'G' };

    private static boolean startsWithAt(byte[] haystack, byte[] needle, int offset) {
        if (haystack.length - offset < needle.length) return false;
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        int limit = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
