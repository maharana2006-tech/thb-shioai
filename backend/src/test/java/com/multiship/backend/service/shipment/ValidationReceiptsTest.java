package com.multiship.backend.service.shipment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationReceiptsTest {

    @Test
    void remembers_a_fingerprint_for_the_ttl_without_redis() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        ValidationReceipts receipts = new ValidationReceipts(none);

        assertFalse(receipts.isFresh("abc"));
        Instant until = receipts.remember("abc");
        assertTrue(receipts.isFresh("abc"));
        assertTrue(until.isAfter(Instant.now().plus(ValidationReceipts.TTL).minusSeconds(5)));
        assertFalse(receipts.isFresh(null));
        assertFalse(receipts.isFresh("other"));
    }
}
