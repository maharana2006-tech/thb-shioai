package com.multiship.backend.controller;

import com.multiship.backend.dto.LoginRequest;
import com.multiship.backend.dto.MessageResponse;
import com.multiship.backend.dto.SignupRequest;
import com.multiship.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Login, signup, and logout — the only endpoints that work without a token")
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(originPatterns = {
        "http://localhost:*",
        "http://127.0.0.1:*",
        "http://[::1]:*"
})
public class AuthController {

    @Autowired
    private AuthService authService; // Pure business layer abstraction contract

    /**
     * Professional Signup Endpoint
     * Triggers inbound object validation and maps parameters down to service implementations.
     */
    @Operation(summary = "Create an account",
            description = "Public signup may create USER or TENANT accounts. Creating an ADMIN requires an existing ADMIN's token. 409 errorCode USERNAME_TAKEN / EMAIL_TAKEN on duplicates.")
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signupRequest) {
        return authService.registerUser(signupRequest);
    }

    /**
     * Professional Login Endpoint
     * Validates credentials against security hashing algorithms inside service handlers.
     */
    @Operation(summary = "Log in",
            description = "Returns {token, username, role}. The JWT carries the role claim and expires in 24h. 401 errorCode INVALID_CREDENTIALS on bad credentials.")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        return authService.loginUser(loginRequest);
    }
    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logoutUser(@RequestHeader("Authorization") String tokenHeader) {
        return authService.logoutUser(tokenHeader);
    }
}
