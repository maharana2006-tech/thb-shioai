package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Self-service profile edit. Only the display name is editable here; username,
 * email and role are changed through signup/invite and the admin user tools.
 */
@Data
public class ProfileUpdateRequest {

    @NotBlank(message = "fullName is required")
    @Size(max = 100, message = "fullName must be at most 100 characters")
    private String fullName;
}
