package com.multiship.backend.service.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Sprint 51 T3 finding #7 — SSRF guard for tenant-registered webhook
 * URLs. Before this landed, any API-key holder could POST a subscription
 * with {@code url = "http://169.254.169.254/latest/meta-data/..."} and,
 * on the next label event, receive our server's outbound POST — with
 * shipment JSON as payload. Even absent payload leak, the ATTEMPT
 * reveals internal network topology and can trigger internal endpoints
 * that trust internal-network origins.
 *
 * <p>Rejects any URL where:
 * <ol>
 *   <li>Scheme is not {@code https} (or {@code http} when explicitly
 *       allowed by env — dev only).</li>
 *   <li>Host resolves to an RFC 1918 / RFC 6598 / loopback / link-local /
 *       any-local / multicast address, OR is one of the well-known
 *       cloud-metadata hosts (AWS/GCP/Azure).</li>
 *   <li>Host resolves to an IPv6 unique-local ({@code fc00::/7}) or
 *       loopback / link-local address.</li>
 * </ol>
 *
 * <p>Validation is invoked at two points:
 * <ol>
 *   <li>{@code ExternalWebhookController.save()} — reject the CREATE/UPDATE
 *       at 400 before the row hits the DB.</li>
 *   <li>{@code ExternalWebhookDispatcher.fire()} — belt-and-braces skip
 *       for stored rows that predate this validator (fresh-DB has none,
 *       but a real prod DB migration could still surface one).</li>
 * </ol>
 *
 * <p>Both {@code allow-http} + {@code allow-private-networks} are
 * env-driven so localhost-only dev + staging can flip them independently.
 * Prod deploys keep the secure defaults.
 */
@Slf4j
@Service
public class WebhookUrlValidator {

    /** Well-known cloud metadata endpoints — hard-coded because the host
     *  is a fixed IP for AWS/GCP and a fixed hostname for Azure. If any
     *  of these responded to our webhook POST it would be a full SSRF. */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",       // AWS EC2 IMDS + GCP metadata (link-local)
            "metadata.google.internal",
            "metadata.azure.com"
    );

    @Value("${webhook.url.allow-http:false}")
    private boolean allowHttp;

    @Value("${webhook.url.allow-private-networks:false}")
    private boolean allowPrivateNetworks;

    /** Thrown by {@link #validate(String)} on any rejected URL. Callers
     *  translate to 400 / VALIDATION_FAILED. */
    public static class WebhookUrlRejectedException extends RuntimeException {
        public WebhookUrlRejectedException(String message) { super(message); }
    }

    /**
     * @throws WebhookUrlRejectedException when the URL is unsafe. Returns
     *         normally when the URL is safe to dispatch to.
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new WebhookUrlRejectedException("URL is required.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw new WebhookUrlRejectedException("URL is not well-formed: " + ex.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new WebhookUrlRejectedException("URL must include a scheme (https recommended).");
        }
        String lowerScheme = scheme.toLowerCase();
        if ("https".equals(lowerScheme)) {
            // ok
        } else if ("http".equals(lowerScheme) && allowHttp) {
            // ok, dev only
        } else {
            throw new WebhookUrlRejectedException(
                    "URL scheme '" + scheme + "' is not allowed. Use https"
                            + (allowHttp ? " or http (dev)." : "."));
        }

        validateHost(uri.getHost(), "URL host");
    }

    /**
     * Audit R2 #344 — public host-only validation reused by non-URL
     * destinations (SFTP, PRINTER — output-destinations page). Same
     * classification as {@link #validate(String)}'s host branch:
     *   - null/blank → rejected;
     *   - cloud-metadata host → always rejected (no env flag lifts);
     *   - unresolvable → rejected;
     *   - resolves to private / loopback / link-local / RFC 6598 /
     *     IPv6 unique-local → rejected unless
     *     {@code webhook.url.allow-private-networks=true}.
     *
     * @param host  bare hostname (no scheme, no port)
     * @param label operator-friendly noun for error messages
     *              (e.g. "SFTP host", "Printer host"). Falls back to
     *              "Host" when blank.
     */
    public void validateHost(String host, String label) {
        String noun = (label == null || label.isBlank()) ? "Host" : label;
        if (host == null || host.isBlank()) {
            throw new WebhookUrlRejectedException(noun + " is missing.");
        }
        String lowerHost = host.toLowerCase();

        // Cloud metadata hosts are always rejected — no env flag can lift
        // this. Even in dev these routes should never receive outbound
        // connections; the risk of accidentally shipping a dev override to
        // prod outweighs any conceivable dev use case.
        if (METADATA_HOSTS.contains(lowerHost)) {
            throw new WebhookUrlRejectedException(
                    noun + " is a cloud metadata endpoint; refusing to connect to " + host + ".");
        }

        if (allowPrivateNetworks) {
            return;
        }

        // Resolve to IP(s); if the host is a literal IP this returns just
        // that address. Multi-A-record hosts get all resolved addresses
        // checked so an attacker can't defeat the check by registering a
        // DNS name that resolves to one public and one private address.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new WebhookUrlRejectedException(noun + " does not resolve: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                throw new WebhookUrlRejectedException(
                        noun + " " + host + " resolves to a private/loopback/link-local address ("
                                + addr.getHostAddress() + "); not allowed.");
            }
        }
    }

    /**
     * Redirects to a boolean-returning variant for the dispatcher path
     * that wants to skip a dispatch without throwing (stored bad rows).
     * True when the URL is unsafe. Logs a WARN.
     */
    public boolean isBlocked(String url) {
        try {
            validate(url);
            return false;
        } catch (WebhookUrlRejectedException ex) {
            log.warn("Skipping webhook dispatch to unsafe URL: {}", ex.getMessage());
            return true;
        }
    }

    /**
     * Address classes that we refuse to dispatch to. Grouped by risk:
     * <ul>
     *   <li>{@code isAnyLocalAddress} — 0.0.0.0 / :: which some stacks
     *       route to loopback.</li>
     *   <li>{@code isLoopbackAddress} — 127.0.0.1 / ::1.</li>
     *   <li>{@code isLinkLocalAddress} — 169.254/16 and fe80::/10
     *       (covers AWS metadata + Windows APIPA).</li>
     *   <li>{@code isSiteLocalAddress} — RFC 1918 for IPv4 (10/8, 172.16/12,
     *       192.168/16) and site-local IPv6 (deprecated but still resolvable).</li>
     *   <li>{@code isMulticastAddress} — 224.0.0.0/4, ff00::/8.</li>
     *   <li>Manual: RFC 6598 shared address space (100.64.0.0/10) — Java
     *       doesn't classify this as site-local.</li>
     *   <li>Manual: IPv6 unique-local (fc00::/7).</li>
     * </ul>
     */
    boolean isBlocked(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // RFC 6598 shared (carrier-grade NAT): 100.64.0.0/10
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        } else if (raw.length == 16) {
            // IPv6 unique-local fc00::/7 → high 7 bits == 0b1111110
            int first = raw[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }
}
