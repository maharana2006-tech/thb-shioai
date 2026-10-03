package com.multiship.backend.service.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A4.2 — Handlebars renderer contract. */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesVars() {
        String out = renderer.render("Hello {{name}}, code {{code}}",
                Map.of("name", "Alice", "code", 42));
        assertEquals("Hello Alice, code 42", out);
    }

    @Test
    void conditionalRendersWhenPresent() {
        String out = renderer.render("Hi{{#if by}} by {{by}}{{/if}}", Map.of("by", "Ops"));
        assertEquals("Hi by Ops", out);
    }

    @Test
    void conditionalOmitsWhenBlank() {
        String out = renderer.render("Hi{{#if by}} by {{by}}{{/if}}", Map.of("by", ""));
        assertEquals("Hi", out);
    }

    @Test
    void unknownVarRendersEmpty() {
        // Handlebars renders missing keys as empty by default — this lets a
        // template safely add optional slots without breaking old call sites.
        String out = renderer.render("[{{missing}}]", Map.of());
        assertEquals("[]", out);
    }

    @Test
    void nullVarsMapTreatedAsEmpty() {
        String out = renderer.render("plain text", null);
        assertEquals("plain text", out);
    }

    @Test
    void malformedTemplateSurfacesAsMailSendException() {
        Map<String, Object> vars = new HashMap<>();
        MailSendException ex = assertThrows(MailSendException.class,
                () -> renderer.render("{{#if x}}unclosed", vars));
        assertTrue(ex.getMessage().toLowerCase().contains("template"));
    }
}
