package com.multiship.backend.service.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Phase 2a — walks a persisted TemplateLayout JSON tree and produces HTML
 * suitable for a preview iframe. Deliberately simple + inline CSS so the
 * output is a single self-contained blob the frontend can drop into an
 * iframe or a print page without needing external resources.
 *
 * <p>Not a full-fidelity PDF renderer — that lands in Phase 2b. This one
 * gives operators an accurate visual check while they build the template.
 *
 * <p>Layout shape mirrors the TypeScript {@code TemplateLayout} type;
 * unknown block kinds render as a small "unsupported" hint so a schema
 * mismatch doesn't blank the preview.
 */
public final class TemplateHtmlRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TemplateHtmlRenderer() { /* static utility */ }

    /**
     * Render a TemplateLayout JSON blob into a full HTML document.
     *
     * @param layoutJson persisted JSON from label_templates.layout_json — may
     *        be null or blank, in which case a placeholder document is
     *        returned.
     * @param context    binding context; use {@link SampleShipmentContext#sample()}
     *                   for the preview endpoint or a real Shipment-derived
     *                   map at render time.
     */
    public static String render(String layoutJson, Map<String, Object> context) {
        StringBuilder html = new StringBuilder(2048);
        html.append(HEADER);
        if (layoutJson == null || layoutJson.isBlank()) {
            html.append(placeholder("This template hasn't been laid out yet — add blocks in the builder to see them here."));
            html.append(FOOTER);
            return html.toString();
        }
        JsonNode root;
        try {
            root = JSON.readTree(layoutJson);
        } catch (Exception e) {
            html.append(placeholder("Layout JSON couldn't be parsed: " + escape(e.getMessage())));
            html.append(FOOTER);
            return html.toString();
        }
        // Page margin — accept the layout's page.marginMm or fall back to 10mm.
        int marginMm = root.path("page").path("marginMm").asInt(10);
        int pageWidthMm = root.path("page").path("widthMm").asInt(210);
        int pageHeightMm = root.path("page").path("heightMm").asInt(297);
        int innerWMm = pageWidthMm - marginMm * 2;
        int innerHMm = pageHeightMm - marginMm * 2;
        html.append("<div class=\"page\" style=\"width:").append(pageWidthMm).append("mm;height:")
                .append(pageHeightMm).append("mm;position:relative;padding:0;\">");
        // Inner (printable) area — positioned blocks anchor here so
        // page.marginMm is respected without a wrapper padding hack that
        // would break absolute-position math.
        html.append("<div class=\"inner\" style=\"position:absolute;left:").append(marginMm)
                .append("mm;top:").append(marginMm).append("mm;width:").append(innerWMm)
                .append("mm;height:").append(innerHMm).append("mm;\">");
        JsonNode blocks = root.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty()) {
            html.append(placeholder("No blocks yet. Add blocks in the builder."));
        } else {
            // Split blocks into two passes: positioned (absolute canvas) and
            // legacy (unpositioned — flows top-down as a fallback). Same order
            // preserved within each group so operator intent stays visible.
            for (JsonNode block : blocks) {
                if (block.has("position") && block.path("position").isObject()) {
                    html.append(renderPositionedBlock(block, context));
                }
            }
            for (JsonNode block : blocks) {
                if (!(block.has("position") && block.path("position").isObject())) {
                    html.append(renderBlock(block, context));
                }
            }
        }
        html.append("</div></div>");
        html.append(FOOTER);
        return html.toString();
    }

    /**
     * Wrap a block in an absolutely-positioned container sized in mm from
     * the block's {@code position} object. Fall back to sensible defaults so
     * a partial position (e.g. missing wMm) still renders something.
     */
    private static String renderPositionedBlock(JsonNode block, Map<String, Object> context) {
        JsonNode p = block.path("position");
        double x = p.path("xMm").asDouble(0);
        double y = p.path("yMm").asDouble(0);
        double w = p.path("wMm").asDouble(60);
        double h = p.path("hMm").asDouble(20);
        return "<div class=\"pblock\" style=\"position:absolute;left:" + x + "mm;top:" + y
                + "mm;width:" + w + "mm;height:" + h + "mm;overflow:hidden;\">"
                + renderBlock(block, context)
                + "</div>";
    }

    private static String renderBlock(JsonNode block, Map<String, Object> context) {
        String kind = block.path("kind").asText("");
        switch (kind) {
            case "text":      return renderText(block, context, false);
            case "signature": return renderText(block, context, true);
            case "logo":      return renderLogo(block);
            case "address":   return renderAddress(block, context);
            case "items":     return renderItems(block, context);
            case "barcode":   return renderBarcode(block, context);
            case "qr":        return renderQr(block, context);
            case "divider":   return renderDivider(block);
            case "spacer":    return renderSpacer(block);
            case "totals":    return renderTotals(block, context);
            default:          return "<div class=\"unsupported\">Unsupported block kind: " + escape(kind) + "</div>";
        }
    }

    private static String renderText(JsonNode block, Map<String, Object> context, boolean signature) {
        String content = TemplateBindingResolver.resolve(block.path("content").asText(""), context);
        String align = block.path("align").asText("left");
        int size = block.path("sizePx").asInt(signature ? 11 : 11);
        boolean bold = block.path("bold").asBoolean(false);
        String color = block.path("color").asText(null);
        StringBuilder style = new StringBuilder();
        style.append("text-align:").append(safeAlign(align)).append(';');
        style.append("font-size:").append(clamp(size, 6, 72)).append("px;");
        if (bold) style.append("font-weight:700;");
        if (color != null && !color.isBlank()) style.append("color:").append(escape(color)).append(';');
        if (signature) style.append("border-top:1px solid #94a3b8;padding-top:6px;margin-top:16px;");
        return "<p class=\"block text\" style=\"" + style + "\">" + escapeMultiline(content) + "</p>";
    }

    private static String renderLogo(JsonNode block) {
        String src = block.path("src").asText("");
        int width = block.path("widthPx").asInt(120);
        String align = block.path("align").asText("left");
        if (src.isEmpty()) {
            return "<div class=\"block logo-placeholder\" style=\"text-align:" + safeAlign(align) + "\">[ logo placeholder ]</div>";
        }
        return "<div class=\"block\" style=\"text-align:" + safeAlign(align) + "\">"
                + "<img src=\"" + escape(src) + "\" style=\"width:" + clamp(width, 20, 800) + "px;max-height:180px;object-fit:contain\" />"
                + "</div>";
    }

    @SuppressWarnings("unchecked")
    private static String renderAddress(JsonNode block, Map<String, Object> context) {
        String which = block.path("which").asText("shipTo");
        String label = block.path("label").asText("");
        Object addr = TemplateBindingResolver.lookup(context, which);
        StringBuilder out = new StringBuilder("<div class=\"block address\">");
        if (!label.isEmpty()) out.append("<div class=\"address-label\">").append(escape(label)).append("</div>");
        if (!(addr instanceof Map<?, ?> map)) {
            out.append("<div class=\"muted\">— no data for ").append(escape(which)).append(" —</div></div>");
            return out.toString();
        }
        Map<String, Object> a = (Map<String, Object>) map;
        appendLine(out, a.get("name"));
        appendLine(out, a.get("line1"));
        appendLine(out, a.get("line2"));
        String cityLine = joinNonEmpty(", ",
                asString(a.get("city")),
                joinNonEmpty(" ", asString(a.get("state")), asString(a.get("zip"))));
        appendLine(out, cityLine);
        appendLine(out, asString(a.get("country")));
        appendLine(out, asString(a.get("phone")));
        out.append("</div>");
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static String renderItems(JsonNode block, Map<String, Object> context) {
        List<String> cols = jsonArrayAsStrings(block.path("columns"));
        boolean showHeader = block.path("showHeader").asBoolean(true);
        if (cols.isEmpty()) {
            cols = List.of("sku", "description", "qty", "unitPrice", "lineTotal");
        }
        Object items = TemplateBindingResolver.lookup(context, "items");
        StringBuilder out = new StringBuilder("<table class=\"block items\">");
        if (showHeader) {
            out.append("<thead><tr>");
            for (String c : cols) out.append("<th>").append(escape(prettyCol(c))).append("</th>");
            out.append("</tr></thead>");
        }
        out.append("<tbody>");
        if (items instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) continue;
                Map<String, Object> item = (Map<String, Object>) m;
                out.append("<tr>");
                for (String c : cols) {
                    Object v = item.get(c);
                    out.append("<td>").append(v == null ? "" : escape(String.valueOf(v))).append("</td>");
                }
                out.append("</tr>");
            }
        } else {
            out.append("<tr><td colspan=\"").append(cols.size()).append("\" class=\"muted\">— no items —</td></tr>");
        }
        out.append("</tbody></table>");
        return out.toString();
    }

    private static String renderBarcode(JsonNode block, Map<String, Object> context) {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        int h = block.path("heightPx").asInt(60);
        // Preview only — we render a stylised placeholder, not an actual
        // barcode image. The real barcode ships in Phase 2b (PDF) / 2c (ZPL).
        return "<div class=\"block barcode\" style=\"height:" + clamp(h, 20, 200) + "px\">"
                + "<div class=\"barcode-lines\"></div>"
                + "<div class=\"barcode-value\">" + escape(value.isEmpty() ? binding : value) + "</div>"
                + "</div>";
    }

    private static String renderQr(JsonNode block, Map<String, Object> context) {
        String binding = block.path("binding").asText("");
        String value = TemplateBindingResolver.valueOrEmpty(context, binding);
        int size = block.path("sizePx").asInt(90);
        return "<div class=\"block qr\" style=\"width:" + clamp(size, 30, 300) + "px;height:" + clamp(size, 30, 300) + "px\">"
                + "<div class=\"qr-inner\">QR</div>"
                + "<div class=\"qr-value\">" + escape(value.isEmpty() ? binding : value) + "</div>"
                + "</div>";
    }

    private static String renderDivider(JsonNode block) {
        int t = block.path("thicknessPx").asInt(1);
        String color = block.path("color").asText("#94a3b8");
        return "<hr class=\"block divider\" style=\"border:0;border-top:" + clamp(t, 1, 10) + "px solid " + escape(color) + ";margin:8px 0\" />";
    }

    private static String renderSpacer(JsonNode block) {
        int h = block.path("heightPx").asInt(12);
        return "<div class=\"block spacer\" style=\"height:" + clamp(h, 2, 200) + "px\"></div>";
    }

    @SuppressWarnings("unchecked")
    private static String renderTotals(JsonNode block, Map<String, Object> context) {
        List<String> include = jsonArrayAsStrings(block.path("include"));
        if (include.isEmpty()) include = List.of("subtotal", "freight", "grandTotal");
        String currency = block.path("currency").asText("USD");
        Object totals = TemplateBindingResolver.lookup(context, "totals");
        Map<String, Object> t = totals instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        StringBuilder out = new StringBuilder("<table class=\"block totals\">");
        for (String k : include) {
            Object v = t.get(k);
            String amount = v == null ? "" : String.valueOf(v);
            out.append("<tr><th>").append(escape(prettyCol(k))).append("</th><td>")
                    .append(escape(amount)).append("&nbsp;").append(escape(currency)).append("</td></tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    // ===== helpers =====

    private static void appendLine(StringBuilder out, Object v) {
        String s = asString(v);
        if (s.isEmpty()) return;
        out.append(escape(s)).append("<br/>");
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
        // camelCase → Title Case with spaces
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

    private static String safeAlign(String a) {
        return switch (a) { case "center", "right" -> a; default -> "left"; };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeMultiline(String s) {
        return escape(s).replace("\n", "<br/>");
    }

    private static String placeholder(String msg) {
        return "<div class=\"placeholder\">" + escape(msg) + "</div>";
    }

    private static final String HEADER = "<!doctype html><html><head><meta charset=\"utf-8\"/><style>" +
            "html,body{margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;font-size:11px;color:#0f172a;}" +
            // The page renders at true mm dimensions. Browser scales viewport
            // to fit; on screen 1mm ≈ 3.78px which matches PDF@72dpi closely.
            ".page{margin:16px auto;background:white;box-shadow:0 4px 20px rgba(15,23,42,.08);border-radius:2px;position:relative;overflow:hidden}" +
            ".inner{position:relative}" +
            // Positioned blocks have their own absolute container; inner
            // content shouldn't add its own margin or the position drifts.
            ".pblock > .block, .pblock > table, .pblock > hr, .pblock > div{margin:0}" +
            ".pblock{box-sizing:border-box}" +
            ".block{margin:0 0 8px 0}" +
            ".text{white-space:pre-wrap;line-height:1.4}" +
            ".logo-placeholder{padding:16px;border:1px dashed #cbd5e1;color:#64748b;text-align:center}" +
            ".address .address-label{font-size:9px;letter-spacing:1px;text-transform:uppercase;color:#64748b;margin-bottom:2px}" +
            ".address{line-height:1.5}" +
            ".muted{color:#94a3b8;font-style:italic}" +
            "table.items{width:100%;border-collapse:collapse;font-size:10.5px;margin:4px 0 8px 0}" +
            "table.items th,table.items td{border-bottom:1px solid #e2e8f0;padding:4px 6px;text-align:left;vertical-align:top}" +
            "table.items th{background:#f8fafc;font-weight:600;font-size:9.5px;color:#475569;text-transform:uppercase;letter-spacing:.5px}" +
            "table.totals{margin-left:auto;font-size:11px;border-collapse:collapse}" +
            "table.totals th{padding:2px 8px 2px 0;text-align:right;font-weight:600;color:#475569}" +
            "table.totals td{padding:2px 0 2px 8px;text-align:right;font-variant-numeric:tabular-nums}" +
            ".barcode{margin:6px 0;position:relative;display:inline-block}" +
            ".barcode-lines{height:calc(100% - 14px);background:repeating-linear-gradient(90deg,#0f172a 0,#0f172a 2px,transparent 2px,transparent 4px,#0f172a 4px,#0f172a 5px,transparent 5px,transparent 7px);width:180px}" +
            ".barcode-value{font-family:monospace;font-size:9.5px;text-align:center;padding-top:2px;color:#334155}" +
            ".qr{border:1px solid #e2e8f0;background:#f8fafc;display:inline-flex;flex-direction:column;align-items:center;justify-content:center;padding:6px}" +
            ".qr-inner{font-family:monospace;font-weight:700;color:#334155}" +
            ".qr-value{font-family:monospace;font-size:9px;color:#64748b;word-break:break-all;text-align:center;margin-top:4px}" +
            ".divider{margin:8px 0}" +
            ".unsupported{color:#b91c1c;background:#fef2f2;padding:6px 10px;border-radius:6px;font-family:monospace}" +
            ".placeholder{color:#64748b;background:#fafafa;padding:16px;text-align:center;border:1px dashed #e2e8f0;border-radius:6px}" +
            "</style></head><body>";
    private static final String FOOTER = "</body></html>";
}
