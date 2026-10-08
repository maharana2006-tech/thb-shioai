package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.service.ManifestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * CARRIER_CLOSEOUT (V135) — automatic end-of-day close. Each run closes out
 * today's open labels (manifest / SCAN form) for every configured carrier via
 * {@link ManifestService#closeOutForDay}, the same path as the Settings page's
 * "Close out today" button. Each close is persisted to carrier_eod_log, so a
 * failed scheduled close leaves a trace beyond this run's summary. Starts OFF.
 *
 * <p>Params: {@code carriers} ([] = FEDEX,UPS,USPS), {@code customerNo}
 * ("" = every client), {@code warehouseCode} ("" = every warehouse).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarrierCloseoutJobRunner implements DtcSchedulerJobRunner {

    public static final String KEY = "CARRIER_CLOSEOUT";
    private static final List<String> DEFAULT_CARRIERS = List.of("FEDEX", "UPS", "USPS");

    private final ManifestService manifestService;

    @Override
    public String jobKey() {
        return KEY;
    }

    @Override
    public Outcome run(Map<String, Object> params) {
        List<String> carriers = carriers(params.get("carriers"));
        String customerNo = str(params.get("customerNo"));
        String warehouse = str(params.get("warehouseCode"));
        LocalDate today = LocalDate.now();

        int manifested = 0, empty = 0, failed = 0, totalTrackings = 0;
        StringBuilder detail = new StringBuilder();
        for (String carrier : carriers) {
            ApiResponse<ManifestResponseDTO> resp =
                    manifestService.closeOutForDay(carrier, customerNo, today, warehouse, "SCHEDULED");
            ManifestResponseDTO d = resp == null ? null : resp.getData();
            String status = d == null ? "ERROR" : d.getStatus();
            int count = d == null ? 0 : d.getTrackingCount();
            totalTrackings += count;
            switch (status == null ? "ERROR" : status) {
                case "MANIFESTED", "PARTIAL" -> manifested++;
                case "EMPTY", "NOT_SUPPORTED" -> empty++;
                default -> failed++;
            }
            if (detail.length() > 0) detail.append("; ");
            detail.append(carrier).append(": ").append(status)
                    .append(count > 0 ? " (" + count + ")" : "");
        }

        String msg = String.format("%d carrier(s): %d closed, %d nothing-to-close, %d failed; %d label(s). [%s]",
                carriers.size(), manifested, empty, failed, totalTrackings, detail);
        if (failed > 0 && manifested == 0) {
            return new Outcome(DtcSchedulerEngine.FAILED, msg);
        }
        return Outcome.success(msg);
    }

    private static List<String> carriers(Object v) {
        if (!(v instanceof Collection<?> c)) return DEFAULT_CARRIERS;
        List<String> list = c.stream()
                .filter(x -> x != null && !x.toString().isBlank())
                .map(x -> x.toString().trim().toUpperCase())
                .distinct()
                .toList();
        return list.isEmpty() ? DEFAULT_CARRIERS : list;
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }
}
