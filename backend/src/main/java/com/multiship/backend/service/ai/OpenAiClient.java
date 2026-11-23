package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.TenantScopeEnforcer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one place that knows which AI provider Multiship talks to. Calls go
 * through Spring AI's {@link ChatClient}, so moving to another provider means
 * changing how {@link #chatClient()} builds its model and nothing else.
 *
 * <p>Every AI-assist feature (address parse, HS suggest, packaging, service,
 * review) funnels through {@link #completeJson}; the Shipmate chat streams
 * through {@link #streamText}. Key handling, error mapping and metering are
 * shared by both.
 *
 * <p>Sprint 49 Tier 0 — key resolution order:
 * <ol>
 *   <li>DB-stored setting {@code openai.api-key} (rotated via admin UI)</li>
 *   <li>{@code OPENAI_API_KEY} env var</li>
 *   <li>neither → feature disabled ({@link #isConfigured()} returns false)</li>
 * </ol>
 *
 * <p>Sprint 51 follow-up BS-M2 — per-tenant OpenAI token metering.
 * BP-M4 wired Actuator + Micrometer + the Prometheus registry, so this
 * client now emits per-tenant, per-endpoint counters and a latency timer
 * against the shared {@link MeterRegistry}. Metrics are scrape-able at
 * {@code /actuator/prometheus}. Every completion emits:
 * <ul>
 *   <li>{@code openai_prompt_tokens_total}, {@code openai_completion_tokens_total},
 *       {@code openai_total_tokens_total} — running per-tenant + per-endpoint token
 *       counts (drive the $-cost dashboard once a pricing multiplier is applied
 *       downstream).</li>
 *   <li>{@code openai_requests_total} — request count with
 *       {@code outcome=success|error} for error-rate alerting.</li>
 *   <li>{@code openai_request_duration_seconds} — timer for latency SLO.</li>
 * </ul>
 * Cardinality note: {@code tenant} = client_code (bounded — tens to low
 * hundreds in the deploy fleet); {@code endpoint} = fixed constant set
 * ({@code parse-address}, {@code suggest-hs}, {@code suggest-packaging},
 * {@code recommend-service}, {@code review-shipment}, {@code unknown});
 * {@code outcome} = 2 values. Bounded — no MeterFilter cap needed.
 */
@Slf4j
@Component
public class OpenAiClient {

    /** Tag value when the caller is unauthenticated / platform operator (empty scope). */
    static final String TENANT_UNSCOPED = "unscoped";
    /** Tag value used for legacy call sites that predate the endpoint-labelled overload. */
    static final String ENDPOINT_UNKNOWN = "unknown";

    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;
    /** Sprint 51 BS-M2 — resolves the caller's client_code for the metric tag. */
    private final TenantScopeEnforcer tenantScopeEnforcer;
    /** Sprint 51 BS-M2 — optional; production always has BP-M4's Prometheus registry. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** Fallback if the DB-stored setting is unset. */
    private final String envApiKey;
    private final String model;
    private final String baseUrl;

    /**
     * A whole call, streamed replies included, may take this long. An OpenAI
     * stall (30-120s hangs are routine) must not hold a request forever.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /** One automatic retry on a rate-limit, 5xx or dropped connection, then the caller is told. */
    private static final int MAX_RETRIES = 1;

    /** The client is built once per key and shared, so connections are reused. */
    private record Built(String apiKey, ChatClient client) { }
    private volatile Built built;

    public static final String SETTING_KEY = "openai.api-key";

    public OpenAiClient(
            ObjectMapper objectMapper,
            SystemSettingService systemSettingService,
            TenantScopeEnforcer tenantScopeEnforcer,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${openai.api-key:}") String envApiKey,
            @Value("${openai.model:gpt-4o-mini}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.systemSettingService = systemSettingService;
        this.tenantScopeEnforcer = tenantScopeEnforcer;
        this.meterRegistryProvider = meterRegistryProvider;
        this.envApiKey = envApiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    private String resolveApiKey() {
        return systemSettingService.getDecrypted(SETTING_KEY)
                .filter(StringUtils::hasText)
                .orElse(envApiKey);
    }

    public boolean isConfigured() {
        return StringUtils.hasText(resolveApiKey());
    }

    /**
     * The chat client for the key in force right now. The key lives in
     * system_settings and an admin can rotate it while the app runs, so the
     * client is rebuilt whenever the resolved key differs from the one it was
     * built with.
     *
     * @throws ResponseStatusException 503 when no key is configured
     */
    private ChatClient chatClient() {
        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI assist is not configured. Set OPENAI_API_KEY or store the key via the admin Settings page.");
        }
        Built current = built;
        if (current == null || !current.apiKey().equals(apiKey)) {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .model(model)
                    .timeout(CALL_TIMEOUT)
                    .maxRetries(MAX_RETRIES)
                    .build();
            current = new Built(apiKey, ChatClient.create(OpenAiChatModel.builder().options(options).build()));
            built = current;
        }
        return current.client();
    }

    /**
     * Back-compat entry point — no explicit endpoint tag. Prefer the
     * three-arg overload so metrics can attribute usage to a real feature
     * name (e.g. {@code parse-address}).
     */
    public JsonNode completeJson(String systemPrompt, String userContent) {
        return completeJson(systemPrompt, userContent, ENDPOINT_UNKNOWN);
    }

    /**
     * Run a JSON-mode completion and return the parsed content object. The system
     * prompt MUST instruct the model to return a JSON object.
     *
     * @param endpoint short, low-cardinality feature name used as the
     *                 {@code endpoint} tag on emitted metrics
     *                 (e.g. {@code parse-address}, {@code suggest-hs}).
     */
    public JsonNode completeJson(String systemPrompt, String userContent, String endpoint) {
        ChatClient client = chatClient();
        if (!StringUtils.hasText(userContent)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Nothing to send to the AI service.");
        }

        String tenant = resolveTenantTag();
        String endpointTag = StringUtils.hasText(endpoint) ? endpoint : ENDPOINT_UNKNOWN;
        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            response = client.prompt()
                    .system(systemPrompt)
                    .user(userContent)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.0)
                            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()))
                    .call()
                    .chatResponse();
        } catch (Exception ex) {
            recordRequest(tenant, endpointTag, "error", startNanos);
            log.warn("OpenAI request failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service could not be reached. Please try again.");
        }

        try {
            recordTokens(tenant, endpointTag, response == null ? null : response.getMetadata().getUsage());
            String content = textOf(response);
            if (!StringUtils.hasText(content)) {
                recordRequest(tenant, endpointTag, "error", startNanos);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI service returned an empty result.");
            }
            JsonNode parsed = objectMapper.readTree(content);
            recordRequest(tenant, endpointTag, "success", startNanos);
            return parsed;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            recordRequest(tenant, endpointTag, "error", startNanos);
            log.warn("Could not parse OpenAI response: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service returned an unexpected result.");
        }
    }

    /**
     * Stream a plain-text reply piece by piece, for the Shipmate chat. Nothing
     * is sent to the provider until the returned stream is subscribed to, and
     * cancelling the subscription stops the provider call.
     *
     * @param messages the conversation so far, ending with the user's new message
     * @param endpoint feature name for the {@code endpoint} metric tag
     * @throws ResponseStatusException 503 when no key is configured (thrown here, before any stream exists)
     */
    public Flux<String> streamText(String systemPrompt, List<Message> messages, String endpoint) {
        ChatClient client = chatClient();
        // Resolved here on the request thread: the stream itself runs on the HTTP client's threads,
        // where there is no security context to read the tenant from.
        String tenant = resolveTenantTag();
        String endpointTag = StringUtils.hasText(endpoint) ? endpoint : ENDPOINT_UNKNOWN;
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        long[] startNanos = new long[1];
        return client.prompt()
                .system(systemPrompt)
                .messages(messages)
                // Usage arrives on the final chunk only when asked for.
                .options(OpenAiChatOptions.builder().streamUsage(true))
                .stream()
                .chatResponse()
                .doOnSubscribe(sub -> startNanos[0] = System.nanoTime())
                .doOnNext(chunk -> {
                    Usage usage = chunk.getMetadata().getUsage();
                    if (usage != null) lastUsage.set(usage);
                })
                .doOnComplete(() -> {
                    recordTokens(tenant, endpointTag, lastUsage.get());
                    recordRequest(tenant, endpointTag, "success", startNanos[0]);
                })
                .doOnError(ex -> {
                    recordRequest(tenant, endpointTag, "error", startNanos[0]);
                    log.warn("OpenAI stream failed: {}", ex.getMessage());
                })
                .mapNotNull(OpenAiClient::textOf)
                .filter(StringUtils::hasLength);
    }

    private static String textOf(ChatResponse response) {
        return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

    // ── Micrometer wiring (Sprint 51 BS-M2) ─────────────────────────────────

    /**
     * Resolve the caller's client_code into a tag value; empty scope
     * (platform operator / unauthenticated /actuator flow) folds to
     * {@link #TENANT_UNSCOPED} so cardinality stays bounded and no null
     * tag ever reaches Prometheus. Never throws — tag resolution must
     * not break the AI call.
     */
    private String resolveTenantTag() {
        try {
            return Optional.ofNullable(tenantScopeEnforcer)
                    .flatMap(TenantScopeEnforcer::resolveScope)
                    .filter(StringUtils::hasText)
                    .orElse(TENANT_UNSCOPED);
        } catch (Exception ex) {
            // The scope resolver looks at SecurityContext; in an odd
            // stack (test, async, boot filter chain) that may throw.
            // Metering must never break the OpenAI call.
            return TENANT_UNSCOPED;
        }
    }

    private MeterRegistry registry() {
        return meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    /**
     * Emit the three token counters from the provider's usage report.
     * The report is optional (some error responses omit it); missing or
     * zero counts are simply skipped rather than emitting a zero-value
     * increment.
     */
    private void recordTokens(String tenant, String endpoint, Usage usage) {
        MeterRegistry reg = registry();
        if (reg == null || usage == null) return;
        long prompt = count(usage.getPromptTokens());
        long completion = count(usage.getCompletionTokens());
        long total = count(usage.getTotalTokens());
        if (prompt > 0) {
            Counter.builder("openai.prompt_tokens")
                    .description("OpenAI prompt (input) tokens billed per tenant + AI endpoint.")
                    .tag("tenant", tenant)
                    .tag("endpoint", endpoint)
                    .register(reg)
                    .increment(prompt);
        }
        if (completion > 0) {
            Counter.builder("openai.completion_tokens")
                    .description("OpenAI completion (output) tokens billed per tenant + AI endpoint.")
                    .tag("tenant", tenant)
                    .tag("endpoint", endpoint)
                    .register(reg)
                    .increment(completion);
        }
        if (total > 0) {
            Counter.builder("openai.total_tokens")
                    .description("OpenAI total tokens billed per tenant + AI endpoint (prompt + completion).")
                    .tag("tenant", tenant)
                    .tag("endpoint", endpoint)
                    .register(reg)
                    .increment(total);
        }
    }

    private static long count(Integer tokens) {
        return tokens == null ? 0L : tokens;
    }

    /**
     * Emit a request-count increment and record the latency timer sample.
     * Safe when no registry is wired (unit tests without Actuator boot).
     */
    private void recordRequest(String tenant, String endpoint, String outcome, long startNanos) {
        MeterRegistry reg = registry();
        if (reg == null) return;
        Counter.builder("openai.requests")
                .description("OpenAI Chat Completions requests grouped by tenant, endpoint and outcome (success|error).")
                .tag("tenant", tenant)
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .register(reg)
                .increment();
        Timer.builder("openai.request.duration")
                .description("OpenAI Chat Completions round-trip latency per tenant + endpoint.")
                .tag("tenant", tenant)
                .tag("endpoint", endpoint)
                .register(reg)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
