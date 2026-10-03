package com.multiship.backend.service.mail;

/** A4.2 — no {@code notification_template} row for the given key. */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String key) {
        super("notification_template not found: " + key);
    }
}
