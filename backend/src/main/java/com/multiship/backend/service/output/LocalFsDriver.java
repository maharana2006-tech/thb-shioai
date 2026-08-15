package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ClientOutputDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Sprint 52 — {@link DestinationType#LOCAL_FS} driver. Writes the payload
 * to {@code path/{shipmentId}_{docType}.{ext}} using an atomic move
 * (write to a {@code .part} file, then rename) so a concurrent watcher
 * never sees a half-written file.
 *
 * <p>The driver does NOT create the parent directory — if the configured
 * path doesn't exist the driver fails loud so a typo'd config surfaces
 * rather than silently writing to disk in an unexpected place.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFsDriver implements OutputDriver {

    private final ObjectMapper objectMapper;

    @Override
    public DestinationType supports() {
        return DestinationType.LOCAL_FS;
    }

    @Override
    public void dispatch(ClientOutputDestination destination,
                         DocType docType,
                         byte[] payload,
                         DispatchContext ctx) {
        OutputConfig.LocalFsConfig cfg =
                OutputConfig.parse(objectMapper, destination.getConfig(), OutputConfig.LocalFsConfig.class);
        if (cfg.getPath() == null || cfg.getPath().isBlank()) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "LOCAL_FS destination " + destination.getId() + " has no 'path' set");
        }
        Path dir = Path.of(cfg.getPath());
        if (!Files.isDirectory(dir)) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "LOCAL_FS path is not an existing directory: " + dir);
        }

        String fileName = buildFileName(docType, ctx);
        Path target = dir.resolve(fileName);
        Path tmp    = dir.resolve(fileName + ".part");

        try {
            Files.write(tmp, payload,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("LOCAL_FS delivered {} bytes to {} (destination_id={}, client={})",
                    payload.length, target, destination.getId(), destination.getClientCode());
        } catch (Exception ex) {
            // Best-effort cleanup of the partial file so a retry starts clean.
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { /* fine */ }
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "LOCAL_FS write failed to " + target + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Filename convention. The extension is derived from doc-type
     * defaults; content-type-based sniffing (ZPL vs PDF label) is a
     * follow-up.
     */
    static String buildFileName(DocType docType, DispatchContext ctx) {
        String base;
        if (ctx.fileNameHint() != null && !ctx.fileNameHint().isBlank()) {
            base = ctx.fileNameHint();
        } else {
            Long ship = ctx.shipmentId();
            Integer ord = ctx.orderNo();
            String key = ship != null && ship > 0 ? "s" + ship
                       : ord != null           ? "o" + ord
                       : "unknown-" + System.currentTimeMillis();
            base = key + "_" + docType.name();
        }
        // If the hint already carries an extension, honour it. Otherwise
        // pick sensible default per doc-type / content-type.
        if (base.contains(".")) return base;
        String ext = extensionFor(docType, ctx.contentType());
        return base + "." + ext;
    }

    static String extensionFor(DocType docType, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("zpl")) return "zpl";
            if (ct.contains("pdf")) return "pdf";
        }
        return docType == DocType.LABEL ? "pdf" : "pdf";
    }
}
