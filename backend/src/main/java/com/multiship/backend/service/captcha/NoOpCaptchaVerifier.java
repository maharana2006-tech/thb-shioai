package com.multiship.backend.service.captcha;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Sprint 50 Tier 0.5 PR D — accept-everything default. Real
 * TurnstileCaptchaVerifier wired via a config-only follow-up when the
 * deploy flips {@code signup.public-enabled=true}.
 */
@Component
@ConditionalOnMissingBean(value = CaptchaVerifier.class, ignored = NoOpCaptchaVerifier.class)
public class NoOpCaptchaVerifier implements CaptchaVerifier {

    @Override
    public boolean verify(String token, String remoteIp) {
        return true;
    }
}
