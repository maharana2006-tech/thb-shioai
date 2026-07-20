package com.multiship.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor // Enables seamless JSON deserialization frameworks (Jackson)
public class AuthResponse {

    private String token;
    private String username;
    private String role;

    // Constant definition for the token standardization mechanism
    private final String type = "Bearer";

    /**
     * Professional Enterprise Constructor.
     * Maps explicit session contexts back up to front-end states while
     * shielding the static final initialization wrapper from mapping errors.
     */
    public AuthResponse(String token, String username, String role) {
        this.token = token;
        this.username = username;
        this.role = role;
    }
}