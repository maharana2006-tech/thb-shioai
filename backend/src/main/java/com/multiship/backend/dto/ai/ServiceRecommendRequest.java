package com.multiship.backend.dto.ai;

import lombok.Data;

import java.util.List;

/** Ask the AI to recommend a shipping service + incoterm for the route. */
@Data
public class ServiceRecommendRequest {
    private String fromCountry;
    private String toCountry;
    private Double weightLb;
    /** ECONOMY | STANDARD | EXPRESS — how fast the shipper wants it (optional). */
    private String urgency;
    /** Service codes available for this client/route (the AI must pick one of these when non-empty). */
    private List<String> available;
}
