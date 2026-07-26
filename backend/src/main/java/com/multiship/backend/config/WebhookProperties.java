package com.multiship.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Sprint 36 — per-carrier HMAC shared secrets for the webhook receiver.
 * Configured via {@code webhook.secrets.{carrier}} in
 * application.properties / application.yml — e.g.:
 *
 * <pre>
 * webhook.secrets.ups=abc123
 * webhook.secrets.fedex=def456
 * webhook.secrets.dhl=ghi789
 * webhook.secrets.stamps=jkl012
 * </pre>
 *
 * <p>Blank secrets are treated as "no verifiable webhook configured" —
 * the receiver logs the payload as unverified but doesn't reject the
 * carrier (some carriers use IP allowlist rather than HMAC).
 */
@Component
@ConfigurationProperties(prefix = "webhook")
@Getter
@Setter
public class WebhookProperties {

    private final Secrets secrets = new Secrets();

    public String secretFor(String carrierCode) {
        if (carrierCode == null) return null;
        return switch (carrierCode.trim().toUpperCase(Locale.ROOT)) {
            case "UPS" -> secrets.ups;
            case "FEDEX" -> secrets.fedex;
            case "DHL" -> secrets.dhl;
            case "USPS", "STAMPS" -> secrets.stamps;
            default -> null;
        };
    }

    @Getter
    @Setter
    public static class Secrets {
        private String ups = "";
        private String fedex = "";
        private String dhl = "";
        private String stamps = "";
    }
}
