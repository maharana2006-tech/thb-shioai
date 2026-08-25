package com.multiship.backend.service.wms;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsAddress;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsContainer;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.service.OrderImportServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pulls pending (shippable) shipments from the external WMS and records each
 * fetch as ONE import batch — the same shape a CSV/XLSX upload produces. The
 * batch surfaces under the "API" section of Order Intake (source = WMS), where
 * the operator can edit rows inline, see per-row validation, and generate
 * carrier labels. Generating stamps the resulting orders as source = API so
 * they stay grouped under that section.
 *
 * <p>Each fetch is its own batch snapshot of the WMS's current pending
 * shipments (no cross-fetch dedup) — matching "one fetch = one batch".
 */
@Service
@RequiredArgsConstructor
public class WmsService {

    private static final Logger log = LoggerFactory.getLogger(WmsService.class);

    private final WmsClient wmsClient;

    /** Persists the fetch as an import batch (optional so the reduced-args
     *  unit-test constructor still compiles). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRepository importBatchRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper importObjectMapper;

    public boolean isConfigured() {
        return wmsClient.isConfigured();
    }

    @Transactional
    public WmsPullResultDTO pullShippable(String requestedBy) {
        if (!wmsClient.isConfigured()) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of("WMS is not configured. Set WMS_BASE_URL to enable the pull."))
                    .importedOrderNos(List.of())
                    .build();
        }

        List<WmsPendingOrderDTO> shippable = wmsClient.fetchShippable();
        List<OrderImportRowDTO> rows = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        int failed = 0;

        for (WmsPendingOrderDTO src : shippable) {
            String externalId = src == null ? null : src.getShipmentNumber();
            if (!StringUtils.hasText(externalId)) {
                failed++;
                messages.add("Skipped a WMS shipment with no shipmentNumber.");
                continue;
            }
            OrderImportRowDTO row = toImportRow(src, rows.size() + 1);
            // Validate up front so the grid shows what needs fixing (e.g. the
            // client to bill) the moment the batch is opened. Editing a cell
            // re-runs the same validator, so errors clear as they're resolved.
            row.setErrors(OrderImportServiceImpl.validateRow(row));
            rows.add(row);
        }

        int total = rows.size();
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();

        // Dedup: fetching the same pending shipments again must NOT pile up
        // duplicate batches. The content hash is computed from the as-fetched
        // rows (deterministic), so an unchanged WMS pending set maps to the
        // existing batch — even after the operator has edited/generated it.
        Long importBatchId = null;
        boolean deduped = false;
        if (total > 0 && importBatchRepository != null) {
            String hash = OrderImportServiceImpl.contentHash(rows);
            ImportBatch existing = hash == null ? null
                    : importBatchRepository.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
            if (existing != null) {
                importBatchId = existing.getId();
                deduped = true;
                messages.add("These shipments were already fetched — showing batch #" + existing.getId() + ".");
            } else {
                importBatchId = recordBatch(requestedBy, rows, invalid, hash);
            }
        }

        log.info("WMS pull ({}): fetched {}, {} row(s) → batch #{}{} ({} need fixes, {} skipped)",
                requestedBy, shippable.size(), total, importBatchId,
                deduped ? " (existing, deduped)" : "", invalid, failed);
        return WmsPullResultDTO.builder()
                .configured(true)
                .fetched(shippable.size())
                .imported(deduped ? 0 : total)
                .skipped(deduped ? total : 0)     // already-present when the same set was re-fetched
                .failed(failed)
                .batchId(null)               // label batch is assigned when labels are generated
                .importBatchId(importBatchId)
                .importedOrderNos(List.of())
                .messages(messages)
                .build();
    }

    /** Persist the fetched shipments as one editable/generatable import batch. */
    private Long recordBatch(String requestedBy, List<OrderImportRowDTO> rows, int invalid, String contentHash) {
        if (importBatchRepository == null) return null;
        try {
            ImportBatch batch = new ImportBatch();
            batch.setCreatedBy(requestedBy);
            batch.setFileName("WMS fetch — " + rows.size() + " shipment" + (rows.size() == 1 ? "" : "s"));
            batch.setSource("WMS");
            // DRAFT while any row still needs fixing (Generate is gated off);
            // INITIATE = all clean and ready to generate.
            batch.setStatus(invalid > 0 ? "DRAFT" : "INITIATE");
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalRows(rows.size());
            batch.setSavedRows(rows.size());
            batch.setInvalidRows(invalid);
            batch.setBillingMode("AUTO");
            batch.setContentHash(contentHash);   // identifies this fetch for re-fetch dedup
            batch.setRowsJson(importObjectMapper != null ? importObjectMapper.writeValueAsString(rows) : "[]");
            return importBatchRepository.save(batch).getId();
        } catch (Exception e) {
            log.warn("WMS pull: could not record the fetch batch: {}", e.getMessage());
            return null;
        }
    }

    /** Map one WMS pending shipment to an editable import row. */
    private OrderImportRowDTO toImportRow(WmsPendingOrderDTO src, int rowNumber) {
        WmsAddress to = src.getShipToAddress();
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setRowNumber(rowNumber);
        r.setOrderRef(firstNonBlank(src.getOrderNo(), src.getShipmentNumber()));
        // WMS gives no customer number in the pending feed — the operator picks
        // the billing client in the grid (validateRow flags it as required).
        r.setClientCode(trimOrNull(src.getCustNo()));
        if (to != null) {
            r.setRecipientName(firstNonBlank(to.getName(), to.getAttn()));
            r.setRecipientCompany(to.getAttn());
            r.setRecipientPhone(digitsOrNull(to.getPhone()));
            r.setRecipientEmail(trimOrNull(to.getEmail()));
            r.setAddressLine1(street(to.getAddr1()));
            r.setAddressLine2(trimOrNull(to.getAddr2()));
            r.setCity(trimOrNull(to.getCity()));
            r.setState(trimOrNull(to.getState()));
            r.setPostalCode(trimOrNull(to.getZip()));
            r.setCountryCode(firstNonBlank(to.getIso2(), to.getCountry()));
        }
        // Best-effort carrier from the WMS ship-via code; the operator can
        // override it inline. Unknown codes pass through so generation flags them.
        r.setCarrierCode(mapCarrier(src.getShipVia()));
        r.setServiceType(trimOrNull(src.getShipMethod()));
        BigDecimal w = totalWeight(src.getContainers());
        if (w != null) {
            r.setWeight(w);
            r.setWeightUnit("LB");
        }
        r.setReference(trimOrNull(src.getPoNumber()));
        return r;
    }

    /** Heuristic WMS ship-via → carrier code (UPS / FEDEX / USPS). Operator overrides. */
    static String mapCarrier(String shipVia) {
        if (!StringUtils.hasText(shipVia)) return null;
        String s = shipVia.trim().toUpperCase();
        if (s.startsWith("USP") || s.startsWith("US")) return "USPS";
        if (s.startsWith("U")) return "UPS";
        if (s.startsWith("F")) return "FEDEX";
        return shipVia.trim();
    }

    /** WMS crams "street  city, ST zip" into addr1 with long whitespace runs — keep the street. */
    private static String street(String addr1) {
        if (!StringUtils.hasText(addr1)) return addr1;
        return addr1.trim().split("\\s{2,}")[0].trim();
    }

    private static BigDecimal totalWeight(List<WmsContainer> containers) {
        if (containers == null) return null;
        double sum = containers.stream()
                .filter(Objects::nonNull)
                .map(WmsContainer::getWeight)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return sum > 0 ? BigDecimal.valueOf(sum) : null;
    }

    private static String trimOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    /** Keep a phone as its dialable digits (WMS sends "14697017960"-style strings). */
    private static String digitsOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a.trim();
        return StringUtils.hasText(b) ? b.trim() : null;
    }
}
