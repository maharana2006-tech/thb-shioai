package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
 * unwrapping live in one place. Key is read from the OPENAI_API_KEY env var.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private final ObjectMapper objectMapper;

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * Run a JSON-mode completion and return the parsed content object. The system
     * prompt MUST instruct the model to return a JSON object.
     */
    public JsonNode completeJson(String systemPrompt, String userContent) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI assist is not configured. Set the OPENAI_API_KEY environment variable.");
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
            raw = RestClient.builder().baseUrl(baseUrl).build()
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
