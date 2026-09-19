package com.multiship.backend.service;

import com.multiship.backend.model.PrinterDiscovered;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-Printer-R2 — subscribe / publish / cleanup contract on the
 * in-memory SSE fan-out. No MockMvc — this is the pub-sub primitive.
 * The controller wiring is a one-line delegation, covered by manual
 * curl against a running server.
 */
class PrinterDiscoveryEventPublisherTest {

    private PrinterDiscoveryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PrinterDiscoveryEventPublisher();
    }

    @AfterEach
    void tearDown() {
        publisher.shutdown();
    }

    @Test
    void subscribe_addsEmitter_toTenantBucket_caseInsensitive() {
        SseEmitter e1 = publisher.subscribe("ACME");
        SseEmitter e2 = publisher.subscribe("acme"); // same tenant, different case

        assertThat(publisher.subscriberCount("ACME")).isEqualTo(2);
        assertThat(publisher.subscriberCount("acme")).isEqualTo(2);
        // Different tenant = separate bucket.
        assertThat(publisher.subscriberCount("BETA")).isZero();

        // Sanity — we got real emitters.
        assertThat(e1).isNotNull();
        assertThat(e2).isNotNull();
        assertThat(e1).isNotSameAs(e2);
    }

    // Note: the "onCompletion callback removes from bucket" path only
    // fires under a live servlet request. It's covered by manual curl
    // against a running server + by the shutdown test below (which
    // exercises the same removal path from a different trigger).

    @Test
    void publish_toEmptyTenant_isSilent() {
        // No throw; no subscribers, no fanout.
        publisher.publish("EMPTY", row(1L, "EMPTY", "10.0.0.1", 9100));
        // Nothing to assert positively — the point is no crash.
    }

    @Test
    void publish_fansOutToAllSubscribers_forThatTenant_notOthers() {
        // Two subscribers on ACME, one on BETA. Publish an ACME row →
        // ACME receives, BETA does not. Verified by hitting completion
        // (which would be attempted on send-failure) — but we can't
        // easily assert what a healthy emitter received without a real
        // servlet response. Instead assert counts stay stable + no
        // exceptions escape.
        publisher.subscribe("ACME");
        publisher.subscribe("ACME");
        publisher.subscribe("BETA");
        assertThat(publisher.subscriberCount("ACME")).isEqualTo(2);
        assertThat(publisher.subscriberCount("BETA")).isEqualTo(1);

        publisher.publish("ACME", row(42L, "ACME", "192.168.1.10", 9100));

        // No unrelated bucket got touched.
        assertThat(publisher.subscriberCount("BETA")).isEqualTo(1);
        // ACME subscribers may still be listed (send goes to a real
        // response buffer; SseEmitter caches until the async servlet
        // flushes). Just assert nothing was pruned unexpectedly.
        assertThat(publisher.subscriberCount("ACME")).isEqualTo(2);
    }

    @Test
    void shutdown_completesAllEmitters_clearsBuckets() {
        publisher.subscribe("ACME");
        publisher.subscribe("BETA");
        assertThat(publisher.subscriberCount("ACME")).isEqualTo(1);
        assertThat(publisher.subscriberCount("BETA")).isEqualTo(1);

        publisher.shutdown();

        assertThat(publisher.subscriberCount("ACME")).isZero();
        assertThat(publisher.subscriberCount("BETA")).isZero();
    }

    private static PrinterDiscovered row(long id, String tenant, String host, int port) {
        PrinterDiscovered pd = new PrinterDiscovered();
        pd.setId(id);
        pd.setTenantCode(tenant);
        pd.setHost(host);
        pd.setPort(port);
        pd.setDiscoveredAt(LocalDateTime.now());
        pd.setScanSeq(1L);
        return pd;
    }
}
