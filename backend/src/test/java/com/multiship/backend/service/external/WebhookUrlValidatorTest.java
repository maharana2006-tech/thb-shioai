package com.multiship.backend.service.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 51 T3 finding #7 — SSRF guard coverage.
 *
 * <p>Direct-address tests bypass DNS so they run without network access
 * (isBlocked(InetAddress) is package-private). The full end-to-end
 * validate(String) path is exercised for a handful of representative
 * hosts that are guaranteed to resolve to their literal IP.
 */
class WebhookUrlValidatorTest {

    private WebhookUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookUrlValidator();
        // Defaults: strict — https only, no private networks.
        ReflectionTestUtils.setField(validator, "allowHttp", false);
        ReflectionTestUtils.setField(validator, "allowPrivateNetworks", false);
    }

    /* -------- address classification (no DNS) -------- */

    @Test
    void loopbackIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("127.0.0.1")));
        assertTrue(validator.isBlocked(InetAddress.getByName("::1")));
    }

    @Test
    void rfc1918IsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("10.0.0.1")));
        assertTrue(validator.isBlocked(InetAddress.getByName("172.16.5.10")));
        assertTrue(validator.isBlocked(InetAddress.getByName("192.168.1.1")));
    }

    @Test
    void awsMetadataAddressIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("169.254.169.254")));
    }

    @Test
    void cgnatIsBlocked() throws Exception {
        // RFC 6598: 100.64.0.0/10 — Java doesn't classify this as site-local.
        assertTrue(validator.isBlocked(InetAddress.getByName("100.64.1.1")));
        assertTrue(validator.isBlocked(InetAddress.getByName("100.127.255.255")));
    }

    @Test
    void cgnatEdgesArePublic() throws Exception {
        // Just outside the CGNAT block: public.
        assertFalse(validator.isBlocked(InetAddress.getByName("100.63.255.255")));
        assertFalse(validator.isBlocked(InetAddress.getByName("100.128.0.0")));
    }

    @Test
    void ipv6UniqueLocalIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("fc00::1")));
        assertTrue(validator.isBlocked(InetAddress.getByName("fd00:1234::1")));
    }

    @Test
    void ipv6LinkLocalIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("fe80::1")));
    }

    @Test
    void anyLocalIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("0.0.0.0")));
    }

    @Test
    void multicastIsBlocked() throws Exception {
        assertTrue(validator.isBlocked(InetAddress.getByName("224.0.0.1")));
    }

    @Test
    void publicIPv4IsAllowed() throws Exception {
        assertFalse(validator.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(validator.isBlocked(InetAddress.getByName("1.1.1.1")));
    }

    /* -------- URL-string validation -------- */

    @Test
    void nullOrBlankUrlRejected() {
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate(null));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate(""));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("   "));
    }

    @Test
    void malformedUrlRejected() {
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("not a url"));
    }

    @Test
    void nonHttpsSchemesRejectedByDefault() {
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("http://example.com/webhook"));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("file:///etc/passwd"));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("gopher://example.com"));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("ftp://example.com"));
    }

    @Test
    void httpAllowedWhenFlagSet() {
        ReflectionTestUtils.setField(validator, "allowHttp", true);
        // Still rejected because localhost resolves to loopback — but the
        // scheme check passes; if the host were a public IP this would
        // succeed. Assert the message mentions the address, not the scheme.
        WebhookUrlValidator.WebhookUrlRejectedException ex =
                assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                        () -> validator.validate("http://localhost/hook"));
        assertTrue(ex.getMessage().toLowerCase().contains("loopback")
                || ex.getMessage().toLowerCase().contains("private"),
                "expected loopback rejection, got: " + ex.getMessage());
    }

    @Test
    void metadataHostAlwaysRejectedEvenWithFlags() {
        // Both flags on — metadata still blocked (hard rule).
        ReflectionTestUtils.setField(validator, "allowHttp", true);
        ReflectionTestUtils.setField(validator, "allowPrivateNetworks", true);
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("http://169.254.169.254/latest/meta-data/"));
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("http://metadata.google.internal/computeMetadata/v1/"));
    }

    @Test
    void loopbackLiteralRejected() {
        assertThrows(WebhookUrlValidator.WebhookUrlRejectedException.class,
                () -> validator.validate("https://127.0.0.1/hook"));
    }

    @Test
    void allowPrivateNetworksSkipsAddressCheck() {
        ReflectionTestUtils.setField(validator, "allowPrivateNetworks", true);
        // With private-nets enabled, an RFC 1918 IP behind https is OK.
        assertDoesNotThrow(() -> validator.validate("https://10.0.0.1/hook"));
    }

    @Test
    void isBlockedTrueOnRejected() {
        assertTrue(validator.isBlocked("http://not-https.example.com"));
        assertTrue(validator.isBlocked("https://127.0.0.1/x"));
    }

    @Test
    void isBlockedFalseOnAcceptedPublicHost() {
        // Public DNS-resolvable host with valid scheme.
        assertFalse(validator.isBlocked("https://8.8.8.8/hook"));
    }
}
