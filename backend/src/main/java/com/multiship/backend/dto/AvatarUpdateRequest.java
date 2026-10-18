package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Self-service profile-photo change. {@code avatar} is a small, client-resized
 * image as a data URL ({@code data:image/...;base64,...}); null or blank removes
 * the photo. The image type and decoded byte size are checked in the service.
 * The length cap here is a coarse DTO-boundary guard against oversized payloads.
 */
@Data
public class AvatarUpdateRequest {

    @Size(max = 700_000, message = "Image is too large.")
    private String avatar;
}
