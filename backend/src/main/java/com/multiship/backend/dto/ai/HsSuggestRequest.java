package com.multiship.backend.dto.ai;

import lombok.Data;

/** Ask the AI for an HS tariff code for one commercial-invoice line. */
@Data
public class HsSuggestRequest {
    private String description;
    /** Country the goods were made in (ISO alpha-2), if known — sharpens the code. */
    private String originCountry;
}
