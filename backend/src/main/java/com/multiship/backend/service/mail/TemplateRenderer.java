package com.multiship.backend.service.mail;

import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * A4.2 — thin wrapper around Handlebars.java. One instance held forever;
 * compiled templates aren't cached here because notification_template rows
 * are hot-editable from /settings/notifications-templates — caching would
 * defeat that. Compilation is microseconds; the outbound-email latency
 * dominates by 5-6 orders of magnitude.
 */
@Slf4j
@Service
public class TemplateRenderer {

    private final Handlebars handlebars;

    public TemplateRenderer() {
        // A4.2 ships plain-text email only; HTML escaping (default) would
        // turn "?token=abc" into "?token&#x3D;abc". Switch to NOOP; A4.2+
        // can add an HTML-templates variant when the FE editor gains a
        // "content type" toggle.
        this.handlebars = new Handlebars().with(EscapingStrategy.NOOP);
        // {{eq a b}} / {{gt}} / {{lt}} and friends. Every seed template
        // uses at most {{#if x}}, but ops-authored templates want more.
        this.handlebars.registerHelpers(ConditionalHelpers.class);
    }

    /**
     * @param source Handlebars source (never null / blank).
     * @param vars   variables map (may be empty; nulls tolerated).
     * @throws MailSendException if the template fails to compile or render.
     */
    public String render(String source, Map<String, ?> vars) {
        if (source == null) {
            throw new MailSendException("Template source is null");
        }
        try {
            Template compiled = handlebars.compileInline(source);
            return compiled.apply(vars == null ? Map.of() : vars);
        } catch (HandlebarsException ex) {
            throw new MailSendException(describe(ex), ex);
        } catch (IOException ex) {
            throw new MailSendException("Template render failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * BUG-138 — "inline@29816619:1:21: found '}}', expected '{{/if}}'" becomes
     * "Line 1, column 21: found '}}', expected '{{/if}}'", so the editor can
     * point at the mistake instead of echoing the engine's internals.
     */
    static String describe(HandlebarsException ex) {
        var err = ex.getError();
        if (err != null && err.reason != null && !err.reason.isBlank()) {
            return "Line " + err.line + ", column " + err.column + ": " + err.reason.trim();
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        return "Template render failed: " + msg.replaceFirst("^inline@[0-9a-f]+:(\\d+):(\\d+):\\s*", "line $1, column $2: ");
    }
}
