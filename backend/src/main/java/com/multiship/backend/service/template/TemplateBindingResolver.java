package com.multiship.backend.service.template;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2a — walk `{{group.field}}` tokens in template block content and
 * resolve them against a nested context map. The context is a plain Map
 * whose values may themselves be Maps (for nested paths like
 * {@code customs.importer.name}) or Lists (for arrays like {@code items}).
 *
 * <p>Missing fields render as empty strings by default — carriers reject
 * document generation when bindings blow up, so we prefer to render a blank
 * cell over throwing. The optional {@link #resolveStrict} variant throws for
 * cases where the caller needs to fail loudly (e.g. audit test runs).
 *
 * <p>Reused by every renderer in Phase 2: HTML preview (this session), PDF
 * pack-slip / invoice (2b), and ZPL shipping label (2c) all resolve the same
 * tokens against the same context, so a template built with the drag-drop
 * builder renders identically across the three output paths.
 */
public final class TemplateBindingResolver {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-\\[\\]]+)\\s*}}");

    private TemplateBindingResolver() { /* static utility */ }

    /** Replace every {@code {{path}}} in {@code template} with its resolved
     *  value from {@code context}. Missing paths become empty strings. */
    public static String resolve(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = valueOrEmpty(context, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Like {@link #resolve} but throws when a token can't be resolved.
     *  Useful for template validation tests + audit runs. */
    public static String resolveStrict(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object v = lookup(context, path);
            if (v == null) {
                throw new IllegalStateException("Unbound template token {{" + path + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Best-effort typed lookup — the caller knows the expected shape. */
    @SuppressWarnings("unchecked")
    public static <T> T lookupTyped(Map<String, Object> context, String path, Class<T> type) {
        Object v = lookup(context, path);
        if (v == null) return null;
        if (type.isInstance(v)) return (T) v;
        return null;
    }

    public static String valueOrEmpty(Map<String, Object> context, String path) {
        Object v = lookup(context, path);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * Walk a dotted path against the context. Each segment may either be a
     * Map key, or `[n]` for a list index (e.g. {@code items[0].sku}). Returns
     * null when any hop is missing — the caller decides whether to surface
     * that as empty or an error.
     */
    @SuppressWarnings("unchecked")
    public static Object lookup(Map<String, Object> context, String path) {
        if (context == null || path == null || path.isEmpty()) return null;
        Object current = context;
        for (String rawSegment : path.split("\\.")) {
            if (current == null) return null;
            String segment = rawSegment;
            Integer index = null;
            // Handle `foo[3]` — split into key `foo` + index 3.
            int bracket = rawSegment.indexOf('[');
            if (bracket >= 0 && rawSegment.endsWith("]")) {
                segment = rawSegment.substring(0, bracket);
                try {
                    index = Integer.parseInt(rawSegment.substring(bracket + 1, rawSegment.length() - 1));
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
            if (segment.isEmpty()) {
                if (current instanceof List<?> list && index != null && index >= 0 && index < list.size()) {
                    current = list.get(index);
                } else {
                    return null;
                }
                continue;
            }
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(segment);
            if (index != null) {
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) return null;
                current = list.get(index);
            }
        }
        return current;
    }

    /** Empty context to use as a sensible default before real data lands. */
    public static Map<String, Object> emptyContext() {
        return new HashMap<>();
    }
}
