package com.multiship.backend.controller.ai;

import com.multiship.backend.dto.ai.HsSuggestRequest;
import com.multiship.backend.dto.ai.PackagingSuggestRequest;
import com.multiship.backend.dto.ai.ParseAddressRequest;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import com.multiship.backend.dto.ai.ServiceRecommendRequest;
import com.multiship.backend.dto.external.ExternalAddress;
import com.multiship.backend.service.ai.AddressAiService;
import com.multiship.backend.service.ai.ShipmentAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI-assist endpoints for the manual-shipment operator UI (authenticated users
 * only, per SecurityConfig's anyRequest().authenticated()). Every endpoint is
 * suggestion-only — results are returned for the user to confirm; nothing is
 * persisted here.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AddressAiService addressAiService;
    private final ShipmentAiService shipmentAiService;

    /** Ship from / Ship to — parse a free-text blob into a structured address. */
    @PostMapping("/parse-address")
    public ResponseEntity<ExternalAddress> parseAddress(@RequestBody ParseAddressRequest req) {
        return ResponseEntity.ok(addressAiService.parse(req == null ? null : req.getText()));
    }

    /** Items · commercial invoice — suggest an HS tariff code for one line. */
    @PostMapping("/suggest-hs")
    public ResponseEntity<Map<String, Object>> suggestHs(@RequestBody HsSuggestRequest req) {
        return ResponseEntity.ok(shipmentAiService.suggestHs(req));
    }

    /** Package & weight — recommend a package + estimate from the item list. */
    @PostMapping("/suggest-packaging")
    public ResponseEntity<Map<String, Object>> suggestPackaging(@RequestBody PackagingSuggestRequest req) {
        return ResponseEntity.ok(shipmentAiService.suggestPackaging(req));
    }

    /** Account & service — recommend a service + incoterm for the route. */
    @PostMapping("/recommend-service")
    public ResponseEntity<Map<String, Object>> recommendService(@RequestBody ServiceRecommendRequest req) {
        return ResponseEntity.ok(shipmentAiService.recommendService(req));
    }

    /** Shipment — pre-ship sanity review of the whole form. */
    @PostMapping("/review-shipment")
    public ResponseEntity<Map<String, Object>> reviewShipment(@RequestBody ReviewShipmentRequest req) {
        return ResponseEntity.ok(shipmentAiService.reviewShipment(req));
    }
}
