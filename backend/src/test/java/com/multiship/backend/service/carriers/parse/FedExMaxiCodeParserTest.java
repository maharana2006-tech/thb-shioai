package com.multiship.backend.service.carriers.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FedExMaxiCodeParser} against synthetic MaxiCode payloads
 * modelled on live FedEx-returned ZPL (empirically decoded from order
 * 900031 US→IN sandbox). Uses raw ASCII control chars (0x1C/0x1D/0x1E) —
 * these are the actual MaxiCode field separators.
 */
class FedExMaxiCodeParserTest {

    private static final char GS = 0x1D;
    private static final char RS = 0x1E;
    private static final char FS = 0x1C;

    private final FedExMaxiCodeParser parser = new FedExMaxiCodeParser();

    /**
     * Build a MaxiCode envelope shaped like FedEx's — CIN {@code [)>},
     * {@code RS 01}, GS-separated SCM header (postal, country, service,
     * tracking, ...), then a {@code RS 06} extended block with GS-separated
     * fields, then a {@code FS}-separated customs trailer, then a
     * closing {@code RS}. Only the pieces relevant to a given test are
     * varied; unused positions get filler.
     */
    private static String maxiCodeZpl(String postal, String country, String service,
                                       String trackingPlusSvc, String hub,
                                       String line1, String city, String state, String name,
                                       String phone, String line2, String reference, String po,
                                       String customsCountry, String customsValue,
                                       String customsCurrency, String commodity,
                                       String eei) {
        String scm = String.join(String.valueOf(GS),
                postal, country, service, trackingPlusSvc, hub);
        String preRs06 = String.join(String.valueOf(GS), line1, city, state, name);
        StringBuilder ext = new StringBuilder();
        ext.append("06").append(GS);
        if (phone != null)     ext.append("12Z").append(phone).append(GS);
        if (line2 != null)     ext.append("14Z").append(line2).append(GS);
        if (reference != null) ext.append("15Z").append(reference).append(GS);
        if (po != null)        ext.append("K").append(po).append(GS);
        StringBuilder customs = new StringBuilder();
        customs.append(FS);
        if (customsCountry != null)  customs.append(customsCountry);
        customs.append(FS);
        if (customsValue != null)    customs.append(customsValue);
        customs.append(FS);
        if (customsCurrency != null) customs.append(customsCurrency);
        customs.append(FS);
        if (commodity != null)       customs.append(commodity);
        customs.append(FS);
        if (eei != null)             customs.append(eei);

        String payload = "[)>" + RS + "01" + GS + scm + GS + preRs06 + RS + ext + customs + RS;
        // Wrap in a minimal ZPL so the regex has to skip real markup.
        return "^XA\n^BD^FD" + payload + "^FS\n^XZ";
    }

    @Test
    void parseExtractsScmHeaderAndExtendedFields() {
        String zpl = maxiCodeZpl(
                "751024", "356", "01",
                "7948605362440430", "FDE",
                "Duplex-145, Lane-1, Sai Paradise",
                "Bhubaneswar", "OR", "Manoj Kr Maharana",
                "919437129909",
                "Daruthenga, KIIT Road",
                "114064862",
                "MAN900031",
                "US", "2400", "USD", "Cotton T-Shirt", "NO EEI 30.37(a)");

        FedExMaxiCodeParser.Details d = parser.parse(zpl);

        assertTrue(d.isPresent(), "MaxiCode envelope should have been detected");
        assertEquals("FedEx MaxiCode", d.getSource());
        assertEquals("751024", d.getRecipientPostalCode());
        assertEquals("356", d.getRecipientCountryCode());
        assertEquals("794860536244", d.getTrackingNumber(),
                "tracking should be sliced to the 12-digit prefix");
        assertEquals("0430", d.getServiceCode(),
                "SCM service code is the 4-digit suffix");
        assertEquals("Duplex-145, Lane-1, Sai Paradise", d.getRecipientAddressLine1());
        assertEquals("Bhubaneswar", d.getRecipientCity());
        assertEquals("OR", d.getRecipientState());
        assertEquals("Manoj Kr Maharana", d.getRecipientName());
        assertEquals("919437129909", d.getRecipientPhone());
        assertEquals("Daruthenga, KIIT Road", d.getRecipientAddressLine2(),
                "line 2 must be decoded from the E14Z field");
        assertEquals("114064862", d.getReferenceNumber());
        assertEquals("MAN900031", d.getCustomerPo());
        assertEquals("US", d.getCustomsCountryCode());
        assertEquals("2400", d.getCustomsValue());
        assertEquals("USD", d.getCustomsCurrency());
        assertEquals("Cotton T-Shirt", d.getCommodityDescription());
        assertEquals("NO EEI 30.37(a)", d.getEeiStatement());
    }

    @Test
    void parseReturnsEmptyWhenMaxiCodeAbsent() {
        String zplWithoutMaxi = "^XA^FDNo MaxiCode here^FS^XZ";
        FedExMaxiCodeParser.Details d = parser.parse(zplWithoutMaxi);
        assertFalse(d.isPresent(), "no MaxiCode → EMPTY sentinel");
    }

    @Test
    void parseReturnsEmptyOnNullOrBlank() {
        assertFalse(parser.parse(null).isPresent());
        assertFalse(parser.parse("").isPresent());
    }

    @Test
    void parseSkipsMissingOptionalFields() {
        // No phone, no line2, no reference, no PO — verify parser doesn't
        // fabricate; those fields stay null instead of picking up
        // downstream data by mistake.
        String zpl = maxiCodeZpl(
                "10001", "840", "01",
                "1234567890120430", "FDE",
                "1 Main St", "New York", "NY", "Jane Doe",
                null, null, null, null,   // no extended fields
                "US", "100", "USD", "Widget", null);

        FedExMaxiCodeParser.Details d = parser.parse(zpl);
        assertTrue(d.isPresent());
        assertNull(d.getRecipientPhone(), "absent 12Z must not fabricate");
        assertNull(d.getRecipientAddressLine2(), "absent 14Z must not fabricate");
        assertNull(d.getReferenceNumber());
        assertNull(d.getCustomerPo());
        assertNull(d.getEeiStatement());
        // But the fields we DID populate still land correctly.
        assertEquals("1 Main St", d.getRecipientAddressLine1());
        assertEquals("Widget", d.getCommodityDescription());
    }

    @Test
    void trackingShorterThan16DigitsFallsThroughVerbatim() {
        // Not every carrier concatenates SVC into the tracking number.
        // Fall through verbatim rather than mis-slicing.
        String zpl = maxiCodeZpl(
                "10001", "840", "01",
                "SHORT", "FDE",
                "1 Main St", "New York", "NY", "Jane Doe",
                null, null, null, null, null, null, null, null, null);
        FedExMaxiCodeParser.Details d = parser.parse(zpl);
        assertEquals("SHORT", d.getTrackingNumber());
        assertNull(d.getServiceCode(),
                "no 16-digit split → service code stays null");
    }
}
