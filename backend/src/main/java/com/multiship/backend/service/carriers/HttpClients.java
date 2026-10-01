package com.multiship.backend.service.carriers;

import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Sprint 49 Tier 2 — shared {@link RestClient.Builder} factory with
 * connect + read timeouts baked in.
 *
 * <p>Prior to this every carrier call used {@code RestClient.builder()}
 * with no timeout configuration, so a carrier degradation hung the
 * servlet thread until the OS default TCP timeout (minutes to
 * indefinite). Under load that exhausts the Tomcat pool.
 *
 * <p>Defaults are 5s connect / 30s read, which cover a healthy
 * production RTT for UPS/FedEx/DHL/Stamps while still failing fast
 * when a carrier goes down. Overrideable in a follow-up by wiring
 * {@code carrier.connection-timeout-seconds} +
 * {@code carrier.read-timeout-seconds} into
 * {@link #reconfigure(int, int)}.
 *
 * <p>V114 wire — a global {@link ClientHttpRequestInterceptor} slot lets
 * the observability layer install {@code CarrierApiLoggingInterceptor}
 * at startup. All current + future callers of {@link #newBuilder()} /
 * {@link #newBuilder(int)} inherit API logging for free. The response
 * body is buffered via {@link BufferingClientHttpRequestFactory} so the
 * interceptor can read it without consuming the stream for the caller.
 *
 * <p>Static utility so existing tests (163+ direct-constructor
 * connector instantiations) don't need updating with a new dependency.
 */
public final class HttpClients {

    private static Duration connectTimeout = Duration.ofSeconds(5);
    private static Duration readTimeout = Duration.ofSeconds(30);
    private static volatile ClientHttpRequestFactory factory = buildFactory();

    /** V114 — set at startup by {@code HttpClientsInterceptorConfig}. Null
     *  means "no interceptor", which is the default for pure-Mockito tests
     *  that bypass Spring. */
    private static volatile ClientHttpRequestInterceptor globalInterceptor;

    private HttpClients() {}

    /**
     * V114 — register the global interceptor (typically the carrier API
     * log). Called once at startup by {@code HttpClientsInterceptorConfig};
     * null clears it (used in tests that need the plain, un-intercepted
     * builder).
     */
    public static synchronized void setGlobalInterceptor(ClientHttpRequestInterceptor interceptor) {
        globalInterceptor = interceptor;
    }

    /**
     * Returns a {@link RestClient.Builder} with connect + read timeouts
     * configured. Callers chain {@code .baseUrl(...).build()} as before.
     */
    public static RestClient.Builder newBuilder() {
        return applyInterceptor(RestClient.builder().requestFactory(factory));
    }

    /**
     * S2 — per-caller read-timeout override. Used by
     * {@code RestExternalConnector} so the admin-editable
     * {@code readTimeoutSeconds} + {@code healthCheckTimeoutSeconds}
     * fields on {@code external_system_connection.config_json} actually
     * take effect (before S2 they were silently ignored).
     *
     * <p>Shares the global connect timeout — connect is per-TCP-handshake,
     * not per-request, and the global default (5s) is a reasonable
     * ceiling for any REST connector.
     */
    public static RestClient.Builder newBuilder(int readSeconds) {
        int safe = Math.max(1, readSeconds);
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory jdk = new JdkClientHttpRequestFactory(client);
        jdk.setReadTimeout(Duration.ofSeconds(safe));
        return applyInterceptor(RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(jdk)));
    }

    /**
     * Ops override for the defaults. Rebuilds the shared factory so
     * subsequent {@link #newBuilder()} calls pick up the new timeouts.
     * Not thread-safe with an in-flight request that's already grabbed
     * the old factory reference; call at startup only.
     */
    public static synchronized void reconfigure(int connectSeconds, int readSeconds) {
        connectTimeout = Duration.ofSeconds(connectSeconds);
        readTimeout = Duration.ofSeconds(readSeconds);
        factory = buildFactory();
    }

    private static ClientHttpRequestFactory buildFactory() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory jdk = new JdkClientHttpRequestFactory(client);
        jdk.setReadTimeout(readTimeout);
        // V114 — wrap in BufferingClientHttpRequestFactory so the carrier
        // API log interceptor can read response bodies without consuming
        // the stream for the caller.
        return new BufferingClientHttpRequestFactory(jdk);
    }

    private static RestClient.Builder applyInterceptor(RestClient.Builder builder) {
        ClientHttpRequestInterceptor current = globalInterceptor;
        if (current != null) builder.requestInterceptor(current);
        return builder;
    }
}
