package com.multiship.backend.service.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A4.1 — the {@link MailSender} bean every caller sees. On {@link #send} it
 * reads the currently-active row from {@code mail_provider}, loads its
 * (decrypted) config from {@code mail_config}, and delegates to the matching
 * {@link MailProvider} SPI impl.
 *
 * <p>Fallback: if no active provider is configured, logs the message at INFO
 * so dev / fresh-install boots still work without SMTP. Callers get no
 * exception in that case — parity with the retired {@code LoggingMailSender}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfiguredMailSender implements MailSender {

    private final MailConfigService configService;
    private final MailProviderRegistry registry;

    @Override
    public void send(String to, String subject, String body) {
        var active = configService.activeProvider();
        if (active.isEmpty()) {
            log.info("[mail:NO-ACTIVE-PROVIDER] to={} subject={} — configure one at /settings/mail\n{}",
                    to, subject, body);
            return;
        }
        var row = active.get();
        MailProvider provider = registry.require(row.getKind());
        Map<String, String> config = configService.loadConfig(row.getId());
        provider.send(to, subject, body, config);
    }
}
