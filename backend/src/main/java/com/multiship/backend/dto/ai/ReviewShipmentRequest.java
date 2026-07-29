package com.multiship.backend.dto.ai;

import lombok.Data;

import java.util.List;

/** Snapshot of the manual-shipment form for a pre-ship AI sanity review. */
@Data
public class ReviewShipmentRequest {
    private String fromCountry;
    private String toCountry;
    private Double weightLb;
    private Double lengthIn;
    private Double widthIn;
    private Double heightIn;
    private String packageCode;
    private String incoterm;
    /** Whether an importer of record is resolved (needed for DDP / dutiable intl). */
    private Boolean importerProvided;
    private List<ReviewItem> items;

    @Data
    public static class ReviewItem {
        private String description;
        private String hsCode;
        private Double value;
    }
}
