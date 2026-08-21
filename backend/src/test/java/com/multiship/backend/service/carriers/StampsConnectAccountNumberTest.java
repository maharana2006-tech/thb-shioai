package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.exception.CarrierConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.multiship.backend.service.carriers.CarrierConnector.CarrierConnectionResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for the {@link StampsConnector#connect} bug where the
 * accountNumber argument was silently dropped.
 *
 * <p>Origin: {@code connect(clientId, secret, accountNumber)} called the
 * 2-arg {@code getAccessToken(clientId, secret)} overload, which forwards
 * {@code accountNumber=null} to the 4-arg version, which then throws
 * {@code CarrierConnectionException("Stamps.com verification needs the
 * account number as the SWSIM Username.")}. Effect: every
 * {@code POST /carriers/connect} for USPS returned 500 regardless of what
 * the caller supplied — the argument was there in the signature but never
 * used.
 *
 * <p>These tests pin the two contract shapes the fix restores:
 * <ol>
 *   <li>Missing accountNumber → still throws {@link CarrierConnectionException}
 *       with the same actionable message (unchanged contract for that path).</li>
 *   <li>Present accountNumber → does NOT throw the "needs the account number"
 *       error (the pre-fix bug); passes through to SWSIM auth which fails
 *       further on because there's no live SWSIM in unit-test scope — that's
 *       fine, the point is the early guard doesn't fire.</li>
 * </ol>
 */
class StampsConnectAccountNumberTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        // `stamps` on CarrierProperties is a final @NestedConfigurationProperty
        // instance, so populate it via the exposed getter rather than a setter.
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setAuthUrl("http://localhost:1/swsim");
        s.setSandboxAuthUrl("http://localhost:1/swsim");
        s.setApiBaseUrl("http://localhost:1/swsim");
        s.setSandboxUrl("http://localhost:1/swsim");
        s.setApiVersion("v135");
        props.setDefaultEnvironment("SANDBOX");
        connector = new StampsConnector(props, new ObjectMapper());
    }

    @Test
    void connect_blankAccountNumber_throwsActionableException() {
        // The original guard fires when accountNumber is blank. That's still
        // correct behaviour — SWSIM AuthenticateUser NEEDS the Username. We
        // only fixed the case where a NON-blank accountNumber was silently
        // dropped in transit to the guard.
        CarrierConnectionException ex = assertThrows(CarrierConnectionException.class,
                () -> connector.connect("01234567-89ab-cdef-0123-456789abcdef",
                        "some-secret", "  "));
        // Message must still surface the "needs the account number" text so
        // the operator knows to fill the Username field.
        assertNotNull(ex.getMessage(), "exception must carry a message");
        if (!ex.getMessage().toLowerCase().contains("account number")) {
            fail("Expected 'account number' in the guard's exception message; got: "
                    + ex.getMessage());
        }
    }

    @Test
    void connect_withAccountNumber_returnsSuccessfully_notThrowingTheMissingAccountNumberGuard() {
        // Regression guard for the actual bug: the 2-arg getAccessToken
        // overload used to drop the accountNumber. Pre-fix, connect() then
        // threw CarrierConnectionException("needs the account number as the
        // SWSIM Username") on EVERY call regardless of what the caller
        // supplied. Post-fix, the accountNumber threads through to the
        // 4-arg overload, past the guard, into the SWSIM AuthenticateUser
        // call. With no live SWSIM (setUp uses localhost:1) that call is
        // caught by the outer try/catch, which sets LAST_AUTH_DETAIL and
        // returns a "-local-" fallback token — so connect() returns a
        // CarrierConnectionResult carrying the fallback token WITHOUT
        // throwing. That's the pre-fix-bug-is-gone signal.
        CarrierConnectionResult result = connector.connect(
                "01234567-89ab-cdef-0123-456789abcdef",
                "some-secret",
                "usps-username-here");

        assertNotNull(result, "connect() must return a result now that accountNumber isn't dropped");
        assertEquals("USPS", result.carrierCode());
        assertEquals("usps-username-here", result.accountNumber(),
                "the accountNumber the caller passed must echo back — proves it didn't get dropped mid-call");
        // The token will be a "-local-" fallback because SWSIM is unreachable
        // in tests. What matters is that we got HERE at all — pre-fix, the
        // guard fired and threw before this point was ever reachable.
        assertNotNull(result.accessToken(), "accessToken (real or fallback) must be present");
    }
}
