package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackPackagePayload;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V89 — thin adapter that lets legacy callers of {@link NdsShipmentWriteback}
 * (any code holding this bean directly) reach the new
 * {@link NdsShipmentOracleWriter} without changing their call site.
 *
 * <p>The original scaffold was a no-op stub named
 * {@code NoopNdsShipmentWriteback}; V89 promoted the shared payload
 * shape into {@code service/externalsystems/writeback/} and wired the
 * real Oracle writer up. This bean bridges the two shapes so anything
 * still holding the old interface (there is nothing today — grep is
 * empty — but the interface stays for backwards-compat and doc value)
 * keeps working.
 *
 * <p>The primary path is via {@link com.multiship.backend.service.externalsystems.writeback.ExternalSystemWritebackDispatcher};
 * production label-generate wiring never calls this class directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoopNdsShipmentWriteback implements NdsShipmentWriteback {

    private final NdsShipmentOracleWriter writer;

    @Override
    public Ack writeback(Payload payload) {
        if (payload == null) {
            log.warn("nds-writeback: null payload — ignoring");
            return Ack.skipped("null payload");
        }
        // Translate legacy → shared shape and delegate.
        WritebackPayload shared = WritebackPayload.builder()
                .scannedValue(payload.scannedValue())
                .clientCode(payload.clientCode())
                .batchId(payload.batchId())
                .trackingNumber(payload.trackingNumber())
                .shipDate(LocalDateTime.now())
                .status("SHIPPED")
                .carrierCode(payload.carrierCode())
                .serviceCode(payload.serviceCode())
                .freightAmount(payload.freightAmount())
                .currency(payload.currency())
                .packages(mapPkgs(payload.packages()))
                .build();
        WritebackAck ack = writer.writeShipment(shared);
        return switch (ack.status()) {
            case OK -> Ack.ok(ack.detail());
            case FAILED -> Ack.failed(ack.detail());
            case SKIPPED -> Ack.skipped(ack.detail());
        };
    }

    private static List<WritebackPackagePayload> mapPkgs(List<PackagePayload> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return List.of();
        return pkgs.stream()
                .map(p -> new WritebackPackagePayload(p.sequence(), p.containerNo(),
                        p.containerIds(), p.orderNos(), p.orderSuffix(),
                        p.weight(), p.weightUnit(), p.packageTracking()))
                .toList();
    }
}
