package com.multiship.backend.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A4.1 — SMTP provider using Spring's {@link JavaMailSenderImpl}. Constructs
 * a fresh sender per {@link #send} call because config lives in the DB and can
 * change without a restart; the overhead is one SMTP TCP handshake per email,
 * which is the same amount of work SMTP itself does.
 *
 * <p>Required config keys: {@code host, port, username, from_address}.
 * Optional: {@code use_tls} (default {@code true}), {@code use_ssl} (default
 * {@code false}), {@code auth_required} (default {@code true}).
 * Secret keys: {@code password}.
 */
@Slf4j
@Component
public class SmtpMailProvider implements MailProvider {

    public static final String KIND = "SMTP";

    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_FROM = "from_address";
    public static final String KEY_USE_TLS = "use_tls";
    public static final String KEY_USE_SSL = "use_ssl";
    public static final String KEY_AUTH_REQUIRED = "auth_required";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of(KEY_HOST, KEY_PORT, KEY_USERNAME, KEY_FROM);
    }

    @Override
    public Set<String> secretConfigKeys() {
        return Set.of(KEY_PASSWORD);
    }

    @Override
    public void send(String to, String subject, String body, Map<String, String> config) {
        JavaMailSenderImpl sender = build(config);
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            helper.setFrom(config.get(KEY_FROM));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(msg);
            log.info("[mail:SMTP] delivered to={} subject={}", to, subject);
        } catch (MessagingException ex) {
            throw new MailSendException("SMTP send failed to " + to + ": " + ex.getMessage(), ex);
        } catch (org.springframework.mail.MailException ex) {
            throw new MailSendException("SMTP send failed to " + to + ": " + ex.getMessage(), ex);
        }
    }

    private JavaMailSenderImpl build(Map<String, String> config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(require(config, KEY_HOST));
        sender.setPort(Integer.parseInt(require(config, KEY_PORT)));
        sender.setUsername(require(config, KEY_USERNAME));
        sender.setPassword(config.getOrDefault(KEY_PASSWORD, ""));

        boolean useTls = parseBool(config.get(KEY_USE_TLS), true);
        boolean useSsl = parseBool(config.get(KEY_USE_SSL), false);
        boolean auth = parseBool(config.get(KEY_AUTH_REQUIRED), true);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(auth));
        props.put("mail.smtp.starttls.enable", Boolean.toString(useTls));
        props.put("mail.smtp.ssl.enable", Boolean.toString(useSsl));
        // Fail fast on network trouble; the caller already retries at its own layer.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        return sender;
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new MailSendException("SMTP config missing required key: " + key);
        }
        return v;
    }

    private static boolean parseBool(String s, boolean fallback) {
        if (s == null || s.isBlank()) return fallback;
        return Boolean.parseBoolean(s.trim());
    }
}
