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
 * <p>Sprint 49 Tier 0: a blank secret alone no longer implies "trust the
 * payload". Per-carrier opt-in is required for unsigned mode via
 * {@code webhook.unsigned.{carrier}=true}, matching the older
 * IP-allowlist deployments. Without the opt-in, unsigned webhooks are
 * rejected (401) so an attacker cannot POST arbitrary shipment updates.
 */
@Component
@ConfigurationProperties(prefix = "webhook")
@Getter
@Setter
public class WebhookProperties {

    private final Secrets secrets = new Secrets();

    /**
     * Per-carrier opt-in for unsigned webhooks. Applies only when the
     * matching secret is blank; ignored when a secret is configured.
     */
    private final Unsigned unsigned = new Unsigned();

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

    public boolean allowsUnsigned(String carrierCode) {
        if (carrierCode == null) return false;
        return switch (carrierCode.trim().toUpperCase(Locale.ROOT)) {
            case "UPS" -> unsigned.ups;
            case "FEDEX" -> unsigned.fedex;
            case "DHL" -> unsigned.dhl;
            case "USPS", "STAMPS" -> unsigned.stamps;
            default -> false;
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

    @Getter
    @Setter
    public static class Unsigned {
        private boolean ups;
        private boolean fedex;
        private boolean dhl;
        private boolean stamps;
    }
}
