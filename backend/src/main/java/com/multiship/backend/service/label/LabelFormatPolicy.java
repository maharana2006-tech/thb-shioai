package com.multiship.backend.service.label;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Phase 0 (Shipment &amp; Label re-architecture) — the single place that
 * decides which label format we ask a carrier for, and how a stored
 * artifact's format maps to its provenance.
 *
 * <p>Before this class the default was scattered: the manual request builder
 * dropped it (null reached every connector), UPS defaulted to ZPL, Stamps
 * SWSIM to Auto (a raster), SERA to zpl, DHL to PDF. A null format is what
 * stored USPS/UPS labels as PDF/GIF and forced {@code /label/zpl} to
 * rasterise them into a {@code ^GFA} bitmap.
 *
 * <p>Precedence: per-shipment request override → per-account operator
 * setting → carrier default. The carrier default is ZPL everywhere the
 * carrier can emit it. DHL stays PDF until its ZPL template
 * ({@code ECOM26_84_001}) is validated against a live account; flipping it
 * is a one-line change here, nowhere else.
 */
@Service
public class LabelFormatPolicy {

    public static final String ZPL = "ZPL";
    public static final String PDF = "PDF";

    public static final String SOURCE_NATIVE = "CARRIER_NATIVE";
    public static final String SOURCE_RASTER = "CARRIER_RASTER";
    public static final String SOURCE_UNKNOWN = "UNKNOWN";
    public static final String FORMAT_UNKNOWN = "UNKNOWN";

    /** Carrier default when neither the request nor the account says. */
    private static final Map<String, String> CARRIER_DEFAULT = Map.of(
            "USPS", ZPL,
            "STAMPS", ZPL,
            "STAMPS_COM", ZPL,
            "UPS", ZPL,
            "FEDEX", ZPL,
            "DHL", PDF);

    /**
     * The format to request from the carrier for this shipment.
     *
     * @param carrierCode     e.g. USPS, UPS, FEDEX, DHL (case-insensitive)
     * @param accountOverride {@code CarrierAccountRef.labelImageFormat}, may be null
     * @param requestOverride {@code ShipmentRequestDTO.labelImageFormat}, may be null
     * @return an upper-case format token, never null
     */
    public String requestedFormat(String carrierCode, String accountOverride, String requestOverride) {
        if (StringUtils.hasText(requestOverride)) return norm(requestOverride);
        if (StringUtils.hasText(accountOverride)) return norm(accountOverride);
        return CARRIER_DEFAULT.getOrDefault(norm(carrierCode), ZPL);
    }

    /**
     * Provenance for a stored artifact of the given format. Only real ZPL is
     * native; every image or PDF is a raster the ZPL endpoint must wrap in
     * {@code ^GFA}. Null or UNKNOWN stays UNKNOWN (e.g. an undecodable URL
     * label) rather than being guessed.
     */
    public static String sourceFor(String format) {
        if (format == null) return SOURCE_UNKNOWN;
        String f = norm(format);
        if (ZPL.equals(f)) return SOURCE_NATIVE;
        if (FORMAT_UNKNOWN.equals(f)) return SOURCE_UNKNOWN;
        return SOURCE_RASTER;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
