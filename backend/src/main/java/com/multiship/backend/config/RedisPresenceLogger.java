package com.multiship.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Sprint 50 Tier 1 finding #7 (ops polish) — surfaces the Redis
 * presence/absence at boot as either a WARN (safe-by-loud) or an INFO.
 *
 * <p>Previously {@code spring.data.redis.host} defaulted to
 * {@code localhost}, which meant a prod deploy that forgot to set
 * {@code REDIS_HOST} silently talked to nothing on the pod and the
 * Idempotency-Key store no-oped without any log signal. That turns a
 * misconfig into a "duplicate POSTs to /shipments create duplicate
 * shipments" incident, discoverable only via customer report.
 *
 * <p>With the default removed (see {@code application.properties}), this
 * listener now runs on {@link ApplicationReadyEvent}:
 * <ul>
 *   <li>{@code REDIS_HOST} blank → WARN with the exact operational
 *       consequence spelled out.</li>
 *   <li>{@code REDIS_HOST} set → INFO with host:port so ops can grep
 *       the boot log to confirm the intended target.</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisPresenceLogger {

    @Value("${spring.data.redis.host:}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private String redisPort;

    @EventListener(ApplicationReadyEvent.class)
    public void logRedisPresence() {
        if (!StringUtils.hasText(redisHost)) {
            log.warn("REDIS DISABLED — Idempotency-Key persistence is off; "
                    + "duplicate POSTs to /shipments will create duplicate "
                    + "shipments. Set REDIS_HOST to enable.");
        } else {
            log.info("Redis idempotency store enabled at {}:{}", redisHost, redisPort);
        }
    }
}
