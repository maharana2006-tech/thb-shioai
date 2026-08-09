package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-side projection for admin Settings page. Never carries the
 * decrypted value — {@code maskedValue} is "****" + last 4 chars, and
 * {@code hasValue} tells the UI whether anything is stored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemSettingDTO {
    private String key;
    private boolean hasValue;
    private String maskedValue;
    private String description;
}
