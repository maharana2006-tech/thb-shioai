package com.multiship.backend.service.output;

import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.ShipmentDocument;
import com.multiship.backend.repository.ClientOutputDestinationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 52 — the routing hub. Every generated label / CI PDF flows
 * through here.
 *
 * <p>Flow for {@link #dispatch(DocType, byte[], DispatchContext)}:
 * <ol>
 *   <li>Persist a DB copy via {@link DatabaseCopyDriver} — always.
 *       Independent of any per-client destination configuration so
 *       the retrieve-later API works regardless.</li>
 *   <li>Look up active {@link ClientOutputDestination}s for
 *       {@code (clientCode, docType)}.</li>
 *   <li>For each, resolve the driver by {@link DestinationType} and
 *       dispatch. One failure does NOT abort the others — errors are
 *       aggregated on the {@link DispatchResult}.</li>
 * </ol>
 *
 * <p>Metrics via Micrometer:
 * {@code output_dispatch_total{destination_type, doc_type, outcome}}.
 */
@Slf4j
@Service
public class OutputDestinationService {

    private final ClientOutputDestinationRepository destinationRepository;
    private final DatabaseCopyDriver databaseCopyDriver;
    private final Map<DestinationType, OutputDriver> driversByType;
    private final MeterRegistry meterRegistry;

    @Autowired
    public OutputDestinationService(
            ClientOutputDestinationRepository destinationRepository,
            DatabaseCopyDriver databaseCopyDriver,
            List<OutputDriver> drivers,
            MeterRegistry meterRegistry) {
        this.destinationRepository = destinationRepository;
        this.databaseCopyDriver    = databaseCopyDriver;
        this.meterRegistry         = meterRegistry;

        // Materialise once — Spring gives us every OutputDriver bean.
        this.driversByType = new EnumMap<>(DestinationType.class);
        for (OutputDriver d : drivers) {
            OutputDriver prev = driversByType.put(d.supports(), d);
            if (prev != null) {
                log.warn("Multiple OutputDriver beans for {} — keeping {} over {}",
                        d.supports(), d.getClass().getSimpleName(),
                        prev.getClass().getSimpleName());
            }
        }
        log.info("OutputDestinationService ready with drivers: {}", driversByType.keySet());
    }

    /**
     * Dispatch one document.
     *
     * @param docType kind of document (LABEL / CI). Used both to look up
     *                destinations and to pick file extensions in drivers.
     * @param payload bytes to send. Null / empty payloads short-circuit
     *                to an empty result — nothing is persisted or sent.
     * @param ctx     per-dispatch context (shipment / order / client).
     *                {@code clientCode} MUST be set; otherwise we can't
     *                look up destinations and only the DB copy is saved.
     */
    public DispatchResult dispatch(DocType docType, byte[] payload, DispatchContext ctx) {
        if (payload == null || payload.length == 0) {
            log.debug("OutputDestinationService: empty {} for {} — skipping.", docType, ctx);
            return DispatchResult.empty(null);
        }

        // 1) Always-on DB copy.
        ShipmentDocument saved = null;
        try {
            saved = databaseCopyDriver.persist(docType, payload, ctx);
        } catch (Exception ex) {
            log.error("DatabaseCopyDriver.persist failed for shipment={} docType={}: {}",
                    ctx.shipmentId(), docType, ex.getMessage(), ex);
            // Do NOT abort — external delivery still worth attempting.
        }

        Long savedId = saved == null ? null : saved.getId();
        DispatchResult.DispatchResultBuilder resultBuilder = DispatchResult.builder()
                .shipmentDocumentId(savedId);

        if (ctx.clientCode() == null || ctx.clientCode().isBlank()) {
            log.debug("OutputDestinationService: no clientCode on ctx — DB copy only.");
            return resultBuilder
                    .totalDestinations(0).successCount(0).failureCount(0)
                    .items(new ArrayList<>()).build();
        }

        // 2) Configured destinations.
        List<ClientOutputDestination> destinations =
                destinationRepository.findByClientCodeAndDocTypeAndActiveTrueOrderById(
                        ctx.clientCode(), docType);

        List<DispatchResult.Item> items = new ArrayList<>(destinations.size());
        int success = 0, failure = 0;
        for (ClientOutputDestination dest : destinations) {
            DispatchResult.Item item = dispatchOne(dest, docType, payload, ctx);
            items.add(item);
            if (item.isSuccess()) { success++; } else { failure++; }
        }

        return resultBuilder
                .totalDestinations(destinations.size())
                .successCount(success)
                .failureCount(failure)
                .items(items)
                .build();
    }

    /**
     * Dispatch to one destination. Wraps every driver invocation in a
     * try/catch so a single misconfigured destination cannot abort the
     * batch. Records a Micrometer counter for both outcomes.
     */
    private DispatchResult.Item dispatchOne(ClientOutputDestination dest,
                                            DocType docType,
                                            byte[] payload,
                                            DispatchContext ctx) {
        OutputDriver driver = driversByType.get(dest.getDestinationType());
        if (driver == null) {
            String msg = "no driver registered for " + dest.getDestinationType();
            recordMetric(dest.getDestinationType(), docType, "failure");
            log.error("OutputDestinationService: destination_id={} client={} — {}",
                    dest.getId(), dest.getClientCode(), msg);
            return DispatchResult.Item.builder()
                    .destinationId(dest.getId())
                    .destinationType(dest.getDestinationType())
                    .success(false)
                    .failureMessage(msg).build();
        }
        try {
            driver.dispatch(dest, docType, payload, ctx);
            recordMetric(dest.getDestinationType(), docType, "success");
            return DispatchResult.Item.builder()
                    .destinationId(dest.getId())
                    .destinationType(dest.getDestinationType())
                    .success(true).build();
        } catch (Exception ex) {
            recordMetric(dest.getDestinationType(), docType, "failure");
            log.error("OutputDestinationService: dispatch failed destination_id={} type={} "
                            + "client={} doc={} cause={}",
                    dest.getId(), dest.getDestinationType(), dest.getClientCode(),
                    docType, ex.getMessage(), ex);
            return DispatchResult.Item.builder()
                    .destinationId(dest.getId())
                    .destinationType(dest.getDestinationType())
                    .success(false)
                    .failureMessage(ex.getMessage()).build();
        }
    }

    private void recordMetric(DestinationType destType, DocType docType, String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("output_dispatch_total")
                .description("Sprint 52 — output routing dispatch attempts by destination type / doc type / outcome")
                .tag("destination_type", destType.name())
                .tag("doc_type",         docType.name())
                .tag("outcome",          outcome)
                .register(meterRegistry)
                .increment();
    }

    /** Convenience — the "does this client have any active destinations for docType?"
     *  query the bulk-label wiring uses to decide whether to skip the ZIP. */
    public boolean hasActiveDestinations(String clientCode, DocType docType) {
        if (clientCode == null || clientCode.isBlank()) return false;
        return !destinationRepository
                .findByClientCodeAndDocTypeAndActiveTrueOrderById(clientCode, docType)
                .isEmpty();
    }

    /**
     * Admin "Test" button — dispatch straight to one destination without
     * persisting a DB copy or touching the lookup index. Bypasses the
     * usual "look up every active destination" path; used only from
     * {@link OutputDestinationAdminService#test(Long)}. Metrics still
     * fire so the test action shows up in dashboards.
     */
    public DispatchResult testDispatch(ClientOutputDestination dest,
                                       DocType docType,
                                       byte[] payload,
                                       DispatchContext ctx) {
        DispatchResult.Item item = dispatchOne(dest, docType, payload, ctx);
        return DispatchResult.builder()
                .shipmentDocumentId(null)
                .totalDestinations(1)
                .successCount(item.isSuccess() ? 1 : 0)
                .failureCount(item.isSuccess() ? 0 : 1)
                .items(new ArrayList<>(java.util.List.of(item)))
                .build();
    }
}
