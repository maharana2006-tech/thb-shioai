package com.multiship.backend.service.carriers.parse;

import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the MaxiCode 2D-barcode payload embedded in FedEx-returned ZPL
 * so operators can see exactly which fields FedEx received + confirmed —
 * independent of what our platform's UI renders from local Order data.
 *
 * <p>MaxiCode layout (SCM Class 0 mode 2/3, FedEx variant):
 * <pre>
 *   [)&gt;{RS}01{GS}yyPOSTAL{GS}CCC{GS}SVC{GS}TRACKING+SVCCODE{GS}HUB{GS}...
 *   {GS}line1 {GS}city {GS}state {GS}name {RS}06{GS}FIELDS...
 * </pre>
 * Field separators: {GS} = 0x1D (Group Separator), {RS} = 0x1E (Record
 * Separator), {FS} = 0x1C (Field Separator).
 *
 * <p>The Version-06 extended fields carry the interesting bits — the
 * ones FedEx accepted but doesn't render as visible text on the thermal
 * label. Key codes (empirically decoded from live FedEx sandbox output):
 * <ul>
 *   <li>{@code 12Z} — recipient phone</li>
 *   <li>{@code 14Z} — recipient address line 2</li>
 *   <li>{@code 15Z} — reference #</li>
 *   <li>{@code K}   — customer PO</li>
 * </ul>
 * The FS-separated trailer holds customs metadata (country, value,
 * currency, commodity description, EEI statement).
 *
 * <p>Design note: this parser is best-effort. FedEx's own docs on the
 * expanded MaxiCode fields are terse; when a field is absent from the
 * payload the DTO leaves it null (never fabricates). Callers should
 * present the DTO as "these came from the carrier response" so absent
 * fields don't get confused with "we didn't send that".
 */
@Component
public class FedExMaxiCodeParser {

    /** ASCII Group Separator (0x1D) — MaxiCode field delimiter. */
    private static final char GS = 0x1D;
    /** ASCII Record Separator (0x1E) — MaxiCode block delimiter. */
    private static final char RS = 0x1E;
    /** ASCII Field Separator (0x1C) — FedEx customs-trailer delimiter. */
    private static final char FS = 0x1C;

    /**
     * Match the whole MaxiCode payload embedded in a ZPL block. FedEx
     * emits it after the CIN {@code [)&gt;} + {@code RS 01}. The payload
     * lives inside a {@code ^BD} field data ({@code ^FD...^FS}); we
     * capture up to the {@code ^FS} terminator. Non-greedy so multiple
     * MaxiCode fields in one ZPL each get their own match. Anchoring on
     * the printable {@code ^FS} marker (rather than the internal RS
     * 0x1E) is safer because MaxiCode payloads contain multiple RS bytes
     * as internal block delimiters — RS on its own is not a payload end.
     */
    private static final Pattern MAXICODE_ENVELOPE = Pattern.compile(
            "\\[\\)>" + RS + "01" + GS + "(.+?)\\^FS",
            Pattern.DOTALL);

    /**
     * Zebra hex-escape sequence — {@code _XX} where XX is 2 hex digits.
     * FedEx uses this to encode the MaxiCode's ASCII control chars
     * (GS 0x1D, RS 0x1E, FS 0x1C) as printable text; the printer
     * converts them to raw bytes at print time. We have to undo this
     * escaping first, otherwise the MaxiCode looks like inert text
     * to the regex.
     */
    private static final Pattern ZEBRA_HEX_ESCAPE = Pattern.compile("_([0-9A-F]{2})");

    /**
     * Parse a ZPL text blob into a {@link Details} view. Returns
     * {@link Details#EMPTY} when no MaxiCode payload is found
     * (never throws — carriers that don't emit MaxiCode still get a
     * valid DTO). Callers can check {@link Details#getSource()} to
     * confirm the parse succeeded.
     */
    public Details parse(String zpl) {
        if (zpl == null || zpl.isEmpty()) return Details.EMPTY;
        // Undo Zebra's _XX hex-escape encoding first — the MaxiCode
        // payload lives in ZPL as ASCII-safe text and its GS/RS/FS
        // separators come across as "_1D" / "_1E" / "_1C" until we
        // decode them into their real control-char equivalents.
        zpl = decodeZebraHexEscapes(zpl);
        Matcher m = MAXICODE_ENVELOPE.matcher(zpl);
        if (!m.find()) return Details.EMPTY;
        String payload = m.group(1);
        // First split: {RS} demarcates the SCM header vs. the version-06
        // extended block. The extended block is where line 2 lives.
        int rsExtended = payload.indexOf(RS);
        String scmHeader = rsExtended >= 0 ? payload.substring(0, rsExtended) : payload;
        String extendedBlock = rsExtended >= 0 ? payload.substring(rsExtended + 1) : "";

        // SCM header — one big flat GS-separated field list. Empirically
        // decoded from live FedEx sandbox (order 900031 US→IN):
        //   [0] SCM version prefix + postal (e.g. "02 751024")
        //   [1] country ISO-3166 numeric
        //   [2] service class
        //   [3] tracking (12 digits) + service code (4 digits) concatenated
        //   [4] hub code
        //   [5..N-5] variable SCM slots (weight, pkg seq, tenant flags — carrier-specific)
        //   [N-4..N-1] recipient line1, city, state, name  ← always last 4
        // We anchor on the tail so future SCM-slot additions from FedEx
        // don't shift the address fields.
        String[] scm = scmHeader.split(String.valueOf(GS));
        String postalRaw = safe(scm, 0);
        // Peel the SCM version prefix (usually 2 chars) off the postal
        // field. Recognised shape: 2 digits + space + 4-10 char postal.
        String postal = postalRaw;
        if (postalRaw != null && postalRaw.matches("^\\d{2}\\s.+")) {
            postal = postalRaw.substring(3);
        }
        String country  = safe(scm, 1);
        String service  = safe(scm, 2);
        String tracking = safe(scm, 3);
        // Tracking is often "TRACKING+SVCCODE" concatenated (12+4 digits);
        // split so the pure tracking number is 12 digits and the trailing
        // 4-digit service code lives in its own slot.
        String pureTracking = null;
        String scmSvcCode = null;
        if (tracking != null && tracking.matches("\\d{16}")) {
            pureTracking = tracking.substring(0, 12);
            scmSvcCode = tracking.substring(12);
        } else if (tracking != null) {
            pureTracking = tracking;
        }

        // Address tail — last 4 tokens are line1/city/state/name.
        String line1 = null, city = null, state = null, name = null;
        if (scm.length >= 4) {
            line1 = scm[scm.length - 4];
            city  = scm[scm.length - 3];
            state = scm[scm.length - 2];
            name  = scm[scm.length - 1];
        }

        // Extended block — split on {GS}, then walk each token looking
        // for its FedEx code prefix. Anything we don't recognise is
        // silently dropped (not fabricated into a random field).
        String phone = null, line2 = null, referenceNo = null, customerPo = null;
        String extRest = extendedBlock;

        // Post-{RS 06} extended fields — one per GS-separated token.
        // Each token starts with a code (e.g. "14Z", "12Z", "K...").
        // FS-separated trailer starts with the country code.
        // Split on FS first to peel the customs trailer off.
        String eeiStatement = null, commodityDescription = null;
        String customsCountry = null, customsValue = null, customsCurrency = null;
        int fsAt = extRest.indexOf(FS);
        String extFields = fsAt >= 0 ? extRest.substring(0, fsAt) : extRest;
        String customsTrailer = fsAt >= 0 ? extRest.substring(fsAt + 1) : "";
        // Peel version-06 token prefix off if present (starts with "06{GS}").
        if (extFields.startsWith("06" + GS)) {
            extFields = extFields.substring(3);
        }
        for (String token : extFields.split(String.valueOf(GS))) {
            if (token.isEmpty()) continue;
            if (token.startsWith("12Z"))       phone = token.substring(3);
            else if (token.startsWith("14Z"))  line2 = token.substring(3);
            else if (token.startsWith("15Z"))  referenceNo = token.substring(3);
            else if (token.startsWith("K"))    customerPo = token.substring(1);
        }
        // Customs trailer: country{FS}value{FS}currency{FS}commodity{FS}eei...
        String[] customs = customsTrailer.split(String.valueOf(FS));
        customsCountry       = safe(customs, 0);
        customsValue         = safe(customs, 1);
        customsCurrency      = safe(customs, 2);
        commodityDescription = safe(customs, 3);
        eeiStatement         = safe(customs, 4);

        return Details.builder()
                .source("FedEx MaxiCode")
                .trackingNumber(pureTracking)
                .serviceCode(scmSvcCode)
                .service(service)
                .recipientName(nullIfBlank(name))
                .recipientAddressLine1(nullIfBlank(line1))
                .recipientAddressLine2(nullIfBlank(line2))
                .recipientCity(nullIfBlank(city))
                .recipientState(nullIfBlank(state))
                .recipientPostalCode(nullIfBlank(postal))
                .recipientCountryCode(nullIfBlank(country))
                .recipientPhone(nullIfBlank(phone))
                .referenceNumber(nullIfBlank(referenceNo))
                .customerPo(nullIfBlank(customerPo))
                .customsCountryCode(nullIfBlank(customsCountry))
                .customsValue(nullIfBlank(customsValue))
                .customsCurrency(nullIfBlank(customsCurrency))
                .commodityDescription(nullIfBlank(commodityDescription))
                .eeiStatement(nullIfBlank(eeiStatement))
                .build();
    }

    private static String safe(String[] arr, int idx) {
        return idx >= 0 && idx < arr.length ? arr[idx] : null;
    }

    /**
     * Replace every {@code _XX} Zebra hex-escape with the corresponding
     * single-byte character. Idempotent-safe: leaves stray underscores
     * (not followed by 2 hex digits) intact. Package-visible for direct
     * unit-test coverage of the escape decoding.
     */
    static String decodeZebraHexEscapes(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = ZEBRA_HEX_ESCAPE.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int lastEnd = 0;
        while (m.find()) {
            out.append(s, lastEnd, m.start());
            int codePoint = Integer.parseInt(m.group(1), 16);
            out.append((char) codePoint);
            lastEnd = m.end();
        }
        out.append(s, lastEnd, s.length());
        return out.toString();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * Structured view of every field decoded from the carrier's MaxiCode
     * payload. Nulls are meaningful: they indicate the payload didn't
     * contain that field. UI callers should render each field with a
     * "from carrier response" attribution so operators can trust the
     * value came from FedEx and not our local Order data.
     */
    @Value
    @Builder
    public static class Details {
        /** Human-facing tag naming the payload source. */
        String source;
        String trackingNumber;
        String serviceCode;
        String service;
        String recipientName;
        String recipientAddressLine1;
        String recipientAddressLine2;
        String recipientCity;
        String recipientState;
        String recipientPostalCode;
        String recipientCountryCode;
        String recipientPhone;
        String referenceNumber;
        String customerPo;
        String customsCountryCode;
        String customsValue;
        String customsCurrency;
        String commodityDescription;
        String eeiStatement;

        /** Empty singleton for the "no MaxiCode payload found" case. */
        public static final Details EMPTY = Details.builder().source(null).build();

        /** True when a MaxiCode payload was parsed (source is non-null). */
        public boolean isPresent() {
            return source != null;
        }
    }
}
