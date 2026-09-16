package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.VoidResult;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PR-D — {@link UspsDirectConnector#voidShipment} boundary + happy-path
 * unit tests.
 *
 * <p>Because USPS APIs v3 have NO synchronous void endpoint (documented
 * gotcha #9 in {@code docs/usps-direct-integration.md}), this method
 * never talks to USPS. The tests verify:
 * <ul>
 *   <li>Blank / null tracking numbers throw
 *       {@link IllegalArgumentException} — refusing to queue a nameless
 *       refund keeps the audit trail honest.</li>
 *   <li>Happy path returns a {@link VoidResult} with {@code voided=true}
 *       and status {@code VOID_PENDING_RECONCILIATION} so the existing
 *       {@code VoidServiceImpl} flow flips local status to VOIDED.</li>
 *   <li>{@code -local-} / null tokens are ACCEPTABLE (differs from every
 *       other USPS_DIRECT method) — no USPS call means no auth needed.</li>
 *   <li>No HTTP client is instantiated / called — verifyNoInteractions
 *       on the injected caches + jdbc.</li>
 * </ul>
 */
class UspsDirectVoidTest {

    private CarrierProperties props;
    private ObjectMapper objectMapper;
    private UspsOAuthTokenCache tokenCache;
    private UspsPaymentAuthCache paymentAuthCache;
    private JdbcTemplate jdbc;
    private UspsDirectConnector connector;

    @BeforeEach
    void setUp() {
        props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        objectMapper = new ObjectMapper();
        // Mock the caches AND jdbc so we can assert verifyNoInteractions
        // — the void path must not need any of them.
        tokenCache = mock(UspsOAuthTokenCache.class);
        paymentAuthCache = mock(UspsPaymentAuthCache.class);
        jdbc = mock(JdbcTemplate.class);
        connector = new UspsDirectConnector(props, objectMapper, tokenCache, paymentAuthCache, jdbc);
    }

    // ================================================================
    // Boundary guards
    // ================================================================

    @Test
    void blankTrackingNumberThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.voidShipment("", "tok", "SANDBOX", "ACCT-1", "US"));
        assertTrue(ex.getMessage().contains("tracking number"),
                "message should name tracking number; got: " + ex.getMessage());
    }

    @Test
    void nullTrackingNumberThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> connector.voidShipment(null, "tok", "SANDBOX", "ACCT-1", "US"));
    }

    @Test
    void whitespaceOnlyTrackingNumberThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> connector.voidShipment("   ", "tok", "SANDBOX", "ACCT-1", "US"));
    }

    // ================================================================
    // Happy path — optimistic queued verdict
    // ================================================================

    @Test
    void happyPathReturnsOptimisticVoidedResult() {
        VoidResult result = connector.voidShipment(
                "9400111899223197428301", "real-usps-oauth-token",
                "SANDBOX", "ACCT-1", "US");
        assertEquals("9400111899223197428301", result.trackingNumber());
        assertTrue(result.voided(),
                "PR-D: voidShipment returns voided=true optimistically so VoidServiceImpl flips to VOIDED locally.");
        assertEquals("VOID_PENDING_RECONCILIATION", result.status());
        assertTrue(result.message().contains("reconciliation"),
                "message should reference reconciliation workflow; got: " + result.message());
        assertNull(result.rawResponse(),
                "no HTTP call is made, so rawResponse stays null.");
    }

    @Test
    void happyPathAcceptsLocalFallbackToken() {
        // Every OTHER USPS_DIRECT method rejects -local- with IllegalStateException.
        // The void path deliberately accepts it because no USPS call is made —
        // an unconfigured platform must not block the local optimistic flip.
        VoidResult result = connector.voidShipment(
                "9400111899223197428301", "usps-direct-local-ACCT-1",
                "SANDBOX", "ACCT-1", "US");
        assertTrue(result.voided());
        assertEquals("VOID_PENDING_RECONCILIATION", result.status());
    }

    @Test
    void happyPathAcceptsNullToken() {
        // Same rationale as the -local- case above. A null token means the
        // caller had nothing to hand us; still no USPS call, still no
        // reason to block the local flip.
        VoidResult result = connector.voidShipment(
                "9400111899223197428301", null, "SANDBOX", "ACCT-1", "US");
        assertTrue(result.voided());
        assertEquals("VOID_PENDING_RECONCILIATION", result.status());
    }

    @Test
    void happyPathAcceptsBlankToken() {
        VoidResult result = connector.voidShipment(
                "9400111899223197428301", "", "SANDBOX", "ACCT-1", "US");
        assertTrue(result.voided());
        assertEquals("VOID_PENDING_RECONCILIATION", result.status());
    }

    // ================================================================
    // No HTTP / no side-effects
    // ================================================================

    @Test
    void voidShipmentDoesNotTouchUspsAtAll() {
        connector.voidShipment("9400111899223197428301", "real-token",
                "SANDBOX", "ACCT-1", "US");
        // The optimistic void method must not mint a token, mint a
        // payment-authorization, or run the tenant lookup query — the
        // reconciliation job reads USPS's report separately. Any of
        // these interactions would defeat the "never lies synchronously"
        // design intent.
        verifyNoInteractions(tokenCache);
        verifyNoInteractions(paymentAuthCache);
        verifyNoInteractions(jdbc);
    }

    @Test
    void voidShipmentIsIndependentOfAccountNumberAndCountry() {
        // accountNumber + senderCountryCode are on the interface for
        // signature parity with FedEx/DHL — USPS Direct doesn't use them
        // for the queued verdict. Verify null / blank inputs don't blow
        // up (some callers may not have them populated for a legacy row).
        VoidResult r1 = connector.voidShipment("TRACK1", "tok", "SANDBOX", null, null);
        VoidResult r2 = connector.voidShipment("TRACK2", "tok", "SANDBOX", "", "");
        assertTrue(r1.voided());
        assertTrue(r2.voided());
    }
}
