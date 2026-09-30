package com.multiship.backend.service.mail;

/** A4.1 — thrown by {@link MailProvider#send} on any provider-side failure. */
public class MailSendException extends RuntimeException {

    public MailSendException(String message) {
        super(message);
    }

    public MailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
