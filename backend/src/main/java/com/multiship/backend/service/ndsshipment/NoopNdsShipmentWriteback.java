package com.multiship.backend.service.ndsshipment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR1 default implementation of {@link NdsShipmentWriteback} — logs
 * the payload and returns {@code SKIPPED}. Real Oracle writeback
 * lands in a separate PR that replaces this bean (via {@code @Primary}
 * or profile-scoped alternative).
 *
 * <p>Kept as a Spring @Component with no priority annotation so the
 * eventual real implementation just needs to be discovered as another
 * @Component and marked @Primary — no config surgery required.
 */
@Slf4j
@Component
public class NoopNdsShipmentWriteback implements NdsShipmentWriteback {

    @Override
    public Ack writeback(Payload payload) {
        if (payload == null) {
            log.warn("nds-writeback: null payload — ignoring");
            return Ack.skipped("null payload");
        }
        log.info("nds-writeback (STUB): scan={} scope={} client={} batch={} tracking={} carrier={} service={} packages={}",
                payload.scannedValue(),
                payload.scope(),
                payload.clientCode(),
                payload.batchId(),
                payload.trackingNumber(),
                payload.carrierCode(),
                payload.serviceCode(),
                payload.packages() == null ? 0 : payload.packages().size());
        return Ack.skipped("NDS writeback not yet implemented — payload logged only");
    }
}
