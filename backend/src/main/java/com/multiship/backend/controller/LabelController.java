package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelArtifactDTO;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.service.label.LabelArtifactService;
import com.multiship.backend.service.label.LabelPrintableService;
import com.multiship.backend.service.label.LabelRenderService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The label resource (Shipment &amp; Label re-architecture, Phases 1–3).
 *
 * <p>Thin by design: HTTP mapping, auth, status codes and content types.
 * Every body is produced by {@link LabelArtifactService} (what the bytes
 * are), {@link LabelRenderService} (what they look like) and
 * {@link LabelPrintableService} (what prints). The preview's {@code ETag}
 * is the SHA-256 of the exact bytes the ZPL endpoint returns, so a client
 * can verify it is looking at a render of what it will copy and print.
 */
@RestController
@RequestMapping("/api/v1/labels")
public class LabelController {

    /** Tells a client that the ZPL is a {@code ^GFA} wrap of a raster the
     *  carrier returned, not native thermal markup. */
    static final String SOURCE_HEADER = "X-Label-Source";

    private final LabelArtifactService artifacts;
    private final LabelRenderService render;
    private final LabelPrintableService printable;

    public LabelController(LabelArtifactService artifacts, LabelRenderService render,
                           LabelPrintableService printable) {
        this.artifacts = artifacts;
        this.render = render;
        this.printable = printable;
    }

    @Operation(summary = "Label model for the Shipment & Label screen: state, carrier, environment, per-package format and source")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<LabelArtifactDTO>> describe(@PathVariable Integer orderNo) {
        return artifacts.describe(orderNo)
                .map(dto -> ResponseEntity.ok(ApiResponse.<LabelArtifactDTO>builder()
                        .status("SUCCESS").code(200).data(dto).build()))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<LabelArtifactDTO>builder()
                        .status("error").code(404).message("Order " + orderNo + " was not found.").build()));
    }

    @Operation(summary = "One package's ZPL: native carrier ZPL verbatim, or a ^GFA wrap of a raster label")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/packages/{seq}/zpl", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> packageZpl(@PathVariable Integer orderNo, @PathVariable Integer seq) {
        Optional<LabelPackage> pkg = artifacts.pkg(orderNo, seq);
        if (pkg.isEmpty()) return ResponseEntity.notFound().build();
        Optional<byte[]> zpl = artifacts.zpl(orderNo, seq);
        if (zpl.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(SOURCE_HEADER, LabelArtifactService.isRaster(pkg.get()) ? "raster" : "native")
                .header(HttpHeaders.ETAG, LabelRenderService.etag(zpl.get()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=label-" + orderNo + "-pkg" + seq + ".zpl")
                .body(new String(zpl.get(), StandardCharsets.ISO_8859_1));
    }

    @Operation(summary = "Every package's ZPL concatenated for spooling to a thermal printer")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/zpl", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> allZpl(@PathVariable Integer orderNo) {
        return artifacts.zplAll(orderNo)
                .map(z -> ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, LabelRenderService.etag(z))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=label-" + orderNo + ".zpl")
                        .body(new String(z, StandardCharsets.ISO_8859_1)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Cheap existence probe for the screen: no render involved. */
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @RequestMapping(value = "/{orderNo}/packages/{seq}/preview.png", method = RequestMethod.HEAD)
    public ResponseEntity<Void> previewHead(@PathVariable Integer orderNo, @PathVariable Integer seq) {
        return artifacts.zpl(orderNo, seq).isPresent()
                ? ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE).build()
                : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Preview PNG — a render of the exact bytes the ZPL endpoint returns (ETag = their SHA-256)")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/packages/{seq}/preview.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPng(@PathVariable Integer orderNo, @PathVariable Integer seq) {
        Optional<LabelPackage> pkg = artifacts.pkg(orderNo, seq);
        if (pkg.isEmpty()) return ResponseEntity.notFound().build();
        Optional<byte[]> zpl = artifacts.zpl(orderNo, seq);
        if (zpl.isEmpty()) return ResponseEntity.notFound().build();

        // Always a render of the ZPL bytes themselves — raster included — so
        // what the screen shows is what Copy ZPL hands the printer.
        byte[] png = render.renderPng(zpl.get());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, LabelRenderService.etag(zpl.get()))
                .header(SOURCE_HEADER, LabelArtifactService.isRaster(pkg.get()) ? "raster" : "native")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @Operation(summary = "Printable PDF — one 4×6 page per package in sequence, from the same render as the preview; ?seq= for one package")
    @PreAuthorize("@orderAccess.canViewOrder(authentication, #orderNo)")
    @GetMapping(value = "/{orderNo}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Integer orderNo,
                                      @RequestParam(name = "seq", required = false) Integer seq) {
        if (seq != null && artifacts.pkg(orderNo, seq).isEmpty()) return ResponseEntity.notFound().build();
        String name = seq != null ? "label-" + orderNo + "-pkg" + seq + ".pdf" : "label-" + orderNo + ".pdf";
        return printable.pdf(orderNo, seq)
                .map(body -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + name)
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
