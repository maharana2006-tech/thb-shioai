package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Result of a synchronous batch create — one entry per request item, in order. */
@Data
@Builder
public class ExternalBatchShipmentResponse {
    private int total;
    private int succeeded;
    private int failed;
    private List<Item> results;

    @Data
    @Builder
    public static class Item {
        /** 0-based position of the item in the request. */
        private int index;
        private String reference;
        /** SUCCESS | ERROR */
        private String status;
        /** HTTP status this item would have returned as a single call. */
        private int code;
        private String errorCode;
        private String message;
        /** Same shape as the single-create response (incl. labelZpl); null on error. */
        private ExternalShipmentResponse shipment;
    }
}
