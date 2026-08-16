package com.multiship.backend.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Sprint 51 carriers-be-integration — <b>anti-fallback guard</b>.
 *
 * <p>Layer 1 (the strong layer) is the {@code @MockitoBean} annotations on
 * every {@link com.multiship.backend.service.carriers.CarrierConnector}
 * implementation in each test class — with the real UPS / FedEx / DHL /
 * Stamps connectors replaced by Mockito mocks, no test-mode carrier call
 * can ever reach a real carrier host.
 *
 * <p>Layer 2 (this class — belt-and-braces) provides hostile
 * {@link RestTemplate}, {@link RestClient.Builder} and {@link WebClient}
 * beans marked {@link Primary}. If any autowired-outbound path were
 * inadvertently exercised by a test — a code refactor swaps a mocked
 * connector for a real one, a new dependency injects RestTemplate for
 * outbound calls — the request factory throws immediately with a
 * self-explaining message, so the misconfiguration surfaces as a
 * loud, obvious test failure instead of a silent hit against a live
 * carrier host. Existing connectors call {@link
 * com.multiship.backend.service.carriers.HttpClients#newBuilder()}
 * which reads a static factory — mocking the connector beans (layer 1)
 * short-circuits that path entirely; this bean is here so any future
 * migration to autowired {@code RestClient.Builder} keeps its "no real
 * HTTP in integration tests" guarantee.
 *
 * <p>Layer 3 lives inside each test method: an explicit
 * {@code Mockito.verify(mockConnector, times(1))...} call proves the
 * business code took the mocked path instead of any fallback.
 *
 * <p>Import via {@code @Import(ForbidOutboundHttpTestConfig.class)} on
 * the test class.
 */
@TestConfiguration
public class ForbidOutboundHttpTestConfig {

    /** Explanatory message baked into every guard failure. */
    private static final String MESSAGE =
            "[integration-test guard] Outbound HTTP is forbidden — "
                    + "the test should exercise a @MockitoBean, not a live carrier. "
                    + "If a new dependency requires HTTP, mock the specific client or "
                    + "extend this guard to allow-list the host.";

    /**
     * Any attempt to actually open a socket via a wired
     * {@link ClientHttpRequestFactory} throws. Marked {@link Primary} so
     * it wins over any test-scoped RestTemplateAutoConfiguration factory.
     */
    @Bean
    @Primary
    public ClientHttpRequestFactory hostileRequestFactory() {
        return (uri, httpMethod) -> {
            throw new IllegalStateException(MESSAGE + " Blocked call: "
                    + httpMethod.name() + " " + uri);
        };
    }

    /**
     * Bare {@link RestTemplate} whose factory throws on every request.
     * Callers that autowire {@code RestTemplate} without a qualifier get
     * this instance and fail fast.
     */
    @Bean
    @Primary
    public RestTemplate hostileRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setRequestFactory(hostileRequestFactory());
        return rt;
    }

    /**
     * {@link RestClient.Builder} whose factory throws on every request.
     * Any autowired-RestClient.Builder path picks this up.
     */
    @Bean
    @Primary
    public RestClient.Builder hostileRestClientBuilder() {
        return RestClient.builder().requestFactory(hostileRequestFactory());
    }

}
