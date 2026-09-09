package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Read-side projection for admin Settings page. For {@code SECRET} kind,
 * {@code maskedValue} is "****" + last 4 chars and the plaintext is never
 * exposed. For {@code CHOICE} kind (like the Stamps SERA/SWSIM toggle),
 * the current value is safe to reveal — {@code currentValue} carries it
 * so the FE can highlight the selected option in a radio picker.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemSettingDTO {
    private String key;
    private boolean hasValue;
    private String maskedValue;
    private String description;

    /**
     * Setting shape hint for the FE. {@code SECRET} = password input, only
     * the masked value is visible. {@code CHOICE} = radio picker over
     * {@link #options} with {@link #currentValue} highlighted.
     */
    private Kind kind;

    /** Valid values for a CHOICE setting. Null on SECRET. */
    private List<String> options;

    /** Current cleartext value for a CHOICE setting. Null on SECRET
     *  (the FE only ever sees the mask for secrets). */
    private String currentValue;

    /** Default (unset) value for a CHOICE setting so the FE can show
     *  the effective choice even before the admin has ever saved. */
    private String defaultValue;

    public enum Kind {
        SECRET, CHOICE
    }
}
