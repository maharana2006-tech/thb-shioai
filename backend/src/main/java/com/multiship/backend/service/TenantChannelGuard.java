package com.multiship.backend.service;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.TenantSettingsService.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

/**
 * Gate for order-intake surfaces (external API + WMS pull) that
 * enforces the per-tenant D2C/B2B allowlist stored in
 * {@link TenantSettingsService}.
 *
 * <p>Force-picking default (per the design call): a tenant with NO
 * configured channels rejects everything. Operator MUST visit
 * /settings/system and pick at least one channel before customer
 * integrations work.
 *
 * <p>Two entry points:
 * <ol>
 *   <li>{@link #requireConfigured(String)} — enforces just "has the
 *       tenant picked at least one channel". Used by WMS pull (which
 *       ingests PENDING orders whose channel isn't known until later).</li>
 *   <li>{@link #requireChannel(String, Channel)} — enforces the strict
 *       per-order check. Used by the external API where the incoming
 *       payload has enough to classify.</li>
 * </ol>
 *
 * <p>All failures throw {@link ChannelNotEnabledException} (extends
 * {@link AccessDeniedException} so the existing Spring-Security
 * error handler maps it to 403 with body errorCode
 * {@link ErrorCode#TENANT_CHANNEL_NOT_ENABLED}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantChannelGuard {

    private final TenantSettingsService settings;

    /**
     * Ensure the tenant has picked at least one channel. Used by
     * intake surfaces that don't classify per-order (WMS pull).
     */
    public void requireConfigured(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            // No tenant scope on the request → decline. This surface
            // shouldn't be reachable from an unauthenticated caller;
            // if we get here without a tenant, fail closed.
            throw denied(null, EnumSet.noneOf(Channel.class),
                    "No tenant scope on the request.");
        }
        EnumSet<Channel> enabled = settings.getEnabledChannels(tenantCode);
        if (enabled.isEmpty()) {
            throw denied(tenantCode, enabled,
                    "Configure allowed shipping channels at /settings/system "
                            + "before submitting orders.");
        }
    }

    /**
     * Ensure the tenant's allowlist includes the given channel.
     * Used by the external API where the payload gives us enough to
     * classify (explicit {@code channel} field or company presence).
     */
    public void requireChannel(String tenantCode, Channel channel) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw denied(null, EnumSet.noneOf(Channel.class),
                    "No tenant scope on the request.");
        }
        EnumSet<Channel> enabled = settings.getEnabledChannels(tenantCode);
        if (enabled.isEmpty()) {
            throw denied(tenantCode, enabled,
                    "Configure allowed shipping channels at /settings/system "
                            + "before submitting orders.");
        }
        if (channel == null || !enabled.contains(channel)) {
            throw denied(tenantCode, enabled,
                    "This tenant does not accept " + (channel == null ? "unclassified" : channel.name())
                            + " orders. Enabled channels: " + enabled + ".");
        }
    }

    /**
     * Classify an incoming order into a channel using the same
     * precedence as {@code CarrierServiceImpl.resolveOrderChannel}
     * (extracted here so both the persist path and the intake gate
     * apply identical rules):
     *
     * <ol>
     *   <li>Explicit {@code channel} field (if present + parseable) wins.</li>
     *   <li>Recipient residential=true → D2C (a home business is still
     *       a residence for carrier rating).</li>
     *   <li>Recipient company present → B2B.</li>
     *   <li>Default D2C.</li>
     * </ol>
     */
    public static Channel classify(String explicitChannel, Boolean residential, String company) {
        if (StringUtils.hasText(explicitChannel)) {
            Optional<Channel> parsed = Channel.parse(explicitChannel);
            if (parsed.isPresent()) return parsed.get();
        }
        if (Boolean.TRUE.equals(residential)) return Channel.D2C;
        if (StringUtils.hasText(company)) return Channel.B2B;
        return Channel.D2C;
    }

    private ChannelNotEnabledException denied(String tenantCode, EnumSet<Channel> enabled, String detail) {
        String tenantLabel = tenantCode == null ? "(unknown)" : tenantCode.toUpperCase(Locale.ROOT);
        log.warn("channel-gate: tenant={} enabled={} → 403 TENANT_CHANNEL_NOT_ENABLED ({})",
                tenantLabel, enabled, detail);
        return new ChannelNotEnabledException(detail);
    }

    /**
     * Marker exception so callers (external-API layer + WMS controller)
     * can catch specifically and map to their preferred wire format.
     * Extends {@link AccessDeniedException} so Spring Security's default
     * 403 mapping picks it up naturally.
     */
    public static class ChannelNotEnabledException extends AccessDeniedException {
        public ChannelNotEnabledException(String message) {
            super(message);
        }

        public ErrorCode errorCode() {
            return ErrorCode.TENANT_CHANNEL_NOT_ENABLED;
        }
    }
}
