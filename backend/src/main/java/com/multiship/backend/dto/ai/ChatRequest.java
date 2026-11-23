package com.multiship.backend.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** One message to Shipmate, with the page it was sent from and the conversation so far. */
@Data
public class ChatRequest {

    @NotBlank
    @Size(max = 8000)
    private String message;

    /** Page name as shown in the topbar, e.g. "New Shipment". */
    @Size(max = 80)
    private String page;

    /** Earlier turns, oldest first. The server keeps no conversation of its own. */
    @Valid
    @Size(max = 200)
    private List<Turn> history;

    @Data
    public static class Turn {
        @NotBlank
        @Pattern(regexp = "user|assistant")
        private String role;

        @NotBlank
        @Size(max = 8000)
        private String text;
    }
}
