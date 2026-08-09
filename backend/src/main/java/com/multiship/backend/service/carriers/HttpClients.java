package com.multiship.backend.service.carriers;

import org.springframework.http.client.ClientHttpRequestFactory;
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
 * <p>Static utility so existing tests (163+ direct-constructor
 * connector instantiations) don't need updating with a new dependency.
 */
public final class HttpClients {

    private static Duration connectTimeout = Duration.ofSeconds(5);
    private static Duration readTimeout = Duration.ofSeconds(30);
    private static volatile ClientHttpRequestFactory factory = buildFactory();

    private HttpClients() {}

    /**
     * Returns a {@link RestClient.Builder} with connect + read timeouts
     * configured. Callers chain {@code .baseUrl(...).build()} as before.
     */
    public static RestClient.Builder newBuilder() {
        return RestClient.builder().requestFactory(factory);
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
        return jdk;
    }
}
