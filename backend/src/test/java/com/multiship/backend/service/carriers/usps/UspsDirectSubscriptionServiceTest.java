package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.UspsDirectSubscriptionDTO;
import com.multiship.backend.dto.UspsDirectSubscriptionRequest;
import com.multiship.backend.model.UspsDirectSubscription;
import com.multiship.backend.model.UspsDirectSubscription.Status;
import com.multiship.backend.repository.UspsDirectSubscriptionRepository;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.UspsDirectConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UspsDirectSubscriptionService}. Uses a subclass
 * to stub the two HTTP surfaces ({@code postJson} + {@code deleteAgainstUsps})
 * — RestClient itself isn't easy to mock cleanly, but the service was
 * designed with those methods package-private so tests can override.
 *
 * <p>Also uses the real {@link UspsOAuthTokenCache} with a reflection-
 * seeded cache slot so the token path returns without calling out.
 */
class UspsDirectSubscriptionServiceTest {

    private UspsDirectSubscriptionRepository repository;
    private UspsOAuthTokenCache tokenCache;
    private SystemSettingService systemSettingService;
    private ObjectMapper objectMapper;

    private ScriptedService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(UspsDirectSubscriptionRepository.class);
        tokenCache = new UspsOAuthTokenCache(new ObjectMapper());
        systemSettingService = mock(SystemSettingService.class);
        objectMapper = new ObjectMapper();

        // Default: platform creds populated + a cached OAuth token.
        when(systemSettingService.getDecrypted(UspsDirectConnector.SETTING_CLIENT_ID))
                .thenReturn(Optional.of("platform-cid"));
        when(systemSettingService.getDecrypted(UspsDirectConnector.SETTING_CLIENT_SECRET))
                .thenReturn(Optional.of("platform-secret"));
        seedTokenCache("platform-cid", "PRODUCTION", "real-usps-oauth-token");
        seedTokenCache("platform-cid", "SANDBOX", "real-sandbox-token");

        // Repo returns the entity as-saved, populating an id.
        AtomicLong seq = new AtomicLong(1);
        when(repository.save(any(UspsDirectSubscription.class))).thenAnswer(inv -> {
            UspsDirectSubscription row = inv.getArgument(0);
            if (row.getId() == null) row.setId(seq.getAndIncrement());
            return row;
        });
        when(repository.findByUspsSubscriptionId(anyString())).thenReturn(Optional.empty());

        service = new ScriptedService(repository, tokenCache, systemSettingService, objectMapper);
    }

    // -----------------------------------------------------------
    // create
    // -----------------------------------------------------------

    @Test
    void create_happyPath_persistsWithUspsSubscriptionIdAndEncryptedSecret() throws Exception {
        service.nextPostResponse = fixture("create_response.json");

        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("MID")
                .filterValue("901234567")
                .listenerURL("https://webhook.example.com/api/v1/webhooks/carrier")
                .environment("PRODUCTION")
                .build();

        UspsDirectSubscription saved = service.create(req);

        assertNotNull(saved.getId());
        assertEquals("sub-abc-123-xyz", saved.getUspsSubscriptionId());
        assertEquals("MID", saved.getFilterType());
        assertEquals("901234567", saved.getFilterValue());
        assertEquals("https://webhook.example.com/api/v1/webhooks/carrier", saved.getListenerUrl());
        assertEquals("PRODUCTION", saved.getEnvironment());
        assertEquals(Status.ACTIVE, saved.getStatus());
        assertNotNull(saved.getSecretEncrypted());
        // Persisted secret is the plaintext (the JPA converter would encrypt
        // on the way to the DB — that path is exercised elsewhere).
        assertTrue(saved.getSecretEncrypted().length() >= 32,
                "Minted secret must clear USPS' 32-char minimum");

        // The URL captured on the outbound POST includes the subscriptions path.
        assertNotNull(service.lastPostUrl);
        assertTrue(service.lastPostUrl.endsWith("/subscriptions-tracking/v3/subscriptions"),
                "POST URL should end with the subscriptions path (was: " + service.lastPostUrl + ")");
        assertTrue(service.lastPostUrl.startsWith(UspsOAuthTokenCache.PROD_HOST),
                "PRODUCTION env should route to apis.usps.com (was: " + service.lastPostUrl + ")");
        assertEquals("real-usps-oauth-token", service.lastPostAccessToken);

        // Body carries the tenant filter, HTTPS listener, plaintext secret, JSON format.
        assertEquals("https://webhook.example.com/api/v1/webhooks/carrier",
                service.lastPostBody.get("listenerURL"));
        Map<?, ?> filterProps = (Map<?, ?>) service.lastPostBody.get("filterProperties");
        assertEquals("901234567", filterProps.get("MID"));
        assertEquals("JSON", service.lastPostBody.get("format"));
        assertNotNull(service.lastPostBody.get("secret"));

        verify(repository).save(any(UspsDirectSubscription.class));
    }

    @Test
    void create_trackingNumbersFilter_sendsJsonArray() throws Exception {
        service.nextPostResponse = fixture("create_response_tracking_numbers.json");

        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("TRACKING_NUMBERS")
                .filterValue("9400111899223197428490, 9400111899223197428491")
                .listenerURL("https://webhook.example.com/hook")
                .eventTypes("DELIVERED, OUT_FOR_DELIVERY")
                .environment("SANDBOX")
                .build();

        UspsDirectSubscription saved = service.create(req);

        assertEquals("sub-tn-777", saved.getUspsSubscriptionId());
        assertEquals("SANDBOX", saved.getEnvironment());
        assertEquals("DELIVERED, OUT_FOR_DELIVERY", saved.getEventTypes());

        // SANDBOX env → apis-tem.usps.com
        assertTrue(service.lastPostUrl.startsWith(UspsOAuthTokenCache.SANDBOX_HOST),
                "SANDBOX env should route to apis-tem.usps.com (was: " + service.lastPostUrl + ")");

        Map<?, ?> filterProps = (Map<?, ?>) service.lastPostBody.get("filterProperties");
        Object trackingNumbers = filterProps.get("trackingNumbers");
        assertTrue(trackingNumbers instanceof List, "trackingNumbers must be a JSON array");
        assertEquals(2, ((List<?>) trackingNumbers).size());

        // eventTypes must also be an array (parsed from the CSV).
        Object types = service.lastPostBody.get("eventTypes");
        assertTrue(types instanceof List, "eventTypes must be a JSON array");
        assertEquals(2, ((List<?>) types).size());
    }

    @Test
    void create_missingPlatformCreds_throwsIllegalStateAndSkipsPost() {
        when(systemSettingService.getDecrypted(UspsDirectConnector.SETTING_CLIENT_ID))
                .thenReturn(Optional.empty());

        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("MID")
                .filterValue("901234567")
                .listenerURL("https://webhook.example.com/hook")
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.create(req));
        assertTrue(ex.getMessage().contains("USPS Direct is not configured platform-wide"),
                "Error message must mention platform-wide config (was: " + ex.getMessage() + ")");
        assertTrue(ex.getMessage().contains("/settings/system"),
                "Error message must point admins at /settings/system");

        assertNull(service.lastPostUrl, "No POST should have been attempted");
        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    @Test
    void create_uspsRejects400_wrapsAndDoesNotPersist() {
        service.nextPostException = new RestClientResponseException(
                "Bad Request", HttpStatus.BAD_REQUEST,
                "Bad Request", null, "{\"error\":{\"code\":\"INVALID_FILTER\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("MID")
                .filterValue("901234567")
                .listenerURL("https://webhook.example.com/hook")
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.create(req));
        assertTrue(ex.getMessage().contains("400"),
                "Error should include status code (was: " + ex.getMessage() + ")");

        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    @Test
    void create_invalidFilterType_throwsValidationBeforeAnyHttp() {
        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("BOGUS")
                .filterValue("x")
                .listenerURL("https://webhook.example.com/hook")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req));
        assertTrue(ex.getMessage().contains("filterType"),
                "Message should name the offending field (was: " + ex.getMessage() + ")");

        assertNull(service.lastPostUrl);
        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    @Test
    void create_nonHttpsListener_throwsValidationBeforeAnyHttp() {
        UspsDirectSubscriptionRequest req = UspsDirectSubscriptionRequest.builder()
                .filterType("MID")
                .filterValue("901234567")
                .listenerURL("http://webhook.example.com/hook")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req));
        assertTrue(ex.getMessage().toLowerCase().contains("https"),
                "Message should mention HTTPS (was: " + ex.getMessage() + ")");

        assertNull(service.lastPostUrl);
        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    // -----------------------------------------------------------
    // list + getById
    // -----------------------------------------------------------

    @Test
    void list_returnsActiveRowsFromRepository() {
        UspsDirectSubscription active = UspsDirectSubscription.builder()
                .id(1L).uspsSubscriptionId("sub-a")
                .filterType("MID").filterValue("111").listenerUrl("https://x")
                .status(Status.ACTIVE).build();
        when(repository.findByStatus(Status.ACTIVE.name())).thenReturn(List.of(active));

        List<UspsDirectSubscription> rows = service.list();

        assertEquals(1, rows.size());
        assertEquals("sub-a", rows.get(0).getUspsSubscriptionId());
    }

    @Test
    void getById_delegatesToRepositoryFindById() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(7L).uspsSubscriptionId("sub-7").filterType("MID")
                .filterValue("777").listenerUrl("https://x")
                .status(Status.ACTIVE).build();
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        Optional<UspsDirectSubscription> got = service.getById(7L);

        assertTrue(got.isPresent());
        assertEquals(7L, got.get().getId());
    }

    // -----------------------------------------------------------
    // delete
    // -----------------------------------------------------------

    @Test
    void delete_happyPath_softDeletesLocalRowAndPostsDeleteToUsps() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(5L).uspsSubscriptionId("sub-live")
                .filterType("MID").filterValue("901234567")
                .listenerUrl("https://webhook.example.com/hook")
                .environment("PRODUCTION").status(Status.ACTIVE).build();
        when(repository.findById(5L)).thenReturn(Optional.of(row));

        Optional<UspsDirectSubscription> deleted = service.delete(5L);

        assertTrue(deleted.isPresent());
        assertEquals(Status.DELETED, deleted.get().getStatus());
        assertNotNull(deleted.get().getDeletedAt());

        assertNotNull(service.lastDeleteUrl);
        assertTrue(service.lastDeleteUrl.endsWith("/subscriptions-tracking/v3/subscriptions/sub-live"),
                "DELETE URL must target USPS' sub-scoped path (was: " + service.lastDeleteUrl + ")");
        assertEquals("real-usps-oauth-token", service.lastDeleteAccessToken);

        verify(repository).save(any(UspsDirectSubscription.class));
    }

    @Test
    void delete_missingRow_returnsEmpty_neverCallsUsps() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        Optional<UspsDirectSubscription> got = service.delete(99L);

        assertTrue(got.isEmpty());
        assertNull(service.lastDeleteUrl);
        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    @Test
    void delete_uspsReturns404_stillSoftDeletesLocalRow() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(6L).uspsSubscriptionId("sub-gone")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.ACTIVE).build();
        when(repository.findById(6L)).thenReturn(Optional.of(row));

        service.nextDeleteException = new RestClientResponseException(
                "Not Found", HttpStatus.NOT_FOUND, "Not Found", null, new byte[0], StandardCharsets.UTF_8);

        Optional<UspsDirectSubscription> deleted = service.delete(6L);

        assertTrue(deleted.isPresent());
        assertEquals(Status.DELETED, deleted.get().getStatus());
    }

    @Test
    void delete_uspsRejects500_bubblesAndLeavesRowActive() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(8L).uspsSubscriptionId("sub-stuck")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.ACTIVE).build();
        when(repository.findById(8L)).thenReturn(Optional.of(row));

        service.nextDeleteException = new RestClientResponseException(
                "Server Error", HttpStatus.INTERNAL_SERVER_ERROR,
                "Server Error", null, "internal".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> service.delete(8L));
        // Row must not have been soft-deleted — retry has to be safe.
        assertEquals(Status.ACTIVE, row.getStatus());
        assertNull(row.getDeletedAt());
        verify(repository, never()).save(any(UspsDirectSubscription.class));
    }

    // -----------------------------------------------------------
    // Secret hygiene — DTO never leaks the plaintext / encrypted material.
    // -----------------------------------------------------------

    @Test
    void dtoConversion_neverExposesEncryptedSecret() {
        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .id(1L).uspsSubscriptionId("sub-1")
                .filterType("MID").filterValue("111")
                .listenerUrl("https://x").environment("PRODUCTION")
                .status(Status.ACTIVE)
                .secretEncrypted("enc:v1:MEGA-SECRET-DO-NOT-LEAK").build();

        UspsDirectSubscriptionDTO dto = UspsDirectSubscriptionDTO.from(row);

        // DTO exposes hasSecret=true but no secret field exists on the class.
        assertTrue(dto.getHasSecret());
        // Reflectively scan every getter to double-check no method returns the ciphertext.
        for (var m : UspsDirectSubscriptionDTO.class.getMethods()) {
            if (!m.getName().startsWith("get") && !m.getName().startsWith("is")) continue;
            if (m.getParameterCount() != 0) continue;
            try {
                Object v = m.invoke(dto);
                if (v instanceof String s) {
                    assertFalse(s.contains("MEGA-SECRET-DO-NOT-LEAK"),
                            "DTO getter " + m.getName() + " leaked the ciphertext");
                    assertFalse(s.contains("enc:v1:"),
                            "DTO getter " + m.getName() + " leaked the encryption prefix");
                }
            } catch (Exception ignore) { /* not a data getter */ }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static String fixture(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                UspsDirectSubscriptionServiceTest.class.getResourceAsStream(
                        "/usps/v3/subscriptions/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Seed a real cache entry via reflection so the token path doesn't
     *  need to hit an OAuth server. Mirrors the pattern in
     *  {@link UspsOAuthTokenCacheTest}. */
    private void seedTokenCache(String clientId, String environment, String token) throws Exception {
        java.lang.reflect.Field field = UspsOAuthTokenCache.class.getDeclaredField("tokenCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Object> map =
                (java.util.concurrent.ConcurrentHashMap<String, Object>) field.get(tokenCache);
        Class<?> clazz = Class.forName(
                "com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache$CachedToken");
        var ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object cached = ctor.newInstance(token, java.time.Instant.now().plusSeconds(7200));
        map.put(UspsOAuthTokenCache.cacheKey(clientId, environment), cached);
    }

    /**
     * Subclass of the SUT that intercepts the two HTTP surfaces so tests
     * can script responses / exceptions without wiring RestClient.
     */
    static class ScriptedService extends UspsDirectSubscriptionService {
        String nextPostResponse;
        RuntimeException nextPostException;
        RuntimeException nextDeleteException;

        String lastPostUrl;
        Map<String, Object> lastPostBody;
        String lastPostAccessToken;

        String lastDeleteUrl;
        String lastDeleteAccessToken;

        ScriptedService(UspsDirectSubscriptionRepository repo, UspsOAuthTokenCache cache,
                        SystemSettingService settings, ObjectMapper mapper) {
            super(repo, cache, settings, mapper);
        }

        @Override
        String postJson(String url, Map<String, Object> body, String accessToken, String context) {
            this.lastPostUrl = url;
            this.lastPostBody = body;
            this.lastPostAccessToken = accessToken;
            if (nextPostException != null) {
                // Match the wrapping the real method does — RestClientResponseException
                // becomes IllegalStateException with the status code + body.
                if (nextPostException instanceof RestClientResponseException rex) {
                    throw new IllegalStateException(
                            "USPS Subscriptions-Tracking " + context + " failed ("
                                    + rex.getStatusCode().value() + "): "
                                    + rex.getResponseBodyAsString(), rex);
                }
                throw nextPostException;
            }
            return nextPostResponse;
        }

        @Override
        void deleteAgainstUsps(String url, String accessToken) {
            this.lastDeleteUrl = url;
            this.lastDeleteAccessToken = accessToken;
            if (nextDeleteException != null) {
                if (nextDeleteException instanceof RestClientResponseException rex) {
                    if (rex.getStatusCode().value() == 404) {
                        // Match production behavior: swallow 404.
                        return;
                    }
                    throw new IllegalStateException(
                            "USPS Subscriptions-Tracking delete failed ("
                                    + rex.getStatusCode().value() + "): "
                                    + rex.getResponseBodyAsString(), rex);
                }
                throw nextDeleteException;
            }
        }
    }

    // Small ArrayList import kept so the compiler doesn't complain about
    // the reserved but-currently-unused helper below.
    @SuppressWarnings("unused")
    private static List<Object> reservedForFutureFixtures() { return new ArrayList<>(); }
}
