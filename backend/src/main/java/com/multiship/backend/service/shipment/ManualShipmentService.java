package com.multiship.backend.service.shipment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.model.ShipmentAttempt;
import com.multiship.backend.repository.ShipmentAttemptRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Manual Shipment redesign, Phase 0 — the one purchase path for manual
 * labels. Claims an attempt row for the caller's {@code Idempotency-Key},
 * runs the purchase ({@link CarrierService#purchaseManualLabel}),
 * and stores the outcome. Same key again replays the stored response; a key
 * still in flight answers 409. Later phases replace the inner call with the
 * resolver / purchaser / recorder split without touching this contract.
 */
@Slf4j
@Service
public class ManualShipmentService {

    private static final TypeReference<ApiResponse<LabelGenerationResponse>> RESPONSE_TYPE = new TypeReference<>() {};

    private final ShipmentAttemptRepository attempts;
    private final CarrierService carrierService;
    private final OrderService orderService;
    private final ObjectMapper json;

    public ManualShipmentService(ShipmentAttemptRepository attempts, CarrierService carrierService, OrderService orderService) {
        this.attempts = attempts;
        this.carrierService = carrierService;
        this.orderService = orderService;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /**
     * Purchase (or re-purchase, when {@code existingOrderNo} is given) under
     * {@code attemptKey}. A blank key gets a fresh one: the purchase still
     * runs, it just cannot be replayed.
     */
    public ApiResponse<LabelGenerationResponse> purchase(ManualShipmentRequest request, String attemptKey,
                                                         UserDetails user, Integer existingOrderNo) {
        return purchase(request, attemptKey, user, existingOrderNo, null);
    }

    /** @param validationFingerprint the X-Validation-Fingerprint header, if the caller validated first. */
    public ApiResponse<LabelGenerationResponse> purchase(ManualShipmentRequest request, String attemptKey,
                                                         UserDetails user, Integer existingOrderNo,
                                                         String validationFingerprint) {
        if (request != null) request.setInternalValidationFingerprint(validationFingerprint);
        UUID key = parseKey(attemptKey);
        String actor = user != null ? user.getUsername() : null;
        String requestJson = write(request);
        LocalDateTime now = LocalDateTime.now();

        if (attempts.claim(key, existingOrderNo, sha256(requestJson), actor, requestJson, now) == 0) {
            ShipmentAttempt existing = attempts.findById(key).orElse(null);
            if (existing == null || ShipmentAttempt.PURCHASING.equals(existing.getState())) {
                return inProgress(key);
            }
            // A FAILED attempt may be retried under the same key (import and
            // queue callers key by order, not by click); whoever re-claims
            // it purchases, everyone else sees it in progress.
            if (ShipmentAttempt.FAILED.equals(existing.getState())) {
                if (attempts.reclaim(key, LocalDateTime.now()) == 0) return inProgress(key);
                log.info("Manual shipment attempt {} retried after a failure", key);
            } else {
                if (!StringUtils.hasText(existing.getResponseJson())) return inProgress(key);
                log.info("Manual shipment attempt {} replayed (state={}, order={})", key, existing.getState(), existing.getOrderNo());
                return read(existing.getResponseJson());
            }
        }

        if (existingOrderNo != null) {
            ApiResponse<LabelGenerationResponse> guard = alreadyGenerated(existingOrderNo);
            if (guard != null) {
                attempts.finish(key, ShipmentAttempt.FAILED, existingOrderNo, write(guard), guard.getErrorCode(), guard.getMessage(), LocalDateTime.now());
                return guard;
            }
        }

        ApiResponse<LabelGenerationResponse> response;
        try {
            response = carrierService.purchaseManualLabel(request, user, existingOrderNo);
        } catch (RuntimeException ex) {
            attempts.finish(key, ShipmentAttempt.FAILED, existingOrderNo, null, ErrorCode.INTERNAL_ERROR.name(), ex.getMessage(), LocalDateTime.now());
            throw ex;
        }

        boolean ok = response.getCode() == 200;
        Integer orderNo = existingOrderNo;
        if (response.getData() != null && response.getData().getOrderNo() != null) {
            orderNo = Math.toIntExact(response.getData().getOrderNo());
        }
        boolean partial = response.getData() != null && "PARTIAL".equals(response.getData().getStatus());
        String state = ok ? ShipmentAttempt.DONE : partial ? ShipmentAttempt.PARTIAL : ShipmentAttempt.FAILED;
        attempts.finish(key, state, orderNo, write(response),
                response.getErrorCode(), ok ? null : response.getMessage(), LocalDateTime.now());
        if (orderNo != null) attempts.stampOrder(orderNo, key);
        return response;
    }

    public Optional<ShipmentAttempt> attempt(UUID key) {
        return attempts.findById(key);
    }

    /** The same guard the regenerate endpoint used to carry: never re-bill a shipped order. */
    private ApiResponse<LabelGenerationResponse> alreadyGenerated(Integer orderNo) {
        ApiResponse<OrderResponseDTO> order = orderService.getOrderWithTracking(orderNo);
        OrderResponseDTO.LabelDetails existing = order != null && order.getData() != null ? order.getData().getLabelDetails() : null;
        if (existing != null && Boolean.TRUE.equals(existing.getIsGenerated())
                && "GENERATED".equalsIgnoreCase(existing.getStatus())) {
            return ApiResponse.<LabelGenerationResponse>builder()
                    .status("error").code(409)
                    .message("Order " + orderNo + " already has a generated label; regenerate is only for failed/pending orders.")
                    .errorCode(ErrorCode.LABEL_ALREADY_GENERATED.name())
                    .timestamp(LocalDateTime.now()).build();
        }
        return null;
    }

    private static ApiResponse<LabelGenerationResponse> inProgress(UUID key) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(409)
                .message("This shipment is still being purchased (attempt " + key + "). Wait for it to finish, then reload the order.")
                .errorCode(ErrorCode.ATTEMPT_IN_PROGRESS.name())
                .timestamp(LocalDateTime.now()).build();
    }

    static UUID parseKey(String raw) {
        if (StringUtils.hasText(raw)) {
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException notUuid) {
                // A non-UUID key is still a stable key: derive one from it.
                return UUID.nameUUIDFromBytes(raw.trim().getBytes(StandardCharsets.UTF_8));
            }
        }
        return UUID.randomUUID();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("attempt json", e);
        }
    }

    private ApiResponse<LabelGenerationResponse> read(String s) {
        try {
            return json.readValue(s, RESPONSE_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("attempt json", e);
        }
    }
}
