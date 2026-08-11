package com.multiship.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SignupRequest {

    @NotBlank(message = "{validation.username.required}")
    @Size(min = 3, max = 20, message = "{validation.username.size}")
    private String username;

    @NotBlank(message = "{validation.email.required}")
    @Email(message = "{validation.email.invalid}")
    private String email;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 6, message = "{validation.password.size}")
    private String password;

    @NotBlank(message = "{validation.fullname.required}")
    private String fullName;

    private String role;

    /**
     * Sprint 50 Tier 0.5 PR D — public signup now REQUIRES a clientCode
     * (validated server-side). Invite-based signups get their clientCode
     * from the invite row, not the caller.
     */
    private String clientCode;

    /**
     * Sprint 50 Tier 0.5 PR D — CAPTCHA token from Turnstile / hCaptcha.
     * NoOpCaptchaVerifier accepts any value (default); prod deploys wire
     * a real verifier via a follow-up bean.
     */
    private String captchaToken;
}