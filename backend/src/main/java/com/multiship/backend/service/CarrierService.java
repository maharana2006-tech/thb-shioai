package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierConnectRequest;
import com.multiship.backend.dto.CarrierConnectResponse;
import com.multiship.backend.dto.CarrierAccountDTO;
import com.multiship.backend.dto.CarrierListResponse;
import com.multiship.backend.dto.CarrierStatusResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderAccountResolutionDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

public interface CarrierService {

    ApiResponse<List<CarrierListResponse>> getAvailableCarriers();

    ApiResponse<CarrierConnectResponse> connectToCarrier(CarrierConnectRequest request, UserDetails user);

    ApiResponse<CarrierStatusResponse> disconnectCarrier(UserDetails user);

    ApiResponse<CarrierStatusResponse> getCarrierStatus(UserDetails user);

    /**
     * Generates a label for the order. Idempotent: concurrent requests for
     * the same order serialize on a row lock, and a retry carrying the same
     * Idempotency-Key returns the existing label as a success.
     */
    ApiResponse<LabelGenerationResponse> generateLabel(Long orderNo, UserDetails user, String idempotencyKey, Long accountId);

    /**
     * Sprint 50 Tier 1 (finding #3) — extended overload with an explicit
     * opt-in to bill the platform (house) account when the client's own
     * carrier credentials fail authentication. The 4-arg overload above
     * delegates here with {@code useHouseAccount=false} so pre-existing
     * callers keep the "no silent platform bill" default.
     */
    ApiResponse<LabelGenerationResponse> generateLabel(Long orderNo, UserDetails user,
                                                       String idempotencyKey, Long accountId,
                                                       boolean useHouseAccount);

    /** One-shot manual shipment: purchase a label from explicit operator input and record it as a manual order. */
    ApiResponse<LabelGenerationResponse> generateManualLabel(com.multiship.backend.dto.ManualShipmentRequest request, UserDetails user);

    ApiResponse<List<OrderAccountResolutionDTO>> resolveOrderAccounts(List<Integer> orderNos);

    CarrierConnector getCarrierConnector(String carrierCode);

    /**
     * PR #534 — full {@link com.multiship.backend.dto.ShipmentRequestDTO}
     * population from a {@link com.multiship.backend.dto.ManualShipmentRequest}.
     * Extracted from {@link CarrierServiceImpl#generateManualLabel} so
     * {@code ShipmentValidationService.callCarrierValidateShipment}
     * can produce a byte-for-byte identical DTO for the carrier-native
     * validate hop. See implementation javadoc for the resolved-input
     * contract.
     */
    com.multiship.backend.dto.ShipmentRequestDTO buildManualShipmentRequestDto(
            com.multiship.backend.dto.ManualShipmentRequest req,
            com.multiship.backend.dto.ManualShipmentRequest.Address from,
            com.multiship.backend.dto.ManualShipmentRequest.Address to,
            String carrier,
            String billToNumber,
            String serviceType,
            String packageType,
            java.math.BigDecimal length,
            java.math.BigDecimal width,
            java.math.BigDecimal height,
            String fromCountry,
            com.multiship.backend.model.CarrierAccountRef account,
            /** PR #543 — pre-allocated for the manual path so the label's
             *  PO field can be stamped MAN{orderNo}. Null when validate
             *  hop calls this (no persisted orderNo yet). */
            Integer orderNoForPo);
}
