package com.multiship.backend.service.externalsystems.connectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Jackson shape for {@code external_system_connection.config_json}
 * when {@code system_type = 'REST_JSON'}. Non-secret fields only —
 * API keys / OAuth secrets live in {@code external_system_secret}.
 *
 * <p>S5 stub — proves the S1 SPI generalizes beyond JDBC. Real
 * consumers (SAP, marketplace webhooks, etc.) will typically fork
 * this into a vendor-specific connector to handle authentication
 * flow quirks, but the generic REST_JSON shape is enough for
 * simple bearer-token / X-API-Key integrations.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestExternalConfig {

    /**
     * Root URL every call resolves against (e.g.
     * {@code https://api.example.com/v1}). Trailing slash tolerated.
     */
    private String baseUrl;

    /**
     * Optional OAuth 2.0 token endpoint. When set, the connector
     * uses client-credentials flow with secret {@code oauthClientSecret};
     * unset means static-header auth with secret {@code apiKey}.
     */
    private String oauthTokenUrl;

    /**
     * Optional header name for the API key when using static-header
     * auth. Defaults to {@code X-API-Key}; some vendors want
     * {@code Authorization: Bearer ...} — set {@code apiKeyHeader = "Authorization"}
     * and provision the secret with the {@code Bearer } prefix.
     */
    private String apiKeyHeader = "X-API-Key";

    /**
     * Health-check path (relative to {@link #baseUrl}). Defaults to
     * {@code /health}; some vendors expose {@code /ping}, {@code /status},
     * etc. — the connector's healthCheck() calls GET on this path.
     */
    private String healthCheckPath = "/health";

    /**
     * Read timeout applied to every request. 10s balances a slow
     * integration against not tying up the servlet thread forever.
     */
    private int readTimeoutSeconds = 10;

    /**
     * Health-check-specific timeout override — kept short so the
     * indicator doesn't wait for a slow endpoint to time out.
     */
    private int healthCheckTimeoutSeconds = 3;

    // ── V89 writeback paths (relative to baseUrl) ──────────────────────
    // Both default to conventional REST shapes; override per-vendor via
    // config_json when their API doesn't fit.

    /** POST path for the generate-side writeback. Body is the payload JSON. */
    private String writebackPath = "/shipments";

    /**
     * Void-side path template. Two conventions supported:
     * <ul>
     *   <li>Path with {@code {tracking}} placeholder — connector substitutes
     *       and calls DELETE (e.g. {@code /shipments/{tracking}}).</li>
     *   <li>Path without placeholder — connector POSTs a small JSON body
     *       ({@code {trackingNumber, orderNo}}) (e.g. {@code /shipments/void}).</li>
     * </ul>
     */
    private String writebackClearPath = "/shipments/{tracking}";
}
