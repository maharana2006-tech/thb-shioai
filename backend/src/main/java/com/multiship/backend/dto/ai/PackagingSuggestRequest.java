package com.multiship.backend.dto.ai;

import lombok.Data;

import java.util.List;

/** Ask the AI to recommend a package + weight from the shipment's item list. */
@Data
public class PackagingSuggestRequest {
    private List<ItemLine> items;
    /** Package preset codes the client is allowed to use (the AI must pick one of these when non-empty). */
    private List<String> available;

    @Data
    public static class ItemLine {
        private String description;
        private Integer quantity;
    }
}
