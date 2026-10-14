package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-020 / BUG-021 — pickup, close-out, void, tracking… never pushed the SERA
 * refresh token, so every one of them got a fallback token ("needs live
 * credentials"). The connector now finds the account's own saved token.
 */
class StampsSeraSavedTokenTest {

    private static CarrierAccountRef usps(String clientId, String env, String token) {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setAccountNumber("874157");
        a.setCarrierCode("USPS");
        a.setClientId(clientId);
        a.setEnvironment(env);
        a.setStampsRefreshToken(token);
        return a;
    }

    @Test
    void aCallerThatPushesNoTokenStillGetsALiveOneFromTheSavedAccount() {
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SERA");
        StampsConnector connector = new StampsConnector(props, new ObjectMapper());
        CarrierAccountRefRepository repo = mock(CarrierAccountRefRepository.class);
        when(repo.findByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("874157", "USPS")).thenReturn(List.of(
                usps("other-client", "SANDBOX", "rt-other"),
                usps("cid", "PRODUCTION", "rt-prod"),
                usps("cid", "SANDBOX", "rt-sandbox")));
        StampsSeraOAuthService oauth = mock(StampsSeraOAuthService.class);
        when(oauth.refreshToken(eq("rt-sandbox"), eq("cid"), any(), eq("SANDBOX")))
                .thenReturn(StampsSeraOAuthService.TokenExchangeResult.success("live-access", null, 3600));
        ReflectionTestUtils.setField(connector, "accountRefRepository", repo);
        ReflectionTestUtils.setField(connector, "seraOAuthService", oauth);

        String token = connector.getAccessToken("cid", "secret", "874157", "SANDBOX");

        assertEquals("live-access", token);
        assertFalse(token.contains("-local-"));
    }
}
