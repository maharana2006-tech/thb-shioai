package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds a 4x6" thermal label (203 dpi -> 812 x 1218 dots) as raw ZPL, mirroring
 * the real carrier label anatomy (and the on-screen preview): incoterms header,
 * two-column origin/ship block, TO block with inline TEST line, PDF417 + wordmark
 * and service-letter box, routing section (TRK# / URSA / destination), and a
 * bottom Code 128. Sandbox/fallback twin of the carrier-provided artifact.
 */
@Service
@RequiredArgsConstructor
public class ZplLabelService {

    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("ddMMMuu", Locale.US);

    private final CarrierProperties carrierProperties;

    public String buildLabel(OrderWithLinesDTO order,
                             OrderAccountResolutionDTO resolution,
                             OrderResponseDTO.LabelDetails label) {
        CarrierProperties.ShipperDefaults shipper = carrierProperties.getShipper();

        String carrierCode = resolution != null && StringUtils.hasText(resolution.getCarrierCode())
                ? resolution.getCarrierCode()
                : order.getShipviaCd();
        String carrierName = displayCarrier(carrierCode);
        boolean fedex = carrierName.equals("FEDEX");
        boolean usps = carrierName.equals("USPS");
        String tier = fedex ? "Express" : usps ? "Priority" : "Ground";
        String serviceLetter = fedex ? "E" : usps ? "P" : "G";
        String serviceCode = fedex ? "IP EOD" : usps ? "PRI" : "GND";
        String environment = resolution != null && StringUtils.hasText(resolution.getEnvironment())
                ? resolution.getEnvironment().toUpperCase(Locale.ROOT)
                : "SANDBOX";
        boolean sandbox = !"PRODUCTION".equals(environment);

        String trackingNumber = label != null ? label.getTrackingNumber() : null;
        boolean generated = label != null && Boolean.TRUE.equals(label.getIsGenerated())
                && StringUtils.hasText(trackingNumber);

        String shipDate = labelDate(label != null ? label.getGeneratedAt() : null, order.getCreatedDate());
        String zip = zpl(order.getShiptoZip());
        String city = zpl(order.getShiptoCity());
        String state = zpl(order.getShiptoState());
        String cityLetters = city.replaceAll("[^A-Z]", "");
        String airport = cityLetters.substring(0, Math.min(3, cityLetters.length()));
        String ursa = "XQ " + cityLetters.substring(0, Math.min(4, cityLetters.length()));

        // recipient chain matches the on-screen label
        String shipName = order.getShipName() != null ? order.getShipName().trim() : null;
        String recipient = shipName != null && shipName.length() > 2
                ? shipName
                : firstNonBlank(order.getShipAttn(), firstNonBlank(order.getCustNo(), "CONSIGNEE"));

        String formCode = hash36(order.getOrderNo() + zip, 5) + "/" + hash36(zip + order.getOrderNo(), 4)
                + "/" + hash36(zpl(order.getCustNo()) + order.getOrderNo(), 4);
        String meter = "J" + String.format("%09d", order.getOrderNo()) + zip.substring(0, Math.min(3, zip.length())) + "uv";

        String digits = (trackingNumber == null ? "" : trackingNumber).replaceAll("[^0-9]", "");
        digits = (digits + "000000000000").substring(0, 12);
        String trkGrouped = digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12);
        String numericLine = digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 9)
                + " (" + digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6, 10) + ")"
                + " 0 00 " + digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12);

        StringBuilder z = new StringBuilder();
        z.append("^XA\n^CI28\n^PW812\n^LL1218\n^LH0,0\n");

        // ---- incoterms + two-column header ----
        z.append("^CF0,28\n").append(text(24, 18, "INCOTERMS: DAP"));
        z.append("^CF0,24\n");
        z.append(text(30, 56, "ORIGIN ID:" + zpl(shipper.getState()) + zpl(shipper.getPostalCode()).substring(0, Math.min(2, zpl(shipper.getPostalCode()).length())) + "A  " + zpl(shipper.getPhone())));
        z.append(text(30, 86, zpl(shipper.getName())));
        z.append(text(30, 116, zpl(shipper.getAddressLine1())));
        z.append(text(30, 172, zpl(shipper.getCity()) + ", " + zpl(shipper.getState()) + " " + zpl(shipper.getPostalCode()) + " " + zpl(shipper.getCountryCode())));
        z.append(text(30, 202, "SIGN: " + zpl(shipper.getName())));
        z.append("^FO420,50^GB2,180,2^FS\n"); // column divider
        z.append(text(438, 56, "SHIP DATE: " + shipDate));
        z.append(text(438, 86, "ACTWGT: " + (order.getWeight() != null ? order.getWeight() : "-") + " KG"));
        z.append(text(438, 116, "CAD: " + order.getOrderNo() + "/MSHIP1"));
        z.append(text(438, 172, "BILL SENDER"));
        z.append("^CF0,20\n").append(text(438, 202, "NO EEI 30.37(a)"));

        z.append("^FO20,236^GB772,6,6^FS\n");

        // ---- TO block + rotated form code ----
        z.append("^CF0,22\n").append(text(24, 262, "TO"));
        z.append("^CF0,46\n").append(text(72, 258, zpl(recipient)));
        z.append("^CF0,40\n").append(text(72, 312, zpl(firstNonBlank(order.getCustNo(), ""))));
        int y = 360;
        if (StringUtils.hasText(order.getShipAddr1())) {
            z.append(text(72, y, zpl(order.getShipAddr1())));
            y += 48;
        }
        if (sandbox && generated) {
            z.append(text(72, y, "**TEST LABEL - DO NOT SHIP**"));
            y += 48;
        }
        z.append("^CF0,44\n").append(text(72, y, city + " " + state + " " + zip));
        z.append("^FO560,").append(y).append("^FB200,1,0,R,0^FD(US)^FS\n");
        y += 54;
        z.append("^FO770,260^A0R,24,24^FD").append(formCode).append("^FS\n");
        z.append("^CF0,24\n").append(text(30, y, zpl(firstNonBlank(order.getPhone(), ""))));
        y += 30;
        z.append("^CF0,20\n");
        z.append(text(30, y, "INV:")).append(text(400, y, "REF: " + order.getOrderNo()));
        y += 26;
        z.append(text(30, y, "PO:")).append(text(400, y, "DEPT: " + zpl(firstNonBlank(order.getCustNo(), "-"))));
        y += 20;

        z.append("^FO20,").append(y + 6).append("^GB772,2,2^FS\n");
        int barTop = y + 24;

        // ---- PDF417 + wordmark / service letter / meter ----
        if (generated) {
            // Full shipment record in the 2D symbol, like real carrier labels
            // (drives the symbol to the ~3/4-width footprint of the sample).
            String pdf417Data = String.join("|",
                    zpl(trackingNumber), String.valueOf(order.getOrderNo()), zpl(order.getCustNo()),
                    zpl(recipient), city, state, zip, "US", serviceCode, shipDate,
                    String.valueOf(order.getWeight()), zpl(shipper.getPostalCode()), formCode, meter);
            z.append("^BY3\n");
            z.append("^FO36,").append(barTop).append("^B7N,6,5,5,,N^FD")
                    .append(pdf417Data).append("^FS\n");
        } else {
            z.append("^FO36,").append(barTop).append("^GB520,150,2^FS\n");
            z.append("^CF0,24\n").append(text(120, barTop + 66, "2D BARCODE AFTER GENERATION"));
        }
        z.append("^CF0,60\n").append(text(596, barTop, zpl(carrierName.equals("FEDEX") ? "FedEx" : carrierName)));
        z.append("^CF0,28\n").append(text(620, barTop + 62, tier.toUpperCase(Locale.ROOT)));
        z.append("^FO600,").append(barTop + 96).append("^GB118,122,5^FS\n");
        z.append("^FO628,").append(barTop + 108).append("^CF0,96^FD").append(serviceLetter).append("^FS\n");
        z.append("^FO780,").append(barTop).append("^A0R,20,20^FD").append(meter).append("^FS\n");

        int routeTop = barTop + 236;
        z.append("^FO20,").append(routeTop - 10).append("^GB772,2,2^FS\n");

        // ---- routing section ----
        z.append("^FO560,").append(routeTop + 2).append("^FB230,1,0,R,0^CF0,30^FDA1^FS\n");
        z.append("^CF0,26\n").append(text(30, routeTop + 44, "TRK#"));
        z.append("^FO100,").append(routeTop + 36).append("^GB72,36,2^FS\n");
        z.append("^CF0,24\n").append(text(110, routeTop + 44, "0430"));
        z.append("^CF0,40\n").append(text(190, routeTop + 36, generated ? trkGrouped : "PENDING"));
        z.append("^FO560,").append(routeTop + 36).append("^FB230,1,0,R,0^CF0,40^FD").append(serviceCode).append("^FS\n");
        z.append("^CF0,90\n").append(text(28, routeTop + 92, ursa));
        z.append("^FO520,").append(routeTop + 88).append("^FB270,1,0,R,0^CF0,44^FD").append(zip).append("^FS\n");
        z.append("^FO430,").append(routeTop + 140).append("^FB360,1,0,R,0^CF0,30^FD").append(state).append("-US ").append(airport).append("^FS\n");
        if (generated) {
            z.append("^CF0,24\n").append(text(30, routeTop + 190, numericLine));
        }

        // ---- bottom Code 128 (no interpretation line, like the real label) ----
        int bcTop = routeTop + 228;
        if (generated) {
            z.append("^BY3,3,150\n");
            z.append("^FO50,").append(bcTop).append("^BCN,150,N,N,N^FD").append(zpl(trackingNumber)).append("^FS\n");
        } else {
            z.append("^FO50,").append(bcTop).append("^GB700,120,2^FS\n");
            z.append("^CF0,26\n").append(text(160, bcTop + 50, "*** LABEL NOT GENERATED ***"));
        }

        z.append("^XZ\n");
        return z.toString();
    }

    private String text(int x, int y, String value) {
        return "^FO" + x + "," + y + "^FD" + value + "^FS\n";
    }

    /** ZPL field data may not contain the ^ and ~ control characters. */
    private String zpl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('^', ' ').replace('~', ' ').toUpperCase(Locale.ROOT).trim();
    }

    private String hash36(Object value, int length) {
        String s = String.valueOf(value);
        long hash = 7;
        for (int i = 0; i < s.length(); i += 1) {
            hash = (hash * 33 + s.charAt(i)) % 2147483647L;
        }
        String out = Long.toString(hash, 36).toUpperCase(Locale.ROOT);
        while (out.length() < length) {
            out = "0" + out;
        }
        return out.substring(0, length);
    }

    private String labelDate(LocalDateTime generatedAt, LocalDate createdDate) {
        if (generatedAt != null) {
            return generatedAt.format(LABEL_DATE).toUpperCase(Locale.ROOT);
        }
        if (createdDate != null) {
            return createdDate.format(LABEL_DATE).toUpperCase(Locale.ROOT);
        }
        return LocalDate.now().format(LABEL_DATE).toUpperCase(Locale.ROOT);
    }

    private String displayCarrier(String carrierCode) {
        if (!StringUtils.hasText(carrierCode)) {
            return "CARRIER";
        }
        return switch (carrierCode.toUpperCase(Locale.ROOT)) {
            case "P80", "UPS" -> "UPS";
            case "F77", "FEDEX" -> "FEDEX";
            case "L01", "USPS" -> "USPS";
            default -> carrierCode.toUpperCase(Locale.ROOT);
        };
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
