package com.multiship.backend.service.shipment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.multiship.backend.dto.ManualShipmentRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Phase 1 — a stable hash of the payload the operator validated, so the
 * purchase can tell "this exact shipment passed the dry run" without
 * re-running the carrier hop. Canonical: sorted properties and map keys,
 * nulls dropped, internal (@JsonIgnore) fields excluded by construction.
 */
public final class ShipmentFingerprint {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private ShipmentFingerprint() {}

    public static String of(ManualShipmentRequest request) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("fingerprint", e);
        }
    }

    /** Length-safe comparison for header input. */
    public static boolean matches(String presented, ManualShipmentRequest request) {
        if (presented == null || presented.length() != 64) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.US_ASCII), of(request).getBytes(StandardCharsets.US_ASCII));
    }
}
