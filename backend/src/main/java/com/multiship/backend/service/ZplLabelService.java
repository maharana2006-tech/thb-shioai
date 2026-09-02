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

    /**
     * Backwards-compatible overload — single-package default when the
     * caller doesn't know a pkg index (older callers, tests).
     */
    public String buildLabel(OrderWithLinesDTO order,
                             OrderAccountResolutionDTO resolution,
                             OrderResponseDTO.LabelDetails label) {
        return buildLabel(order, resolution, label, 1, 1);
    }

    public String buildLabel(OrderWithLinesDTO order,
                             OrderAccountResolutionDTO resolution,
                             OrderResponseDTO.LabelDetails label,
                             int pkgIndex,
                             int pkgCount) {
        return buildLabel(order, resolution, label, pkgIndex, pkgCount, null);
    }

    /**
     * Full signature with per-package overrides. When {@code perPkg} is
     * non-null, its {@code trackingNumber} drives the barcode and its
     * {@code weight} drives the ACTWGT header + footer WT — so a
     * multi-package shipment renders each box's own tracking and weight
     * instead of the master values.
     */
    public String buildLabel(OrderWithLinesDTO order,
                             OrderAccountResolutionDTO resolution,
                             OrderResponseDTO.LabelDetails label,
                             int pkgIndex,
                             int pkgCount,
                             com.multiship.backend.dto.LabelPackageDTO perPkg) {
        CarrierProperties.ShipperDefaults shipper = carrierProperties.getShipper();

        // Pkg count + index clamped once, reused for CAD, filename, and footer.
        int safeCount = Math.max(1, pkgCount);
        int safeIndex = Math.min(Math.max(1, pkgIndex), safeCount);

        // Per-package overrides — tracking + weight for THIS box, falling back
        // to the shipment-level values when we don't have a per-pkg row.
        java.math.BigDecimal effectiveWeight = perPkg != null && perPkg.getWeight() != null
                ? perPkg.getWeight() : order.getWeight();
        String effectiveWeightUnit = perPkg != null && StringUtils.hasText(perPkg.getWeightUnit())
                ? perPkg.getWeightUnit() : null;
        String pkgTrackingOverride = perPkg != null && StringUtils.hasText(perPkg.getTrackingNumber())
                ? perPkg.getTrackingNumber() : null;

        // Carrier for the wordmark comes from the resolution's canonical code;
        // fall back to shipviaCd for the display switch only when resolution
        // is absent (shipviaCd may hold either a legacy carrier code like F77
        // or a full service code like FEDEX_GROUND — displayCarrier tolerates
        // both).
        String carrierCode = resolution != null && StringUtils.hasText(resolution.getCarrierCode())
                ? resolution.getCarrierCode()
                : order.getShipviaCd();
        String carrierName = displayCarrier(carrierCode);
        boolean fedex = carrierName.equals("FEDEX");
        boolean usps = carrierName.equals("USPS");

        // Tier / letter / service-code come from the ORDER's selected service
        // (order.shipviaCd stores the service code assigned during resolution —
        // FEDEX_GROUND, GROUND_HOME_DELIVERY, UPS_NEXT_DAY_AIR, etc.). The
        // previous hardcode ("Express" for every FedEx label) mis-rendered
        // Ground shipments as Express in the sandbox artifact.
        String[] rendered = renderServiceTier(order.getShipviaCd(), carrierName);
        String tier = rendered[0];
        String serviceLetter = rendered[1];
        String serviceCode = rendered[2];
        String environment = resolution != null && StringUtils.hasText(resolution.getEnvironment())
                ? resolution.getEnvironment().toUpperCase(Locale.ROOT)
                : "SANDBOX";
        boolean sandbox = !"PRODUCTION".equals(environment);

        String trackingNumber = pkgTrackingOverride != null ? pkgTrackingOverride
                : (label != null ? label.getTrackingNumber() : null);
        boolean generated = label != null && Boolean.TRUE.equals(label.getIsGenerated())
                && StringUtils.hasText(trackingNumber);

        String shipDate = labelDate(label != null ? label.getGeneratedAt() : null, order.getCreatedDate());
        String zip = zpl(order.getShiptoZip());
        String city = zpl(order.getShiptoCity());
        String state = zpl(order.getShiptoState());
        String cityLetters = city.replaceAll("[^A-Z]", "");
        String airport = cityLetters.substring(0, Math.min(3, cityLetters.length()));
        String ursa = "XQ " + cityLetters.substring(0, Math.min(4, cityLetters.length()));

        // recipient chain matches the on-screen label — NEVER falls back
        // to custNo (client code), which would render the tenant identifier
        // as the parcel's addressee. Fallback is a literal placeholder so a
        // mis-populated shipment is visibly broken instead of silently
        // shipping to "DES840".
        String shipName = order.getShipName() != null ? order.getShipName().trim() : null;
        String recipient = shipName != null && shipName.length() > 2
                ? shipName
                : firstNonBlank(order.getShipAttn(), "-");

        String formCode = hash36(order.getOrderNo() + zip, 5) + "/" + hash36(zip + order.getOrderNo(), 4)
                + "/" + hash36(zpl(order.getCustNo()) + order.getOrderNo(), 4);
        String meter = "J" + String.format("%09d", order.getOrderNo()) + zip.substring(0, Math.min(3, zip.length())) + "uv";

        // Domestic vs international — same customs territory (EU intra, EAEU
        // intra, GCC, SACU) counts as domestic so we don't print export
        // paperwork on parcels that don't cross a customs boundary.
        String originCountryCode = shipper.getCountryCode() == null ? "" : shipper.getCountryCode().trim();
        String destCountryCode = order.getShiptoCountryCd() == null ? "" : order.getShiptoCountryCd().trim();
        boolean crossBorder = !destCountryCode.isEmpty() && !originCountryCode.isEmpty()
                && !com.multiship.backend.util.CustomsTerritories.sameTerritory(originCountryCode, destCountryCode);
        boolean usExport = crossBorder && "US".equalsIgnoreCase(originCountryCode);

        String digits = (trackingNumber == null ? "" : trackingNumber).replaceAll("[^0-9]", "");
        digits = (digits + "000000000000").substring(0, 12);
        String trkGrouped = digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12);
        String numericLine = digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 9)
                + " (" + digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6, 10) + ")"
                + " 0 00 " + digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12);

        StringBuilder z = new StringBuilder();
        z.append("^XA\n^CI28\n^PW812\n^LL1218\n^LH0,0\n");

        // ---- (incoterms) + two-column header ----
        // Incoterms are a cross-border commercial term; domestic parcels
        // don't declare them. Only render for shipments crossing a customs
        // boundary.
        if (crossBorder) {
            z.append("^CF0,28,28\n").append(text(24, 18, "INCOTERMS: DAP"));
        }
        z.append("^CF0,24,24\n");
        z.append(text(30, 56, "ORIGIN ID:" + zpl(shipper.getState()) + zpl(shipper.getPostalCode()).substring(0, Math.min(2, zpl(shipper.getPostalCode()).length())) + "A  " + zpl(shipper.getPhone())));
        z.append(text(30, 86, zpl(shipper.getName())));
        z.append(text(30, 116, zpl(shipper.getAddressLine1())));
        z.append(text(30, 172, zpl(shipper.getCity()) + ", " + zpl(shipper.getState()) + " " + zpl(shipper.getPostalCode()) + " " + zpl(shipper.getCountryCode())));
        z.append(text(30, 202, "SIGN: " + zpl(shipper.getName())));
        z.append("^FO420,50^GB2,180,2^FS\n"); // column divider
        z.append(text(438, 56, "SHIP DATE: " + shipDate));
        z.append(text(438, 86, "ACTWGT: " + (effectiveWeight != null ? effectiveWeight : "-")
                + " " + (effectiveWeightUnit != null ? effectiveWeightUnit.toUpperCase(Locale.ROOT) : "KG")));
        // CAD (Customer Automation Device) reference — one per box so
        // warehouse pickers can distinguish scans within a multi-package
        // shipment (e.g. 900002-2/MSHIP1 for box 2). Single-package
        // shipments render without the -N suffix to match the legacy shape.
        // Sprint 48 B10: use the customer-facing displayOrderNo (MAN prefix
        // for manual shipments) — falls back to the raw integer when the
        // DTO wasn't populated with it (tests, older callers).
        String orderDisplay = StringUtils.hasText(order.getDisplayOrderNo())
                ? order.getDisplayOrderNo() : String.valueOf(order.getOrderNo());
        String cadSuffix = safeCount > 1 ? "-" + safeIndex : "";
        z.append(text(438, 116, "CAD: " + orderDisplay + cadSuffix + "/MSHIP1"));
        z.append(text(438, 172, "BILL SENDER"));
        // EEI (Electronic Export Information) exemption text is only relevant
        // when the parcel exports from the US. Domestic + non-US shipments
        // don't need it.
        if (usExport) {
            z.append("^CF0,20,20\n").append(text(438, 202, "NO EEI 30.37(a)"));
        }

        z.append("^FO20,236^GB772,6,6^FS\n");

        // ---- TO block + rotated form code ----
        // (custNo used to render here at Y=312, which read like part of the
        //  shipping address to the recipient. Moved to the footer block below
        //  where it belongs as a warehouse-facing routing hint.)
        z.append("^CF0,22,22\n").append(text(24, 262, "TO"));
        z.append("^CF0,46,46\n").append(text(72, 258, zpl(recipient)));
        int y = 320;
        if (StringUtils.hasText(order.getShipAddr1())) {
            z.append(text(72, y, zpl(order.getShipAddr1())));
            y += 48;
        }
        if (sandbox && generated) {
            z.append(text(72, y, "**TEST LABEL - DO NOT SHIP**"));
            y += 48;
        }
        z.append("^CF0,44,44\n").append(text(72, y, city + " " + state + " " + zip));
        // Destination country tag: use the actual destination (was hardcoded
        // '(US)'). Suppress for domestic since the country is implicit.
        if (crossBorder && !destCountryCode.isEmpty()) {
            z.append("^FO560,").append(y).append("^FB200,1,0,R,0^FD(")
                    .append(destCountryCode.toUpperCase(Locale.ROOT)).append(")^FS\n");
        }
        y += 54;
        z.append("^FO770,260^A0R,24,24^FD").append(formCode).append("^FS\n");
        z.append("^CF0,24,24\n").append(text(30, y, zpl(firstNonBlank(order.getPhone(), ""))));
        y += 30;
        z.append("^CF0,20,20\n");
        z.append(text(30, y, "INV:")).append(text(400, y, "REF: " + orderDisplay));
        y += 26;
        // PO/DEPT line intentionally removed: PO isn't captured on the Order
        // yet, and DEPT was rendering the client code inside the TO block —
        // moved to the warehouse footer where it belongs.

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
            z.append("^CF0,24,24\n").append(text(120, barTop + 66, "2D BARCODE AFTER GENERATION"));
        }
        z.append("^CF0,60,60\n").append(text(596, barTop, zpl(carrierName.equals("FEDEX") ? "FedEx" : carrierName)));
        z.append("^CF0,28,28\n").append(text(620, barTop + 62, tier.toUpperCase(Locale.ROOT)));
        z.append("^FO600,").append(barTop + 96).append("^GB118,122,5^FS\n");
        z.append("^FO628,").append(barTop + 108).append("^CF0,96,96^FD").append(serviceLetter).append("^FS\n");
        z.append("^FO780,").append(barTop).append("^A0R,20,20^FD").append(meter).append("^FS\n");

        int routeTop = barTop + 236;
        z.append("^FO20,").append(routeTop - 10).append("^GB772,2,2^FS\n");

        // ---- routing section ----
        z.append("^FO560,").append(routeTop + 2).append("^FB230,1,0,R,0^CF0,30,30^FDA1^FS\n");
        z.append("^CF0,26,26\n").append(text(30, routeTop + 44, "TRK#"));
        z.append("^FO100,").append(routeTop + 36).append("^GB72,36,2^FS\n");
        z.append("^CF0,24,24\n").append(text(110, routeTop + 44, "0430"));
        z.append("^CF0,40,40\n").append(text(190, routeTop + 36, generated ? trkGrouped : "PENDING"));
        z.append("^FO560,").append(routeTop + 36).append("^FB230,1,0,R,0^CF0,40,40^FD").append(serviceCode).append("^FS\n");
        z.append("^CF0,90,90\n").append(text(28, routeTop + 92, ursa));
        z.append("^FO520,").append(routeTop + 88).append("^FB270,1,0,R,0^CF0,44,44^FD").append(zip).append("^FS\n");
        z.append("^FO430,").append(routeTop + 140).append("^FB360,1,0,R,0^CF0,30,30^FD").append(state).append("-US ").append(airport).append("^FS\n");
        if (generated) {
            z.append("^CF0,24,24\n").append(text(30, routeTop + 190, numericLine));
        }

        // ---- warehouse-facing footer (two lines just above the Code128) ----
        // Compact routing/audit info for the picker/packer. Kept off the TO
        // block so it doesn't get read as part of the shipping address.
        int footerTop = routeTop + 216;
        z.append("^FO20,").append(footerTop - 8).append("^GB772,2,2^FS\n");
        z.append("^CF0,22,22\n");
        String weightStr = effectiveWeight != null ? effectiveWeight.toPlainString() : "-";
        String weightUnitStr = effectiveWeightUnit != null ? effectiveWeightUnit.toUpperCase(Locale.ROOT) : "KG";
        String tierLabel = tier.toUpperCase(Locale.ROOT);
        // Line 1: client · order · pkg · ship date
        String footerLine1 = "CLIENT: " + zpl(firstNonBlank(order.getCustNo(), "-"))
                + " · ORDER: " + orderDisplay
                + (order.getOrderSuffix() != null && order.getOrderSuffix() != 0
                        ? "-" + order.getOrderSuffix() : "")
                + " · PKG " + safeIndex + " OF " + safeCount
                + " · " + shipDate;
        z.append(text(24, footerTop + 4, footerLine1));
        // Line 2: service · weight · ref (WMS order # when present)
        String footerLine2 = "SVC: " + tierLabel + " (" + zpl(firstNonBlank(order.getShipviaCd(), "-")) + ")"
                + " · WT: " + weightStr + " " + weightUnitStr
                + (StringUtils.hasText(order.getTenantId())
                        ? " · TENANT: " + zpl(order.getTenantId()) : "");
        z.append(text(24, footerTop + 30, footerLine2));

        // ---- bottom Code 128 (no interpretation line, like the real label) ----
        int bcTop = footerTop + 60;
        if (generated) {
            z.append("^BY3,3,120\n");
            z.append("^FO50,").append(bcTop).append("^BCN,120,N,N,N^FD").append(zpl(trackingNumber)).append("^FS\n");
        } else {
            z.append("^FO50,").append(bcTop).append("^GB700,90,2^FS\n");
            z.append("^CF0,26,26\n").append(text(160, bcTop + 34, "*** LABEL NOT GENERATED ***"));
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

    /**
     * Map an order's selected service code to the tier / service-letter /
     * service-code triplet rendered on the label. Ordering matters — most
     * specific matches first (e.g. GROUND_HOME_DELIVERY before GROUND).
     * Fallback per carrier: FedEx → Express, UPS/USPS → Ground/Priority.
     */
    static String[] renderServiceTier(String serviceCode, String carrierName) {
        String s = serviceCode == null ? "" : serviceCode.toUpperCase(Locale.ROOT);

        // FedEx
        if (s.contains("GROUND_HOME_DELIVERY") || s.contains("HOME_DELIVERY"))
            return new String[]{"HOME", "H", "HD"};
        if (s.contains("FEDEX_GROUND") || (s.equals("GROUND") && "FEDEX".equals(carrierName)))
            return new String[]{"GROUND", "G", "GND"};
        if (s.contains("FIRST_OVERNIGHT"))
            return new String[]{"FIRST OVERNIGHT", "F", "1ST ON"};
        if (s.contains("PRIORITY_OVERNIGHT"))
            return new String[]{"PRIORITY OVERNIGHT", "P", "PRI ON"};
        if (s.contains("STANDARD_OVERNIGHT"))
            return new String[]{"STANDARD OVERNIGHT", "S", "STD ON"};
        if (s.contains("FEDEX_2_DAY_AM"))
            return new String[]{"2DAY AM", "2", "2DAY AM"};
        if (s.contains("FEDEX_2_DAY"))
            return new String[]{"2DAY", "2", "2DAY"};
        if (s.contains("EXPRESS_SAVER"))
            return new String[]{"EXPRESS SAVER", "X", "EX SVR"};
        if (s.contains("INTERNATIONAL_PRIORITY_EXPRESS"))
            return new String[]{"INTL PRI EXPRESS", "I", "IPE"};
        if (s.contains("INTERNATIONAL_PRIORITY"))
            return new String[]{"INTL PRIORITY", "I", "IP EOD"};
        if (s.contains("INTERNATIONAL_ECONOMY"))
            return new String[]{"INTL ECONOMY", "I", "IE"};
        if (s.contains("INTERNATIONAL_FIRST"))
            return new String[]{"INTL FIRST", "I", "IF"};

        // UPS
        if (s.contains("NEXT_DAY_AIR_SAVER"))
            return new String[]{"NEXT DAY SAVER", "N", "ND SVR"};
        if (s.contains("NEXT_DAY_AIR_EARLY"))
            return new String[]{"NEXT DAY EARLY", "N", "ND EARLY"};
        if (s.contains("NEXT_DAY_AIR"))
            return new String[]{"NEXT DAY", "N", "ND AIR"};
        if (s.contains("2ND_DAY_AIR_AM") || s.contains("SECOND_DAY_AIR_AM"))
            return new String[]{"2ND DAY AM", "2", "2ND AM"};
        if (s.contains("2ND_DAY_AIR") || s.contains("SECOND_DAY_AIR"))
            return new String[]{"2ND DAY", "2", "2ND AIR"};
        if (s.contains("3_DAY_SELECT") || s.contains("THREE_DAY_SELECT"))
            return new String[]{"3 DAY SELECT", "3", "3 DAY"};
        if (s.contains("WORLDWIDE_EXPRESS_PLUS"))
            return new String[]{"WW EXPRESS PLUS", "W", "WW EX+"};
        if (s.contains("WORLDWIDE_EXPRESS"))
            return new String[]{"WW EXPRESS", "W", "WW EX"};
        if (s.contains("WORLDWIDE_SAVER"))
            return new String[]{"WW SAVER", "W", "WW SVR"};
        if (s.contains("WORLDWIDE_EXPEDITED"))
            return new String[]{"WW EXPEDITED", "W", "WW EXP"};
        if (s.contains("STANDARD"))
            return new String[]{"STANDARD", "S", "STD"};
        if (s.contains("UPS_GROUND") || (s.equals("GROUND") && "UPS".equals(carrierName)))
            return new String[]{"GROUND", "G", "GND"};

        // USPS
        if (s.contains("PRIORITY_MAIL_EXPRESS") || s.contains("EXPRESS_MAIL"))
            return new String[]{"PRI EXPRESS", "E", "PRI EX"};
        if (s.contains("PRIORITY_MAIL") || s.contains("PRIORITY"))
            return new String[]{"PRIORITY", "P", "PRI"};
        if (s.contains("FIRST_CLASS"))
            return new String[]{"FIRST CLASS", "F", "FIRST"};
        if (s.contains("PARCEL_SELECT"))
            return new String[]{"PARCEL SELECT", "P", "PS"};
        if (s.contains("MEDIA_MAIL"))
            return new String[]{"MEDIA MAIL", "M", "MEDIA"};

        // Per-carrier fallback when nothing matches
        return switch (carrierName == null ? "" : carrierName) {
            case "FEDEX" -> new String[]{"EXPRESS", "E", "IP EOD"};
            case "USPS" -> new String[]{"PRIORITY", "P", "PRI"};
            default -> new String[]{"GROUND", "G", "GND"};
        };
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
