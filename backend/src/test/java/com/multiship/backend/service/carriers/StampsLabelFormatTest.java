package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SWSIM {@code CreateIndicium} label format. The carrier gives us native
 * ZPL only when we ask for it; omitting {@code <ImageType>} makes SWSIM
 * fall back to Auto (a PNG/GIF/PDF raster), leaving no native ZPL — which
 * forces {@code /label/zpl} to rasterise the label into a giant {@code ^GFA}
 * bitmap. SWSIM's ImageType enum DOES include Zpl (developer.stamps.com
 * SWS/IM reference), so we default to it, matching
 * {@code DEFAULT_LABEL_IMAGE_FORMAT} and the SERA path.
 */
class StampsLabelFormatTest {

    private static ShipmentRequestDTO uspsRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("A12345")
                .serviceType("GROUND_ADVANTAGE")
                .packageType("PACKAGE")
                .weight(new BigDecimal("1.5"))
                .weightUnit("LB")
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("10 Main St")
                .recipientCity("Austin").recipientState("TX")
                .recipientPostalCode("78701").recipientCountryCode("US")
                .referenceNumber("PO-1")
                .build();
    }

    private static String envelope(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod(
                "buildCreateIndiciumEnvelope", ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-TOKEN");
    }

    @Test
    void blankFormatDefaultsToZplNotAutoRaster() throws Exception {
        // No labelImageFormat on the request or account → the connector must
        // still request ZPL so the carrier returns native thermal markup.
        String soap = envelope(uspsRequest());
        assertTrue(soap.contains("<ImageType>Zpl</ImageType>"),
                "Blank format must emit <ImageType>Zpl</ImageType>, not omit it (which SWSIM reads as Auto → raster). SOAP:\n" + soap);
    }

    @Test
    void explicitPdfOverrideStillHonoured() throws Exception {
        ShipmentRequestDTO r = uspsRequest();
        r.setLabelImageFormat("PDF");
        String soap = envelope(r);
        assertTrue(soap.contains("<ImageType>Pdf</ImageType>"),
                "An explicit PDF override must still map to <ImageType>Pdf</ImageType>");
    }

    @Test
    void explicitZplOverrideMapsToPascalCase() throws Exception {
        ShipmentRequestDTO r = uspsRequest();
        r.setLabelImageFormat("ZPL");
        String soap = envelope(r);
        assertTrue(soap.contains("<ImageType>Zpl</ImageType>"),
                "ZPL must map to SWSIM's PascalCase enum value Zpl");
    }
}
