package com.multiship.backend.service.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Phase 2c — walks the TemplateLayout tree and emits ZPL II (Zebra
 * Programming Language) for thermal shipping-label printers. Same layout
 * source as {@link TemplateHtmlRenderer} and {@link TemplatePdfRenderer};
 * a template built once in the drag-drop editor renders across all three.
 *
 * <p>Dot math:
 * <ul>
 *   <li>Zebra printers are addressed in dots. 203 dpi (the default 4×6"
 *       shipping-label printer) = 8 dots/mm; 300 dpi = 11.811 dots/mm.</li>
 *   <li>Everything in the layout is authored in mm — we round to the
 *       nearest dot per axis. Sub-mm precision is meaningless on a
 *       thermal head anyway.</li>
 * </ul>
 *
 * <p>Coordinate system matches ZPL native (origin top-left, Y grows
 * downward) so no flip is needed — but callers should note that the
 * template layout page dimensions default to A4 for the HTML/PDF paths,
 * whereas shipping labels are usually 100×152mm (4×6"). If the layout
 * doesn't specify page dimensions, we assume 4×6" so the output prints
 * on the standard shipping stock without truncation.
 *
 * <p>Barcode/QR are rendered as real ZPL fields (^BC / ^BQ) — this is
 * the whole point of ZPL over PDF, so the placeholder branch used for
 * the PDF preview never applies here.
 *
 * <p>Logo images are intentionally deferred: encoding a raster into
 * ^GFA is a chunky feature and most shipping-label templates use text
 * headers rather than logos. Rendered as a labelled placeholder for
 * now; a follow-up can add PDFBox-backed image → ^GFA encoding when
 * the demand is real.
 */
public final class TemplateZplRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Default thermal head resolution. Callers can override via {@link #render(String, Map, int)}. */
    public static final int DEFAULT_DPI = 203;

    private TemplateZplRenderer() { /* static utility */ }

    /** Convenience — default DPI. */
    public static String render(String layoutJson, Map<String, Object> context) {
        return render(layoutJson, context, DEFAULT_DPI);
    }

    /**
     * Render a TemplateLayout JSON blob into a ZPL string ready to send to
     * a Zebra-compatible printer.
     *
     * @param layoutJson persisted JSON — nulls/blank produce a minimal
     *                   placeholder label so callers never have to handle
     *                   an empty string.
     * @param context    binding context, same shape as the HTML / PDF renderers.
     * @param dpi        printer resolution (203 or 300). Other values are
     *                   accepted but the math still works — 8 dots/mm at 203,
     *                   11.811 at 300, linear elsewhere.
     */
    public static String render(String layoutJson, Map<String, Object> context, int dpi) {
        double dotsPerMm = dpi / 25.4;
        StringBuilder z = new StringBuilder(2048);
        if (layoutJson == null || layoutJson.isBlank()) {
            return placeholderLabel("This template hasn't been laid out yet.", dotsPerMm);
        }
        JsonNode root;
        try {
            root = JSON.readTree(layoutJson);
        } catch (Exception e) {
            return placeholderLabel("Layout JSON couldn't be parsed: " + e.getMessage(), dotsPerMm);
        }
        // Shipping labels default to 4×6" (100×152mm) — the standard
        // thermal stock. The HTML/PDF renderers default to A4, but a
        // shipping template that forgot to set page.widthMm would blow
        // out the head width on a 4×6 printer.
        int pageWidthMm  = root.path("page").path("widthMm").asInt(100);
        int pageHeightMm = root.path("page").path("heightMm").asInt(152);
        int marginMm     = root.path("page").path("marginMm").asInt(0);
        int printWidthDots = mm(pageWidthMm, dotsPerMm);
        int labelLengthDots = mm(pageHeightMm, dotsPerMm);

        z.append("^XA\n");
        // ^PW sets the print width so the printer knows the label's usable
        // area — clips content beyond it rather than wrapping to the next
        // label. ^LL sets the label length for cut/tear alignment. ^LH
        // stays at (0,0); coordinates are absolute so no home offset is
        // needed.
        z.append("^PW").append(printWidthDots).append('\n');
        z.append("^LL").append(labelLengthDots).append('\n');
        z.append("^LH0,0\n");
        z.append("^CI28\n"); // UTF-8 encoding for extended chars

        JsonNode blocks = root.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty()) {
            z.append(centeredText("No blocks yet — add some in the builder.",
                    pageWidthMm, pageHeightMm, dotsPerMm));
        } else {
            // Positioned blocks first (author order), then any legacy
            // unpositioned blocks flow top-down. Mirrors HTML/PDF two-pass
            // strategy so all three renderers layer content the same way.
            for (JsonNode block : blocks) {
                if (block.has("position") && block.path("position").isObject()) {
                    JsonNode p = block.path("position");
                    double xMm = p.path("xMm").asDouble(0) + marginMm;
                    double yMm = p.path("yMm").asDouble(0) + marginMm;
                    double wMm = p.path("wMm").asDouble(60);
                    double hMm = p.path("hMm").asDouble(20);
                    renderBlock(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm);
                }
            }
            double flowYMm = marginMm;
            double innerWMm = pageWidthMm - marginMm * 2.0;
            for (JsonNode block : blocks) {
                if (!(block.has("position") && block.path("position").isObject())) {
                    double defaultH = defaultFlowHeightMm(block);
                    renderBlock(z, block, context, marginMm, flowYMm,
                            innerWMm, defaultH, dotsPerMm);
                    flowYMm += defaultH + 2.0;
                    if (flowYMm > pageHeightMm - marginMm) break;
                }
            }
        }
        z.append("^XZ\n");
        return z.toString();
    }

    private static double defaultFlowHeightMm(JsonNode block) {
        return switch (block.path("kind").asText("")) {
            case "logo"      -> 25.0;
            case "address"   -> 30.0;
            case "items"     -> 40.0;
            case "barcode"   -> 20.0;
            case "qr"        -> 25.0;
            case "divider"   -> 2.0;
            case "spacer"    -> 5.0;
            case "totals"    -> 25.0;
            case "signature" -> 25.0;
            default          -> 15.0;
        };
    }

    private static void renderBlock(StringBuilder z, JsonNode block,
                                    Map<String, Object> context,
                                    double xMm, double yMm, double wMm, double hMm,
                                    double dotsPerMm) {
        String kind = block.path("kind").asText("");
        switch (kind) {
            case "text":      writeText(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm, false); break;
            case "signature": writeText(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm, true);  break;
            case "logo":      writeLogoPlaceholder(z, xMm, yMm, wMm, hMm, dotsPerMm); break;
            case "address":   writeAddress(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm); break;
            case "items":     writeItems(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm); break;
            case "barcode":   writeBarcode(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm); break;
            case "qr":        writeQr(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm); break;
            case "divider":   writeDivider(z, block, xMm, yMm, wMm, dotsPerMm); break;
            case "spacer":    /* no visual */ break;
            case "totals":    writeTotals(z, block, context, xMm, yMm, wMm, hMm, dotsPerMm); break;
            default:
                writeText(z, "Unsupported block: " + kind,
                        xMm, yMm, wMm, 3.5, 0.6, dotsPerMm, false, "left");
        }
    }

    // ================= block writers =================

    private static void writeText(StringBuilder z, JsonNode block,
                                  Map<String, Object> context,
                                  double xMm, double yMm, double wMm, double hMm,
                                  double dotsPerMm, boolean signature) {
        String content = TemplateBindingResolver.resolve(block.path("content").asText(""), context);
        String align = block.path("align").asText("left");
        int sizePx = block.path("sizePx").asInt(11);
        boolean bold = block.path("bold").asBoolean(false);
        // Convert px → mm for ZPL font height. 96 dpi assumed so the value
        // visually matches HTML/PDF at the same "size" number.
        double fontHmm = sizePx * (25.4 / 96.0);
        double topOffsetMm = 0.0;
        if (signature) {
            // Top rule for signature blocks — 0.3mm thick.
            z.append(box(xMm, yMm, wMm, 0.3, dotsPerMm));
            topOffsetMm = 2.0;
        }
        writeText(z, content, xMm, yMm + topOffsetMm, wMm, fontHmm,
                bold ? fontHmm * 0.9 : fontHmm * 0.7, dotsPerMm, bold, align);
        // Ensure hMm isn't unused for a warning — we already clip via ^FB max lines,
        // but keeping the caller's height honest lets a future ^GB clip land later.
        clampLines(hMm, fontHmm);
    }

    /** Common single-string text emitter used by writeText, writeAddress, writeTotals. */
    private static void writeText(StringBuilder z, String text,
                                  double xMm, double yMm, double wMm, double fontHmm,
                                  double fontWmm, double dotsPerMm,
                                  boolean bold, String align) {
        if (text == null || text.isEmpty()) return;
        int x = mm(xMm, dotsPerMm);
        int y = mm(yMm, dotsPerMm);
        int fh = Math.max(10, mm(fontHmm, dotsPerMm));
        int fw = Math.max(6,  mm(fontWmm, dotsPerMm));
        int wDots = mm(wMm, dotsPerMm);
        String zplAlign = switch (align) {
            case "center" -> "C";
            case "right"  -> "R";
            default        -> "L";
        };
        // ^A0N,h,w = scalable Font 0, normal rotation, height, width (dots).
        // ^FB gives us word-wrap into the block width across up to N lines.
        // 8 lines is a safe cap for a 4×6 shipping label; overflow gets
        // truncated by the printer rather than spilling into the next field.
        z.append("^FO").append(x).append(',').append(y);
        z.append("^A0N,").append(fh).append(',').append(fw);
        z.append("^FB").append(wDots).append(",8,0,").append(zplAlign).append(",0");
        z.append("^FD").append(escapeZpl(text)).append("^FS\n");
        if (bold) {
            // Cheap bold — overprint offset by 1 dot right + 1 dot down.
            z.append("^FO").append(x + 1).append(',').append(y + 1);
            z.append("^A0N,").append(fh).append(',').append(fw);
            z.append("^FB").append(wDots).append(",8,0,").append(zplAlign).append(",0");
            z.append("^FD").append(escapeZpl(text)).append("^FS\n");
        }
    }

    private static void writeLogoPlaceholder(StringBuilder z,
                                             double xMm, double yMm, double wMm, double hMm,
                                             double dotsPerMm) {
        // Dashed placeholder: outer box + a "LOGO" label centered inside.
        // Real image encoding into ^GFA is a follow-up (see class javadoc).
        z.append("^FO").append(mm(xMm, dotsPerMm)).append(',').append(mm(yMm, dotsPerMm));
        z.append("^GB").append(mm(wMm, dotsPerMm)).append(',').append(mm(hMm, dotsPerMm))
                .append(",2^FS\n");
        writeText(z, "[ LOGO ]", xMm, yMm + hMm / 2.0 - 2.0, wMm,
                4.0, 2.5, dotsPerMm, false, "center");
    }

    @SuppressWarnings("unchecked")
    private static void writeAddress(StringBuilder z, JsonNode block,
                                     Map<String, Object> context,
                                     double xMm, double yMm, double wMm, double hMm,
                                     double dotsPerMm) {
        String which = block.path("which").asText("shipTo");
        String label = block.path("label").asText("");
        Object addr = TemplateBindingResolver.lookup(context, which);
        double cursor = yMm;
        if (!label.isEmpty()) {
            writeText(z, label.toUpperCase(), xMm, cursor, wMm,
                    2.8, 1.8, dotsPerMm, true, "left");
            cursor += 3.5;
        }
        if (!(addr instanceof Map<?, ?> map)) {
            writeText(z, "— no data for " + which + " —",
                    xMm, cursor, wMm, 3.0, 2.0, dotsPerMm, false, "left");
            return;
        }
        Map<String, Object> a = (Map<String, Object>) map;
        String[] lines = new String[] {
                asString(a.get("name")),
                asString(a.get("line1")),
                asString(a.get("line2")),
                joinNonEmpty(", ", asString(a.get("city")),
                        joinNonEmpty(" ", asString(a.get("state")), asString(a.get("zip")))),
                asString(a.get("country")),
                asString(a.get("phone"))
        };
        double lineH = 4.0;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (cursor - yMm + lineH > hMm) break;
            writeText(z, line, xMm, cursor, wMm, lineH, lineH * 0.65, dotsPerMm, false, "left");
            cursor += lineH;
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeItems(StringBuilder z, JsonNode block,
                                   Map<String, Object> context,
                                   double xMm, double yMm, double wMm, double hMm,
                                   double dotsPerMm) {
        List<String> cols = jsonArrayAsStrings(block.path("columns"));
        if (cols.isEmpty()) cols = List.of("sku", "description", "qty", "unitPrice", "lineTotal");
        boolean showHeader = block.path("showHeader").asBoolean(true);
        Object items = TemplateBindingResolver.lookup(context, "items");
        double colW = wMm / cols.size();
        double rowH = 4.0;
        double cursor = yMm;
        if (showHeader) {
            // Header rule + labels
            z.append(box(xMm, cursor + rowH - 0.3, wMm, 0.3, dotsPerMm));
            for (int i = 0; i < cols.size(); i++) {
                writeText(z, prettyCol(cols.get(i)).toUpperCase(),
                        xMm + i * colW + 0.5, cursor, colW - 0.5,
                        2.8, 1.8, dotsPerMm, true, "left");
            }
            cursor += rowH;
        }
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            writeText(z, "— no items —", xMm + 0.5, cursor, wMm - 0.5,
                    3.0, 2.0, dotsPerMm, false, "left");
            return;
        }
        for (Object row : list) {
            if (cursor - yMm + rowH > hMm) break;
            if (!(row instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            for (int i = 0; i < cols.size(); i++) {
                Object v = item.get(cols.get(i));
                String text = v == null ? "" : String.valueOf(v);
                writeText(z, text, xMm + i * colW + 0.5, cursor, colW - 0.5,
                        3.0, 2.0, dotsPerMm, false, "left");
            }
            cursor += rowH;
        }
    }

    private static void writeBarcode(StringBuilder z, JsonNode block,
                                     Map<String, Object> context,
                                     double xMm, double yMm, double wMm, double hMm,
                                     double dotsPerMm) {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        if (value.isEmpty()) value = binding;
        String format = block.path("format").asText("code128");
        int x = mm(xMm, dotsPerMm);
        int y = mm(yMm, dotsPerMm);
        int barsH = Math.max(20, mm(hMm - 3.0, dotsPerMm)); // leave 3mm for human-readable
        // ^BY sets bar defaults — module width 2, ratio 3, height (overridden per barcode)
        z.append("^FO").append(x).append(',').append(y).append("^BY2,3,").append(barsH).append('\n');
        if ("code39".equalsIgnoreCase(format)) {
            // ^B3o,e,h,f,g — orientation N, mod43 check N, height, print interp Y, above N
            z.append("^B3N,N,").append(barsH).append(",Y,N");
        } else {
            // Code128 default. ^BCo,h,f,g,e,m — orientation N, height, print interp Y,
            // above N, UCC check N, mode A (auto).
            z.append("^BCN,").append(barsH).append(",Y,N,N,A");
        }
        z.append("^FD").append(escapeZpl(value)).append("^FS\n");
        // Suppress hMm/wMm unused warning by clamping cursor
        clampLines(hMm, 3.0);
        // Also silence wMm — it's advisory: ZPL doesn't let us cap barcode
        // width, but callers size the block wide enough for the encoded value.
        if (wMm < 0) return;
    }

    private static void writeQr(StringBuilder z, JsonNode block,
                                Map<String, Object> context,
                                double xMm, double yMm, double wMm, double hMm,
                                double dotsPerMm) {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        if (value.isEmpty()) value = binding;
        int x = mm(xMm, dotsPerMm);
        int y = mm(yMm, dotsPerMm);
        // Magnification factor picked from block size — bigger box = bigger
        // module. ZPL accepts 1..10; anything smaller than 3 is unreadable
        // on a 4×6 label.
        double side = Math.min(wMm, hMm);
        int mag = (int) Math.round(clamp(side / 6.0, 3, 10));
        z.append("^FO").append(x).append(',').append(y);
        // ^BQ orientation, model 2, magnification, error correction H, mask 7
        z.append("^BQN,2,").append(mag).append(",H,7");
        // Field data prefix: <error>A,<data>. Use H (30% error correction)
        // + Alphanumeric input; carriers commonly encode tracking URLs
        // here so alphanumeric is the safer default.
        z.append("^FDHA,").append(escapeZpl(value)).append("^FS\n");
    }

    private static void writeDivider(StringBuilder z, JsonNode block,
                                     double xMm, double yMm, double wMm,
                                     double dotsPerMm) {
        int thicknessPx = block.path("thicknessPx").asInt(1);
        double thicknessMm = Math.max(0.3, thicknessPx * (25.4 / 96.0));
        z.append(box(xMm, yMm, wMm, thicknessMm, dotsPerMm));
    }

    @SuppressWarnings("unchecked")
    private static void writeTotals(StringBuilder z, JsonNode block,
                                    Map<String, Object> context,
                                    double xMm, double yMm, double wMm, double hMm,
                                    double dotsPerMm) {
        List<String> include = jsonArrayAsStrings(block.path("include"));
        if (include.isEmpty()) include = List.of("subtotal", "freight", "grandTotal");
        String currency = block.path("currency").asText("USD");
        Object totalsObj = TemplateBindingResolver.lookup(context, "totals");
        Map<String, Object> totals = totalsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        double rowH = 4.0;
        double cursor = yMm;
        double labelW = wMm * 0.55;
        double amountW = wMm - labelW;
        for (String key : include) {
            if (cursor - yMm + rowH > hMm) break;
            writeText(z, prettyCol(key), xMm, cursor, labelW,
                    3.2, 2.1, dotsPerMm, true, "left");
            Object v = totals.get(key);
            String amount = (v == null ? "" : String.valueOf(v)) + " " + currency;
            writeText(z, amount, xMm + labelW, cursor, amountW,
                    3.2, 2.1, dotsPerMm, false, "right");
            cursor += rowH;
        }
    }

    // ================= helpers =================

    /** ^GB w,h,thickness — draws a filled rectangle at (x,y). */
    private static String box(double xMm, double yMm, double wMm, double hMm, double dotsPerMm) {
        int t = Math.max(1, mm(hMm, dotsPerMm));
        return "^FO" + mm(xMm, dotsPerMm) + "," + mm(yMm, dotsPerMm)
                + "^GB" + mm(wMm, dotsPerMm) + "," + t + "," + t + "^FS\n";
    }

    private static String centeredText(String msg, int pageWidthMm, int pageHeightMm, double dotsPerMm) {
        StringBuilder s = new StringBuilder();
        double yMm = pageHeightMm / 2.0 - 3.0;
        s.append("^FO0,").append(mm(yMm, dotsPerMm));
        int fh = mm(4.0, dotsPerMm);
        int fw = mm(2.5, dotsPerMm);
        s.append("^A0N,").append(fh).append(',').append(fw);
        s.append("^FB").append(mm(pageWidthMm, dotsPerMm)).append(",3,0,C,0");
        s.append("^FD").append(escapeZpl(msg)).append("^FS\n");
        return s.toString();
    }

    private static String placeholderLabel(String msg, double dotsPerMm) {
        StringBuilder s = new StringBuilder();
        int pageWidthMm = 100;
        int pageHeightMm = 152;
        s.append("^XA\n");
        s.append("^PW").append(mm(pageWidthMm, dotsPerMm)).append('\n');
        s.append("^LL").append(mm(pageHeightMm, dotsPerMm)).append('\n');
        s.append("^LH0,0\n^CI28\n");
        s.append(centeredText(msg, pageWidthMm, pageHeightMm, dotsPerMm));
        s.append("^XZ\n");
        return s.toString();
    }

    private static int mm(double millimetres, double dotsPerMm) {
        return (int) Math.round(millimetres * dotsPerMm);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** No-op; the layout height only matters if we grow line-count clipping later. */
    private static void clampLines(double hMm, double lineHmm) {
        if (hMm < 0 || lineHmm < 0) return; // parameter used marker
    }

    /**
     * Escape ZPL command specials in field data. {@code ^} and {@code ~}
     * start format / control commands; {@code \} starts an escape sequence.
     * Newlines become {@code \&} which ZPL treats as a line break inside
     * {@code ^FB} field blocks.
     */
    static String escapeZpl(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '^' -> out.append("\\5E");
                case '~' -> out.append("\\7E");
                case '\\' -> out.append("\\5C");
                case '\n' -> out.append("\\&");
                case '\r' -> { /* swallow — \n already handled */ }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(p);
        }
        return b.toString();
    }

    private static List<String> jsonArrayAsStrings(JsonNode arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    private static String prettyCol(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length() + 4);
        out.append(Character.toUpperCase(raw.charAt(0)));
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c)) out.append(' ');
            out.append(c);
        }
        return out.toString();
    }
}
