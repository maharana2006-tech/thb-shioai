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

    /**
     * Sprint 50 PR Q1 (JWT httpOnly cookie migration) — cookie-mode
     * constructor: no token in the JSON body, only the SPA-visible
     * username + role. The JWT itself is written by AuthServiceImpl as
     * a Set-Cookie header (HttpOnly, so JS never sees it). Existing FE
     * builds that read {@code .token} will see null and can fall
     * through to the cookie-driven flow.
     */
    public AuthResponse(String username, String role) {
        this(null, username, role);
    }
}