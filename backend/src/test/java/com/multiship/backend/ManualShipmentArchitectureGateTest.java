package com.multiship.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual Shipment redesign, Phase 4 gate — the retired purchase paths may not
 * come back. There is one purchase path ({@code ManualShipmentService.purchase}
 * behind {@code POST /shipments}); the in-controller delegates, the "legacy
 * body" name and the phase-C regenerate split stay deleted. Precise
 * identifiers, not English words, so documentation of the removal cannot trip it.
 */
class ManualShipmentArchitectureGateTest {

    private static final List<String> FORBIDDEN = List.of(
            // the old in-controller purchase endpoints
            "@PostMapping(\"/manual-label\")", "@PostMapping(\"/{orderNo}/regenerate\")",
            // the pre-split purchase body and its flagged variants
            "generateManualLabelLegacyBody", "generateManualLabelSplit", "generateRegenerateSplit",
            "phaseSplitFullManualRegenerate", "full-manual-regenerate", "clearInFlightSinceForOrder");

    @Test
    void no_retired_manual_purchase_path_survives_in_main_sources() throws IOException {
        Path root = Files.isDirectory(Paths.get("src/main")) ? Paths.get("src/main") : Paths.get("backend/src/main");
        assertTrue(Files.isDirectory(root), "cannot locate src/main from " + Paths.get("").toAbsolutePath());

        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties"))
                    .forEach(p -> {
                        String text;
                        try {
                            text = Files.readString(p, StandardCharsets.ISO_8859_1);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        for (String token : FORBIDDEN) {
                            if (text.contains(token)) hits.add(root.relativize(p) + "  ::  " + token);
                        }
                    });
        }
        assertTrue(hits.isEmpty(), "Retired manual purchase paths resurfaced:\n  " + String.join("\n  ", hits));
    }
}
