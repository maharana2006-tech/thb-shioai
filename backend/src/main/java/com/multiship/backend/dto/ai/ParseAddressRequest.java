package com.multiship.backend.dto.ai;

import lombok.Data;

/** Free-text blob (pasted email, order note, signature) to parse into a structured address. */
@Data
public class ParseAddressRequest {
    private String text;
}
