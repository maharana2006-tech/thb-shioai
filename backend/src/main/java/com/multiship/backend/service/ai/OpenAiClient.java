package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.HttpClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over OpenAI Chat Completions in JSON mode. Every AI-assist
 * feature (address parse, HS suggest, packaging, service, review) funnels
 * through {@link #completeJson} so the key handling, error mapping and JSON
 * unwrapping live in one place.
 *
 * <p>Sprint 49 Tier 0 — key resolution order:
 * <ol>
 *   <li>DB-stored setting {@code openai.api-key} (rotated via admin UI)</li>
 *   <li>{@code OPENAI_API_KEY} env var</li>
 *   <li>neither → feature disabled ({@link #isConfigured()} returns false)</li>
 * </ol>
 */
@Slf4j
@Component
public class OpenAiClient {

    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    /** Fallback if the DB-stored setting is unset. */
    private final String envApiKey;
    private final String model;
    private final String baseUrl;

    /**
     * Sprint 51 T4 finding #9 — shared {@link RestClient} built ONCE
     * in the constructor with 5s connect / 30s read via
     * {@link HttpClients#newBuilder()}. Pre-T4 the client was rebuilt
     * per call inside {@link #completeJson} with no timeout config,
     * so an OpenAI stall (30-120s hangs are routine) drained Tomcat
     * threads and there was needless handshake cost per request.
     */
    private final RestClient sharedRestClient;

    public static final String SETTING_KEY = "openai.api-key";

    public OpenAiClient(
            ObjectMapper objectMapper,
            SystemSettingService systemSettingService,
            @Value("${openai.api-key:}") String envApiKey,
            @Value("${openai.model:gpt-4o-mini}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.systemSettingService = systemSettingService;
        this.envApiKey = envApiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.sharedRestClient = HttpClients.newBuilder().baseUrl(baseUrl).build();
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
     * Run a JSON-mode completion and return the parsed content object. The system
     * prompt MUST instruct the model to return a JSON object.
     */
    public JsonNode completeJson(String systemPrompt, String userContent) {
        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI assist is not configured. Set OPENAI_API_KEY or store the key via the admin Settings page.");
        }
        if (!StringUtils.hasText(userContent)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nothing to send to the AI service.");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                )
        );

        String raw;
        try {
            raw = sharedRestClient
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            log.warn("OpenAI request failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service could not be reached. Please try again.");
        }

        try {
            JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI service returned an empty result.");
            }
            return objectMapper.readTree(content);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            log.warn("Could not parse OpenAI response: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI service returned an unexpected result.");
        }
    }
}
