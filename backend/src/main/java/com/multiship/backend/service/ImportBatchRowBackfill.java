package com.multiship.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Moves file imports saved before V87 from rows_json into import_batch_row,
 * once, in the background after startup. Each batch is stored, then its
 * rows_json dropped only if unchanged since it was read. Until a batch is
 * moved, the service reads its rows_json as before; its next write moves it
 * too. Idempotent: a restart picks up whatever is left.
 *
 * <p>ponytail: a write to a batch in the seconds between this reading and
 * storing its rows could be overwritten in the table; startup-only and rare —
 * a per-batch lock shared with the service's writes if that ever matters.
 */
@Slf4j
@Component
public class ImportBatchRowBackfill implements ApplicationRunner {

    private final ImportBatchRepository batches;
    private final ImportBatchRowStore rows;
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${multiship.import-rows.backfill.enabled:true}")
    private boolean enabled;

    public ImportBatchRowBackfill(ImportBatchRepository batches, ImportBatchRowStore rows) {
        this.batches = batches;
        this.rows = rows;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        Thread t = new Thread(this::moveAll, "import-rows-backfill");
        t.setDaemon(true);
        t.start();
    }

    void moveAll() {
        List<Long> ids;
        try {
            ids = batches.findIdsWithRowsJson();
        } catch (Exception e) {
            log.warn("Import rows backfill: could not list batches: {}", e.getMessage());
            return;
        }
        if (ids.isEmpty()) return;
        log.info("Import rows backfill: moving {} import(s) from rows_json to import_batch_row", ids.size());
        long started = System.currentTimeMillis();
        int moved = 0;
        int rowCount = 0;
        for (Long id : ids) {
            try {
                ImportBatch b = batches.findById(id).orElse(null);
                if (b == null || !StringUtils.hasText(b.getRowsJson())) continue;
                String payload = b.getRowsJson();
                List<OrderImportRowDTO> list = json.readValue(payload, new TypeReference<List<OrderImportRowDTO>>() { });
                rows.store(id, list);
                if (batches.clearRowsJsonIfUnchanged(id, md5(payload)) == 1) {
                    moved++;
                    rowCount += list.size();
                } else {
                    log.info("Import rows backfill: import {} changed while moving — its next write moves it", id);
                }
            } catch (Exception e) {
                // Left in rows_json, still read from there; retried on the next start.
                log.warn("Import rows backfill: import {} not moved: {}", id, e.getMessage());
            }
        }
        log.info("Import rows backfill: moved {} import(s), {} row(s), in {} ms",
                moved, rowCount, System.currentTimeMillis() - started);
    }

    static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
