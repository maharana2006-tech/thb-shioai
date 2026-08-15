package com.multiship.backend.service.output;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sprint 52 — typed views over the {@code client_output_destination.config}
 * JSONB column. Each driver reads via
 * {@link OutputConfig#parse(ObjectMapper, String, Class)} which gives it
 * a POJO instead of dictionary hunting.
 *
 * <p>All three shapes tolerate unknown fields so we can add new options
 * without breaking older rows. Secret material (SFTP password, private
 * key) is never stored in-line — the JSON only carries a
 * {@code passwordSecretId} that points at an AES-GCM encrypted blob in
 * {@link com.multiship.backend.model.SystemSetting}. Admin UI is
 * write-only for those fields (POST/PUT accepts, GET never echoes).
 */
public final class OutputConfig {

    private OutputConfig() {}

    public static <T> T parse(ObjectMapper mapper, String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("config JSON is null/blank");
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "invalid " + type.getSimpleName() + " config JSON: " + ex.getMessage(), ex);
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalFsConfig {
        /** Absolute directory the driver writes files into. Must exist and
         *  be writable — the driver does NOT create parents automatically
         *  (would mask a typo'd path). */
        private String path;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SftpConfig {
        private String host;
        private Integer port;              // null → 22
        private String  username;
        /** {@code PASSWORD} or {@code KEY}. */
        private String  authType;
        /** Points into system_settings.setting_key; the encrypted_value column holds the ciphertext. */
        private String  passwordSecretId;
        /** Ditto — PEM contents encrypted at rest. */
        private String  privateKeySecretId;
        /** Remote directory to upload into. Absolute or relative to the SSH user's home. */
        private String  remoteDir;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrinterConfig {
        private String host;
        private Integer port;              // null → protocol default (9100 or 631)
        /** {@code RAW_9100} for Zebra ZPL, {@code IPP} for laser PDF. */
        private String  protocol;
        /** IPP queue name (e.g. {@code /printers/Zebra_Front_Desk}). Optional. */
        private String  queueName;
    }
}
