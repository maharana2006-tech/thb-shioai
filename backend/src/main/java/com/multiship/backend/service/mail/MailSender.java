package com.multiship.backend.service.mail;

/**
 * Outbound email surface. The only production impl is
 * {@link ConfiguredMailSender}, which delegates to whichever
 * {@link MailProvider} row is active in {@code mail_provider} (managed
 * from /settings/mail). When no provider is active the send is INFO-
 * logged rather than dropped, which keeps fresh installs bootable.
 */
public interface MailSender {

    void send(String to, String subject, String body);
}
