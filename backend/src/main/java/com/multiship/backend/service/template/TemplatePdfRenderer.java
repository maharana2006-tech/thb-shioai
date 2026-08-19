package com.multiship.backend.service.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Phase 2b — walks the same TemplateLayout JSON as
 * {@link TemplateHtmlRenderer} and emits a PDF via PDFBox. Coordinate math:
 *
 * <ul>
 *   <li>Layout is authored in mm with origin at the page's top-left (matches
 *       the HTML preview and the drag-drop canvas).</li>
 *   <li>PDFBox's origin is bottom-left, so every Y coordinate is flipped:
 *       {@code pdfY = pageHeightPt - yMm*MM_TO_PT}.</li>
 * </ul>
 *
 * <p>Every block kind supported by the HTML renderer has a matching branch
 * here so a template previewed as HTML lays out identically as PDF. Barcode
 * and QR are drawn as labelled placeholders — the real ZPL barcode ships in
 * Phase 2c; a PDFBox barcode implementation can slot in later without
 * changing the tree walk.
 *
 * <p>Never throws for missing block fields or unresolved bindings — we
 * prefer a blank cell over a failed render because these documents ship
 * to carriers who reject retries.
 */
@Slf4j
public final class TemplatePdfRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 1 mm = 2.83464567 pt at 72 dpi (PDF's native unit). */
    private static final float MM_TO_PT = 72f / 25.4f;

    private static final PDType1Font HELVETICA =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font HELVETICA_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font HELVETICA_OBLIQUE =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final Color TEXT_DEFAULT = new Color(0x0f, 0x17, 0x2a);
    private static final Color MUTED        = new Color(0x94, 0xa3, 0xb8);
    private static final Color RULE         = new Color(0xcb, 0xd5, 0xe1);
    private static final Color HEADER_BG    = new Color(0xf8, 0xfa, 0xfc);
    private static final Color HEADER_FG    = new Color(0x47, 0x55, 0x69);

    private TemplatePdfRenderer() { /* static utility */ }

    /**
     * Render a TemplateLayout JSON blob into a PDF byte array.
     *
     * @param layoutJson persisted JSON — nulls/blank produce a one-page
     *                   placeholder so callers never have to handle bytes[0].
     * @param context    binding context, same shape as the HTML renderer.
     * @return the PDF bytes, never null.
     */
    public static byte[] render(String layoutJson, Map<String, Object> context) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JsonNode root = null;
            String parseError = null;
            if (layoutJson != null && !layoutJson.isBlank()) {
                try {
                    root = JSON.readTree(layoutJson);
                } catch (Exception e) {
                    parseError = e.getMessage();
                }
            }
            // Page dimensions default to A4 (matches HTML renderer and the
            // frontend canvas). Any layout.page override wins.
            int pageWidthMm  = root != null ? root.path("page").path("widthMm").asInt(210)  : 210;
            int pageHeightMm = root != null ? root.path("page").path("heightMm").asInt(297) : 297;
            int marginMm     = root != null ? root.path("page").path("marginMm").asInt(10)  : 10;
            PDRectangle size = new PDRectangle(mm(pageWidthMm), mm(pageHeightMm));
            PDPage page = new PDPage(size);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (parseError != null) {
                    drawPlaceholder(cs, mm(pageWidthMm), mm(pageHeightMm),
                            "Layout JSON couldn't be parsed: " + parseError);
                } else if (root == null) {
                    drawPlaceholder(cs, mm(pageWidthMm), mm(pageHeightMm),
                            "This template hasn't been laid out yet — add blocks in the builder to see them here.");
                } else {
                    JsonNode blocks = root.path("blocks");
                    if (!blocks.isArray() || blocks.isEmpty()) {
                        drawPlaceholder(cs, mm(pageWidthMm), mm(pageHeightMm),
                                "No blocks yet. Add blocks in the builder.");
                    } else {
                        renderBlocks(cs, doc, blocks, context,
                                pageWidthMm, pageHeightMm, marginMm);
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render template PDF", e);
        }
    }

    private static void renderBlocks(PDPageContentStream cs, PDDocument doc,
                                     JsonNode blocks, Map<String, Object> context,
                                     int pageWidthMm, int pageHeightMm, int marginMm)
            throws Exception {
        float innerWMm = pageWidthMm - marginMm * 2f;
        // Two-pass render matches the HTML side: positioned blocks in author
        // order, then any legacy unpositioned blocks flow top-down in a stack.
        for (JsonNode block : blocks) {
            if (block.has("position") && block.path("position").isObject()) {
                JsonNode p = block.path("position");
                float xMm = (float) p.path("xMm").asDouble(0) + marginMm;
                float yMm = (float) p.path("yMm").asDouble(0) + marginMm;
                float wMm = (float) p.path("wMm").asDouble(60);
                float hMm = (float) p.path("hMm").asDouble(20);
                renderBlock(cs, doc, block, context,
                        xMm, yMm, wMm, hMm, pageHeightMm);
            }
        }
        // Flow-mode fallback for legacy blocks — 20mm rows starting at top
        // of the inner area, stacked downward. Enough to keep old rows
        // rendering; new templates always ship positions.
        float flowYMm = marginMm;
        for (JsonNode block : blocks) {
            if (!(block.has("position") && block.path("position").isObject())) {
                float defaultHMm = defaultFlowHeightMm(block);
                renderBlock(cs, doc, block, context,
                        marginMm, flowYMm, innerWMm, defaultHMm, pageHeightMm);
                flowYMm += defaultHMm + 2f;
                if (flowYMm > pageHeightMm - marginMm) break;
            }
        }
    }

    private static float defaultFlowHeightMm(JsonNode block) {
        return switch (block.path("kind").asText("")) {
            case "logo"      -> 25f;
            case "address"   -> 35f;
            case "items"     -> 60f;
            case "barcode"   -> 20f;
            case "qr"        -> 30f;
            case "divider"   -> 3f;
            case "spacer"    -> 10f;
            case "totals"    -> 30f;
            case "signature" -> 30f;
            default          -> 20f;
        };
    }

    private static void renderBlock(PDPageContentStream cs, PDDocument doc,
                                    JsonNode block, Map<String, Object> context,
                                    float xMm, float yMm, float wMm, float hMm,
                                    int pageHeightMm) throws Exception {
        String kind = block.path("kind").asText("");
        switch (kind) {
            case "text":      drawText(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm, false); break;
            case "signature": drawText(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm, true);  break;
            case "logo":      drawLogo(cs, doc, block, xMm, yMm, wMm, hMm, pageHeightMm); break;
            case "address":   drawAddress(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm); break;
            case "items":     drawItems(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm); break;
            case "barcode":   drawBarcode(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm); break;
            case "qr":        drawQr(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm); break;
            case "divider":   drawDivider(cs, block, xMm, yMm, wMm, pageHeightMm); break;
            case "spacer":    /* no visual */ break;
            case "totals":    drawTotals(cs, block, context, xMm, yMm, wMm, hMm, pageHeightMm); break;
            default:
                drawUnsupported(cs, xMm, yMm, wMm, pageHeightMm, kind);
        }
    }

    // ================= block renderers =================

    private static void drawText(PDPageContentStream cs, JsonNode block,
                                 Map<String, Object> context, float xMm, float yMm,
                                 float wMm, float hMm, int pageHeightMm,
                                 boolean signature) throws Exception {
        String content = TemplateBindingResolver.resolve(block.path("content").asText(""), context);
        String align = block.path("align").asText("left");
        int sizePx = block.path("sizePx").asInt(11);
        boolean bold = block.path("bold").asBoolean(false);
        String color = block.path("color").asText(null);
        Color c = parseColor(color, TEXT_DEFAULT);
        PDType1Font font = bold ? HELVETICA_BOLD : HELVETICA;
        float fontSize = pxToPt(sizePx);
        cs.setNonStrokingColor(c);
        // Signature blocks get a top rule + a bit of padding to visually
        // separate them from surrounding content — mirrors the HTML.
        float topOffsetPt = 0f;
        if (signature) {
            float baseTopY = flipY(yMm, pageHeightMm);
            cs.setStrokingColor(RULE);
            cs.setLineWidth(0.5f);
            cs.moveTo(mm(xMm), baseTopY);
            cs.lineTo(mm(xMm + wMm), baseTopY);
            cs.stroke();
            cs.setStrokingColor(Color.BLACK);
            topOffsetPt = 6f;
        }
        drawWrapped(cs, font, fontSize, content,
                mm(xMm), flipY(yMm, pageHeightMm) - fontSize - topOffsetPt,
                mm(wMm), mm(hMm) - topOffsetPt, align);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    private static void drawLogo(PDPageContentStream cs, PDDocument doc, JsonNode block,
                                 float xMm, float yMm, float wMm, float hMm,
                                 int pageHeightMm) throws Exception {
        String src = block.path("src").asText("");
        if (src.isEmpty()) {
            drawPlaceholderRect(cs, xMm, yMm, wMm, hMm, pageHeightMm, "[ logo placeholder ]");
            return;
        }
        byte[] bytes = decodeDataUrl(src);
        if (bytes == null) {
            drawPlaceholderRect(cs, xMm, yMm, wMm, hMm, pageHeightMm, "[ logo unavailable ]");
            return;
        }
        try {
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, "logo");
            float boxW = mm(wMm);
            float boxH = mm(hMm);
            // Fit inside the block preserving aspect ratio.
            float ratio = (float) img.getWidth() / img.getHeight();
            float drawW = boxW;
            float drawH = boxW / ratio;
            if (drawH > boxH) {
                drawH = boxH;
                drawW = boxH * ratio;
            }
            String align = block.path("align").asText("left");
            float extraX = switch (align) {
                case "center" -> (boxW - drawW) / 2f;
                case "right"  -> boxW - drawW;
                default        -> 0f;
            };
            float topY = flipY(yMm, pageHeightMm);
            cs.drawImage(img, mm(xMm) + extraX, topY - drawH, drawW, drawH);
        } catch (Exception e) {
            drawPlaceholderRect(cs, xMm, yMm, wMm, hMm, pageHeightMm, "[ logo decode failed ]");
        }
    }

    @SuppressWarnings("unchecked")
    private static void drawAddress(PDPageContentStream cs, JsonNode block,
                                    Map<String, Object> context, float xMm, float yMm,
                                    float wMm, float hMm, int pageHeightMm) throws Exception {
        String which = block.path("which").asText("shipTo");
        String label = block.path("label").asText("");
        Object addr = TemplateBindingResolver.lookup(context, which);
        float topY = flipY(yMm, pageHeightMm);
        float cursor = topY;
        if (!label.isEmpty()) {
            cs.setNonStrokingColor(HEADER_FG);
            drawSingle(cs, HELVETICA_BOLD, 8f, label.toUpperCase(), mm(xMm), cursor - 8f);
            cursor -= 12f;
        }
        cs.setNonStrokingColor(TEXT_DEFAULT);
        if (!(addr instanceof Map<?, ?> map)) {
            cs.setNonStrokingColor(MUTED);
            drawSingle(cs, HELVETICA_OBLIQUE, 9f,
                    "— no data for " + which + " —", mm(xMm), cursor - 9f);
            cs.setNonStrokingColor(TEXT_DEFAULT);
            return;
        }
        Map<String, Object> a = (Map<String, Object>) map;
        List<String> lines = new ArrayList<>();
        addIfNonEmpty(lines, a.get("name"));
        addIfNonEmpty(lines, a.get("line1"));
        addIfNonEmpty(lines, a.get("line2"));
        String cityLine = joinNonEmpty(", ",
                asString(a.get("city")),
                joinNonEmpty(" ", asString(a.get("state")), asString(a.get("zip"))));
        if (!cityLine.isEmpty()) lines.add(cityLine);
        addIfNonEmpty(lines, a.get("country"));
        addIfNonEmpty(lines, a.get("phone"));
        float lineH = 12f;
        float bottom = flipY(yMm + hMm, pageHeightMm);
        for (String line : lines) {
            if (cursor - lineH < bottom) break;
            cursor -= lineH;
            drawSingle(cs, HELVETICA, 10f, line, mm(xMm), cursor);
        }
    }

    @SuppressWarnings("unchecked")
    private static void drawItems(PDPageContentStream cs, JsonNode block,
                                  Map<String, Object> context, float xMm, float yMm,
                                  float wMm, float hMm, int pageHeightMm) throws Exception {
        List<String> cols = jsonArrayAsStrings(block.path("columns"));
        if (cols.isEmpty()) cols = List.of("sku", "description", "qty", "unitPrice", "lineTotal");
        boolean showHeader = block.path("showHeader").asBoolean(true);
        Object items = TemplateBindingResolver.lookup(context, "items");
        float topY = flipY(yMm, pageHeightMm);
        float bottomY = flipY(yMm + hMm, pageHeightMm);
        float x0 = mm(xMm);
        float colW = mm(wMm) / cols.size();
        float rowH = 12f;
        float cursor = topY;
        if (showHeader) {
            // Header background band + labels
            cs.setNonStrokingColor(HEADER_BG);
            cs.addRect(x0, cursor - rowH, mm(wMm), rowH);
            cs.fill();
            cs.setNonStrokingColor(HEADER_FG);
            for (int i = 0; i < cols.size(); i++) {
                drawSingle(cs, HELVETICA_BOLD, 8f,
                        prettyCol(cols.get(i)).toUpperCase(),
                        x0 + i * colW + 4f, cursor - rowH + 3.5f);
            }
            cs.setNonStrokingColor(TEXT_DEFAULT);
            cursor -= rowH;
        }
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            cs.setNonStrokingColor(MUTED);
            drawSingle(cs, HELVETICA_OBLIQUE, 9f, "— no items —",
                    x0 + 4f, cursor - 10f);
            cs.setNonStrokingColor(TEXT_DEFAULT);
            return;
        }
        for (Object row : list) {
            if (cursor - rowH < bottomY) break;
            cursor -= rowH;
            if (!(row instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            for (int i = 0; i < cols.size(); i++) {
                Object v = item.get(cols.get(i));
                String text = v == null ? "" : String.valueOf(v);
                drawClipped(cs, HELVETICA, 9.5f, text,
                        x0 + i * colW + 4f, cursor + 3.5f, colW - 8f);
            }
            // Thin row rule
            cs.setStrokingColor(RULE);
            cs.setLineWidth(0.3f);
            cs.moveTo(x0, cursor);
            cs.lineTo(x0 + mm(wMm), cursor);
            cs.stroke();
            cs.setStrokingColor(Color.BLACK);
        }
    }

    private static void drawBarcode(PDPageContentStream cs, JsonNode block,
                                    Map<String, Object> context, float xMm, float yMm,
                                    float wMm, float hMm, int pageHeightMm) throws Exception {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        if (value.isEmpty()) value = binding;
        float topY = flipY(yMm, pageHeightMm);
        float bottomY = flipY(yMm + hMm, pageHeightMm);
        float labelSpace = 12f;
        float barsHeight = Math.max(6f, (topY - bottomY) - labelSpace);
        // Draw a stripe pattern that approximates a Code 128 barcode. Real
        // barcode drawing lands with the ZPL renderer in Phase 2c; this
        // preview-quality stripe keeps PDF/HTML visually consistent.
        cs.setNonStrokingColor(TEXT_DEFAULT);
        float x = mm(xMm);
        float barW = 1.2f;
        float gap = 1.0f;
        int i = 0;
        while (x + barW < mm(xMm + wMm)) {
            float w = (i % 3 == 0) ? barW * 1.7f : barW;
            cs.addRect(x, bottomY + labelSpace, w, barsHeight);
            cs.fill();
            x += w + gap;
            i++;
        }
        cs.setNonStrokingColor(HEADER_FG);
        drawCentered(cs, HELVETICA, 8.5f, value,
                mm(xMm), mm(xMm + wMm), bottomY + 2f);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    private static void drawQr(PDPageContentStream cs, JsonNode block,
                               Map<String, Object> context, float xMm, float yMm,
                               float wMm, float hMm, int pageHeightMm) throws Exception {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        if (value.isEmpty()) value = binding;
        float topY = flipY(yMm, pageHeightMm);
        float bottomY = flipY(yMm + hMm, pageHeightMm);
        float side = Math.min(mm(wMm), (topY - bottomY) - 12f);
        // Placeholder: bordered box with QR text + resolved value below.
        cs.setStrokingColor(RULE);
        cs.setLineWidth(0.5f);
        cs.addRect(mm(xMm), topY - side, side, side);
        cs.stroke();
        cs.setStrokingColor(Color.BLACK);
        cs.setNonStrokingColor(HEADER_FG);
        drawCentered(cs, HELVETICA_BOLD, 12f, "QR",
                mm(xMm), mm(xMm) + side, topY - side / 2f - 4f);
        drawCentered(cs, HELVETICA, 7.5f, value,
                mm(xMm), mm(xMm + wMm), bottomY + 2f);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    private static void drawDivider(PDPageContentStream cs, JsonNode block,
                                    float xMm, float yMm, float wMm,
                                    int pageHeightMm) throws Exception {
        int thicknessPx = block.path("thicknessPx").asInt(1);
        String color = block.path("color").asText("#94a3b8");
        cs.setStrokingColor(parseColor(color, MUTED));
        cs.setLineWidth(Math.max(0.3f, pxToPt(thicknessPx)));
        float y = flipY(yMm, pageHeightMm);
        cs.moveTo(mm(xMm), y);
        cs.lineTo(mm(xMm + wMm), y);
        cs.stroke();
        cs.setStrokingColor(Color.BLACK);
    }

    @SuppressWarnings("unchecked")
    private static void drawTotals(PDPageContentStream cs, JsonNode block,
                                   Map<String, Object> context, float xMm, float yMm,
                                   float wMm, float hMm, int pageHeightMm) throws Exception {
        List<String> include = jsonArrayAsStrings(block.path("include"));
        if (include.isEmpty()) include = List.of("subtotal", "freight", "grandTotal");
        String currency = block.path("currency").asText("USD");
        Object totalsObj = TemplateBindingResolver.lookup(context, "totals");
        Map<String, Object> totals = totalsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        float topY = flipY(yMm, pageHeightMm);
        float bottomY = flipY(yMm + hMm, pageHeightMm);
        float rowH = 12f;
        float cursor = topY;
        float rightX = mm(xMm + wMm);
        for (String key : include) {
            if (cursor - rowH < bottomY) break;
            cursor -= rowH;
            String labelText = prettyCol(key);
            Object v = totals.get(key);
            String amount = (v == null ? "" : String.valueOf(v)) + " " + currency;
            cs.setNonStrokingColor(HEADER_FG);
            drawSingle(cs, HELVETICA_BOLD, 9.5f, labelText, mm(xMm), cursor + 2f);
            cs.setNonStrokingColor(TEXT_DEFAULT);
            drawRightAligned(cs, HELVETICA, 10f, amount, rightX, cursor + 2f);
        }
    }

    private static void drawUnsupported(PDPageContentStream cs, float xMm, float yMm,
                                        float wMm, int pageHeightMm, String kind) throws Exception {
        cs.setNonStrokingColor(new Color(0xb9, 0x1c, 0x1c));
        drawSingle(cs, HELVETICA_OBLIQUE, 9f,
                "Unsupported block kind: " + kind, mm(xMm), flipY(yMm, pageHeightMm) - 10f);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    private static void drawPlaceholder(PDPageContentStream cs, float pageWpt,
                                        float pageHpt, String message) throws Exception {
        cs.setNonStrokingColor(MUTED);
        drawCentered(cs, HELVETICA_OBLIQUE, 11f, message,
                0f, pageWpt, pageHpt / 2f);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    private static void drawPlaceholderRect(PDPageContentStream cs, float xMm, float yMm,
                                            float wMm, float hMm, int pageHeightMm,
                                            String label) throws Exception {
        float topY = flipY(yMm, pageHeightMm);
        cs.setStrokingColor(RULE);
        cs.setLineWidth(0.4f);
        // Dashed border courtesy of PDFBox — 3 on / 2 off @ pt scale.
        cs.setLineDashPattern(new float[]{3f, 2f}, 0f);
        cs.addRect(mm(xMm), topY - mm(hMm), mm(wMm), mm(hMm));
        cs.stroke();
        cs.setLineDashPattern(new float[]{}, 0f);
        cs.setStrokingColor(Color.BLACK);
        cs.setNonStrokingColor(MUTED);
        drawCentered(cs, HELVETICA, 9f, label,
                mm(xMm), mm(xMm + wMm), topY - mm(hMm) / 2f - 4f);
        cs.setNonStrokingColor(TEXT_DEFAULT);
    }

    // ================= text drawing helpers =================

    private static void drawSingle(PDPageContentStream cs, PDType1Font font,
                                   float size, String text, float x, float y) throws Exception {
        if (text == null || text.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(text));
        cs.endText();
    }

    private static void drawCentered(PDPageContentStream cs, PDType1Font font,
                                     float size, String text, float xLeft, float xRight,
                                     float y) throws Exception {
        if (text == null || text.isBlank()) return;
        String s = sanitise(text);
        float w = textWidth(font, size, s);
        float x = xLeft + ((xRight - xLeft) - w) / 2f;
        drawSingle(cs, font, size, s, x, y);
    }

    private static void drawRightAligned(PDPageContentStream cs, PDType1Font font,
                                         float size, String text, float xRight,
                                         float y) throws Exception {
        if (text == null || text.isBlank()) return;
        String s = sanitise(text);
        float w = textWidth(font, size, s);
        drawSingle(cs, font, size, s, xRight - w, y);
    }

    private static void drawClipped(PDPageContentStream cs, PDType1Font font,
                                    float size, String text, float x, float y,
                                    float maxWidth) throws Exception {
        if (text == null || text.isBlank()) return;
        String s = sanitise(text);
        // Truncate character-by-character rather than word-wrapping — items
        // rows are single-line by convention and word-wrapping would blow out
        // the row height set by the caller.
        while (textWidth(font, size, s) > maxWidth && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.equals(sanitise(text)) && s.length() > 1) {
            s = s.substring(0, s.length() - 1) + "…";
        }
        drawSingle(cs, font, size, s, x, y);
    }

    /**
     * Word-wrap {@code text} into lines that fit {@code maxWidth}, drawing
     * from {@code (x, yTop)} downward respecting the alignment. Explicit
     * newlines force a break; long words that don't fit get hard-broken.
     * Silently stops when the y cursor falls below {@code yTop - maxHeight}.
     */
    private static void drawWrapped(PDPageContentStream cs, PDType1Font font,
                                    float size, String text, float x, float yTop,
                                    float maxWidth, float maxHeight, String align)
            throws Exception {
        if (text == null || text.isEmpty()) return;
        float lineH = size * 1.25f;
        float cursor = yTop;
        float bottom = yTop - maxHeight + lineH;
        for (String paragraph : text.split("\\r?\\n")) {
            List<String> lines = wrap(font, size, paragraph, maxWidth);
            for (String line : lines) {
                if (cursor < bottom) return;
                String s = sanitise(line);
                float w = textWidth(font, size, s);
                float lineX = x;
                if ("center".equals(align)) lineX = x + (maxWidth - w) / 2f;
                else if ("right".equals(align)) lineX = x + maxWidth - w;
                drawSingle(cs, font, size, s, lineX, cursor);
                cursor -= lineH;
            }
        }
    }

    private static List<String> wrap(PDType1Font font, float size, String text, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (textWidth(font, size, sanitise(candidate)) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                // Hard-break words that overflow the width on their own so
                // the layout doesn't silently drop content.
                String remaining = word;
                while (textWidth(font, size, sanitise(remaining)) > maxWidth && remaining.length() > 1) {
                    int cut = remaining.length();
                    while (cut > 1 && textWidth(font, size, sanitise(remaining.substring(0, cut))) > maxWidth) {
                        cut--;
                    }
                    out.add(remaining.substring(0, cut));
                    remaining = remaining.substring(cut);
                }
                current.append(remaining);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static float textWidth(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(sanitise(text)) / 1000f * size;
        } catch (Exception e) {
            return size * 0.5f * (text == null ? 0 : text.length());
        }
    }

    /** WinAnsi-only sanitisation matches {@link PackingSlipServiceImpl}. */
    private static String sanitise(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c <= 126) out.append(c);
            else if (c == '–' || c == '—') out.append('-');
            else if (c == ' ') out.append(' ');
            else out.append('?');
        }
        return out.toString();
    }

    // ================= misc helpers =================

    private static float mm(float millimetres) {
        return millimetres * MM_TO_PT;
    }

    private static float pxToPt(int px) {
        // Screen px → PDF pt at the common 96-dpi assumption. Keeps the
        // "sizePx=11" defaults visually similar to the HTML preview.
        return px * (72f / 96f);
    }

    /** PDFBox origin is bottom-left; layout origin is top-left. */
    private static float flipY(float yMm, int pageHeightMm) {
        return mm(pageHeightMm) - mm(yMm);
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0)
                       + h.charAt(1) + h.charAt(1)
                       + h.charAt(2) + h.charAt(2);
            }
            if (h.length() != 6) return fallback;
            return new Color(
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static byte[] decodeDataUrl(String src) {
        String data = src;
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            // Null return preserves the "never throw during render" contract
            // (see class doc). DEBUG because it fires per-render and points
            // to a user-owned template asset, not an ops issue.
            String prefix = src == null ? "<null>"
                    : src.substring(0, Math.min(40, src.length()));
            log.debug("Template data-URL Base64 decode failed (prefix='{}'): {}", prefix, e.toString());
            return null;
        }
    }

    private static void addIfNonEmpty(List<String> lines, Object v) {
        String s = asString(v);
        if (!s.isEmpty()) lines.add(s);
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
        List<String> out = new ArrayList<>();
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
