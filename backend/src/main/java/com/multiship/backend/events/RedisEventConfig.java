package com.multiship.backend.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Spring wiring for the SSE pub/sub side. Only activates when
 * {@code spring.data.redis.host} is set — the same conditional used
 * by {@link com.multiship.backend.config.RedisPresenceLogger}. Keeps
 * the unit-test suite (no Redis) working without changes: the
 * SseController's null-check on the listener container returns 503
 * from the FE-facing endpoint, and the FE falls back to polling.
 *
 * <p>Only one bean here — the {@link RedisMessageListenerContainer}
 * SseController subscribes on. Spring Boot's Redis auto-config
 * provides the {@link RedisConnectionFactory} and
 * {@link org.springframework.data.redis.core.StringRedisTemplate}
 * beans for free.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisEventConfig {

    @Bean
    public RedisMessageListenerContainer eventListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        log.info("RedisMessageListenerContainer ready — SSE subscribers will attach here.");
        return container;
    }
}
