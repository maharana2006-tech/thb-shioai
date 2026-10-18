package com.multiship.backend.service.resolution;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShipmentResolutionException is an answer ("client has no billing markup"),
 * and callers such as shipment validation catch it and carry on. If it marked
 * their transaction rollback-only, Validate failed with a bare 500 instead.
 */
class ShipmentResolutionNoRollbackTest {

    @Test
    void everyTransactionalMethodLetsItsCallerCatchTheResolutionAnswer() {
        for (Method m : ShipmentResolutionServiceImpl.class.getDeclaredMethods()) {
            Transactional tx = m.getAnnotation(Transactional.class);
            if (tx == null) continue;
            assertTrue(Arrays.asList(tx.noRollbackFor()).contains(ShipmentResolutionException.class), m.getName());
        }
    }
}
