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
 * Phase 3 gate (Shipment &amp; Label re-architecture) — none of the removed
 * label paths may come back. Scans {@code src/main} so the old in-controller
 * endpoints, the synthetic-label builders, the Labelary rotation knob and
 * the deleted helpers stay deleted. Precise identifiers, not English words,
 * so the classes that document the removal cannot trip it.
 */
class LabelArchitectureGateTest {

    private static final List<String> FORBIDDEN = List.of(
            // the Labelary 180° rotation knob
            "label.labelary.rotation", "LABEL_LABELARY_ROTATION", "X-Rotation",
            // synthetic-label (facsimile) builders
            "PdfLabelService", "zplLabelService.buildLabel(", "buildMultiPagePdf(",
            // the old in-controller label endpoints
            "\"/{orderNo}/label/zpl\"", "\"/{orderNo}/label/pdf\"", "\"/{orderNo}/label/preview.png\"",
            "@GetMapping(\"/{orderNo}/label\")",
            // their private helpers
            "renderPngPreferZebrash", "rasterOrPdfCarrierZpl", "realLabelPng(", "hasRealLabel(",
            "renderZplsToPdfViaLabelary");

    @Test
    void no_removed_label_path_survives_in_main_sources() throws IOException {
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
        assertTrue(hits.isEmpty(), "Removed label paths resurfaced:\n  " + String.join("\n  ", hits));
    }
}
