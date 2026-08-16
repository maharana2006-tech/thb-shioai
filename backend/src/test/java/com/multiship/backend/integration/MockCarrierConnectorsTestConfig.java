package com.multiship.backend.integration;

import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.DhlConnector;
import com.multiship.backend.service.carriers.FedExConnector;
import com.multiship.backend.service.carriers.StampsConnector;
import com.multiship.backend.service.carriers.UpsConnector;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Sprint 51 carriers-be-integration — replace every real
 * {@link CarrierConnector} @Component with a pre-stubbed Mockito mock at
 * the bean-definition layer so downstream constructors (notably
 * {@link com.multiship.backend.service.external.ExternalApiService},
 * which calls {@code connector.getCarrierCode().toUpperCase(...)} inside
 * its constructor) get non-null stubs on context load.
 *
 * <p><b>Why not {@code @MockitoBean}?</b> {@code @MockitoBean} installs
 * the override at bean-definition time, but the resulting Mockito mock
 * is created with the default no-op answer — so
 * {@code getCarrierCode()} returns {@code null} and any @Component whose
 * constructor invokes it crashes context load. We need pre-stubbed mocks
 * <b>before</b> Spring finishes wiring the singletons — a
 * {@link TestConfiguration @TestConfiguration} bean solves that.
 *
 * <p><b>Why not {@code @Primary}?</b> {@code @Primary} disambiguates
 * single-type autowires but leaves BOTH beans (real + mock) in a
 * {@code List<CarrierConnector>} injection — the CarrierServiceImpl
 * lookup would then pick the real UPS bean at random, silently defeating
 * the guard. Using bean names that match the {@code @Component} default
 * ({@code upsConnector}, {@code fedExConnector}, ...) plus
 * {@link org.springframework.boot.autoconfigure.SpringBootApplication#allowBeanDefinitionOverriding}
 * (enabled via a {@code spring.main.allow-bean-definition-overriding=true}
 * @TestPropertySource on the test class) makes the @Bean here truly
 * <b>replace</b> the @Component — the list collection then contains only
 * the mocks.
 *
 * <p>Test classes {@code @Autowired} the mock beans by concrete type and
 * layer additional per-test stubs (return values for
 * {@code getAccessToken}, {@code connect}, ...) inside {@code @BeforeEach}.
 *
 * <p>This is layer 1 of the three-layer anti-fallback guard — see
 * {@link ForbidOutboundHttpTestConfig} for layers 2 + 3.
 */
@TestConfiguration
public class MockCarrierConnectorsTestConfig {

    /** Names match the default @Component bean names so override wins
     *  when {@code spring.main.allow-bean-definition-overriding=true}. */
    @Bean(name = "upsConnector")
    public UpsConnector upsMock() {
        return prime(Mockito.mock(UpsConnector.class), "UPS", "UPS");
    }

    @Bean(name = "fedExConnector")
    public FedExConnector fedExMock() {
        return prime(Mockito.mock(FedExConnector.class), "FEDEX", "FedEx");
    }

    @Bean(name = "stampsConnector")
    public StampsConnector stampsMock() {
        return prime(Mockito.mock(StampsConnector.class), "USPS", "USPS");
    }

    @Bean(name = "dhlConnector")
    public DhlConnector dhlMock() {
        return prime(Mockito.mock(DhlConnector.class), "DHL", "DHL");
    }

    /** Stub the minimum surface every constructor touches:
     *  {@code getCarrierCode()} (ExternalApiService key), {@code getCarrierName()}
     *  (list endpoints), and {@code getConfiguration()} (list + status endpoints). */
    private static <T extends CarrierConnector> T prime(T mock, String code, String name) {
        Mockito.when(mock.getCarrierCode()).thenReturn(code);
        Mockito.when(mock.getCarrierName()).thenReturn(name);
        Mockito.when(mock.getConfiguration()).thenReturn(new CarrierConnector.CarrierConfiguration(
                code, name, null, null, null, null, null, null, null,
                null, "https://docs.example/" + code, "Connect via mock",
                null, null, null, "SANDBOX", true));
        return mock;
    }
}
