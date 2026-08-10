package com.multiship.backend.service.captcha;

/**
 * Sprint 50 Tier 0.5 PR D — CAPTCHA verification surface.
 *
 * <p>Default bean {@link NoOpCaptchaVerifier} passes every token —
 * fine while public signup is disabled (the plan default). Prod
 * deploys that flip {@code signup.public-enabled=true} SHOULD ship a
 * {@code TurnstileCaptchaVerifier} (Cloudflare Turnstile is free +
 * privacy-friendly) that validates the token against the provider's
 * verify endpoint. Zero caller changes when the real bean swaps in.
 */
public interface CaptchaVerifier {

    /** True when the token verifies successfully OR captcha is disabled. */
    boolean verify(String token, String remoteIp);
}
