package com.multiship.backend.service;

import com.multiship.backend.model.SignupAttempt;
import com.multiship.backend.repository.SignupAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 0.5 PR D — 1-hour moving-window rate limit on the
 * public signup endpoint.
 *
 * <p>Enforced per email AND per IP with independent budgets so a
 * shared corporate NAT doesn't lock out every user on the same
 * subnet. Both budgets are configurable via
 * {@code signup.rate-limit.email-per-hour} / {@code .ip-per-hour}.
 *
 * <p>Backed by the {@code signup_attempts} table (Sprint 50 PR A) so
 * the window survives restarts and is visible across replicas without
 * Redis. Successful + failed attempts both count — the limit is on
 * volume, not outcome, matching the goal (block probing).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupRateLimiter {

    private final SignupAttemptRepository repository;

    @Value("${signup.rate-limit.email-per-hour:5}")
    private int emailPerHour;

    @Value("${signup.rate-limit.ip-per-hour:20}")
    private int ipPerHour;

    /**
     * @return true when the attempt is under both caps; false when
     * either the email or IP budget is exhausted for the current hour.
     */
    @Transactional(readOnly = true)
    public boolean isAllowed(String email, String ip) {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);
        long emailCount = repository.countByEmailAndCreatedAtAfter(email, windowStart);
        if (emailCount >= emailPerHour) {
            log.warn("Signup rate-limit: email {} hit cap ({}/{})",
                    email, emailCount, emailPerHour);
            return false;
        }
        long ipCount = repository.countByIpAndCreatedAtAfter(ip, windowStart);
        if (ipCount >= ipPerHour) {
            log.warn("Signup rate-limit: ip {} hit cap ({}/{})",
                    ip, ipCount, ipPerHour);
            return false;
        }
        return true;
    }

    /**
     * Records an attempt (successful or not). Callers invoke this even
     * on failures so probing counts toward the cap.
     */
    @Transactional
    public void record(String email, String ip, boolean succeeded) {
        SignupAttempt attempt = new SignupAttempt();
        attempt.setEmail(email);
        attempt.setIp(ip);
        attempt.setSucceeded(succeeded);
        attempt.setCreatedAt(LocalDateTime.now());
        repository.save(attempt);
    }
}
