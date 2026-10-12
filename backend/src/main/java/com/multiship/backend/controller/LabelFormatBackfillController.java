package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.service.LabelFormatConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-off backfill endpoint for V143's pre-rendered label columns.
 * Decodes each label_package row's existing {@code label_file_path},
 * sniffs the format, runs {@link LabelFormatConverter#renderAll} and
 * persists the three columns. Idempotent — re-running on an already-
 * backfilled order just overwrites with freshly converted bytes.
 *
 * <p>Scoped to one order so an operator can smoke-test the Stage 2
 * pipeline against a specific historical shipment without touching
 * the other ~33k rows.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/labels")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LabelFormatBackfillController {

    private final LabelPackageRepository labelPackageRepository;
    private final LabelFormatConverter labelFormatConverter;

    @PostMapping("/{orderNo}/backfill-formats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfill(@PathVariable Integer orderNo) {
        List<LabelPackage> pkgs = labelPackageRepository.findByOrderNoOrderBySequenceNumberAsc(orderNo);
        if (pkgs.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.<Map<String, Object>>builder()
                    .status("error").code(404).message("No label_package rows for order " + orderNo).build());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> perPkg = new java.util.ArrayList<>();
        int touched = 0;
        for (LabelPackage pkg : pkgs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sequenceNumber", pkg.getSequenceNumber());
            byte[] bytes = decode(pkg.getLabelFilePath());
            if (bytes == null) {
                row.put("skipped", "no decodable bytes");
                perPkg.add(row);
                continue;
            }
            String format = sniff(bytes);
            row.put("sourceFormat", format);
            if (format == null) {
                row.put("skipped", "unknown source format");
                perPkg.add(row);
                continue;
            }
            LabelFormatConverter.RenderedFormats rendered = labelFormatConverter.renderAll(bytes, format);
            pkg.setZplData(rendered.zpl());
            pkg.setPdfData(rendered.pdf());
            pkg.setPngData(rendered.png());
            labelPackageRepository.save(pkg);
            touched++;
            row.put("zplBytes", rendered.zpl() != null ? rendered.zpl().length() : null);
            row.put("pdfBytes", rendered.pdf() != null ? rendered.pdf().length() : null);
            row.put("pngBytes", rendered.png() != null ? rendered.png().length() : null);
            perPkg.add(row);
        }
        result.put("orderNo", orderNo);
        result.put("packagesTouched", touched);
        result.put("packages", perPkg);
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("success").code(200).data(result).build());
    }

    private static byte[] decode(String stored) {
        if (!StringUtils.hasText(stored)) return null;
        String t = stored.trim();
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("http://") || l.startsWith("https://")) return null;
        if (t.startsWith("^XA")) return t.getBytes();
        try { return Base64.getDecoder().decode(t); } catch (IllegalArgumentException e) { return null; }
    }

    private static String sniff(byte[] b) {
        int i = 0;
        while (i < b.length && (b[i] == '\n' || b[i] == '\r' || b[i] == ' ' || b[i] == '\t')) i++;
        if (b.length - i >= 3 && b[i] == '^' && b[i + 1] == 'X' && b[i + 2] == 'A') return "ZPL";
        if (b.length - i >= 4 && b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F') return "PDF";
        if (b.length - i >= 4 && b[i] == 'G' && b[i + 1] == 'I' && b[i + 2] == 'F' && b[i + 3] == '8') return "GIF";
        if (b.length - i >= 4 && (b[i] & 0xff) == 0x89 && b[i + 1] == 'P' && b[i + 2] == 'N' && b[i + 3] == 'G') return "PNG";
        return null;
    }
}
