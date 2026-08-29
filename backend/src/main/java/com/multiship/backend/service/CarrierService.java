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

    /** Fix-and-regenerate: apply corrected operator input to {@code existingOrderNo}
     *  and re-label it in place (same order number). Used to recover failed orders. */
    ApiResponse<LabelGenerationResponse> generateManualLabel(com.multiship.backend.dto.ManualShipmentRequest request,
            UserDetails user, Integer existingOrderNo);

    ApiResponse<List<OrderAccountResolutionDTO>> resolveOrderAccounts(List<Integer> orderNos);

    CarrierConnector getCarrierConnector(String carrierCode);
}
