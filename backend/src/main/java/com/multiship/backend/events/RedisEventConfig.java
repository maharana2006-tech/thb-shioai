package com.multiship.backend.events;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

/**
 * Spring wiring for the SSE side. Only activates when
 * {@code spring.data.redis.host} is set — same conditional used by
 * {@link com.multiship.backend.config.RedisPresenceLogger}. Keeps
 * the unit-test suite (no Redis) working without changes: when
 * absent, the SseController's null-check returns 503 and the FE
 * falls back to polling.
 *
 * <p>Phase 4 — backed by Redis Streams via
 * {@link StreamMessageListenerContainer}. Each SSE subscription
 * calls {@code container.receive(offset, listener)} to attach an
 * XREAD-BLOCK loop that fans matching stream entries to the emitter.
 * The container itself owns the read-loop thread pool (default sized
 * to the number of cores) — one shared pool for all subscribers, so
 * an operator office with 20 open browsers doesn't spawn 20 blocking
 * threads.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisEventConfig {

    /** Poll timeout for each XREAD BLOCK. Shorter = more responsive
     *  shutdown but more CPU spent re-blocking; longer = smoother
     *  but slower to notice the container was stopped. 2s is a
     *  reasonable balance for our low-volume traffic. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            eventStreamListenerContainer(RedisConnectionFactory factory) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<
                        String, MapRecord<String, String, String>>
                options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(POLL_TIMEOUT)
                .build();
        this.container = StreamMessageListenerContainer.create(factory, options);
        container.start();
        log.info("StreamMessageListenerContainer ready — SSE subscribers will XREAD BLOCK on '{}'.",
                AppEventBus.STREAM_KEY);
        return container;
    }

    /** Explicit shutdown so a K8s rolling deploy tears the XREAD
     *  threads down cleanly instead of leaving them blocked. */
    @PreDestroy
    public void stopContainer() {
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("StreamMessageListenerContainer stopped.");
        }
    }
}
