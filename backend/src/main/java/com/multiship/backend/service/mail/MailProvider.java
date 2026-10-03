package com.multiship.backend.service.mail;

import java.util.Map;
import java.util.Set;

/**
 * A4.1 — SPI for mail-delivery providers. Register a {@code @Component}
 * implementation with a unique {@link #kind()} and it becomes selectable
 * from {@code /settings/mail}.
 *
 * <p>Adding a provider = a new impl class + (optional) a Maven dep for the
 * provider's SDK. No changes to {@link MailProviderRegistry} or
 * {@link ConfiguredMailSender}: the registry discovers all beans at boot.
 *
 * <p>A4.1 ships SMTP only. A4.3 adds SENDGRID / SES / POSTMARK impls.
 */
public interface MailProvider {

    /** Uppercase discriminator stored in {@code mail_provider.kind}. */
    String kind();

    /**
     * Config keys the provider requires — used by the admin FE to render
     * the correct input fields and to fail-validate before {@link #send}.
     * SMTP example: {@code host, port, username, from_address, use_tls}.
     */
    Set<String> requiredConfigKeys();

    /**
     * Config keys that hold secrets — stored encrypted at rest via
     * {@link com.multiship.backend.config.CryptoService} and never returned
     * from the config-read endpoint.
     */
    Set<String> secretConfigKeys();

    /**
     * Deliver one message. Config values come pre-decrypted; the provider
     * never touches the DB. {@code body} is treated as plain text in A4.1;
     * A4.2 will introduce Handlebars-rendered HTML with a text fallback.
     *
     * @throws MailSendException on any provider error (network, auth, quota).
     */
    void send(String to, String subject, String body, Map<String, String> config);
}
