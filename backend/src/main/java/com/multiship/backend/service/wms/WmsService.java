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
    /** Resolves the WMS ship-via / ship-method to a catalog service (optional for unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.ShippingConfigService shippingConfigService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ClientShipviaCodeMapRepository clientShipviaCodeMapRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ShippingServiceRepository shippingServiceRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.CarrierAccountRefRepository carrierAccountRefRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ClientWarehouseRepository clientWarehouseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.WarehouseRepository warehouseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrderImportServiceImpl orderImportService;

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
        int shipments = 0;

        for (WmsPendingOrderDTO src : shippable) {
            String externalId = src == null ? null : src.getShipmentNumber();
            if (!StringUtils.hasText(externalId)) {
                failed++;
                messages.add("Skipped a WMS shipment with no shipmentNumber.");
                continue;
            }
            shipments++;
            OrderImportRowDTO row = toImportRow(src, rows.size() + 1);
            // Validate up front so the grid shows what needs fixing (e.g. the
            // client to bill) the moment the batch is opened. Editing a cell
            // re-runs the same validator, so errors clear as they're resolved.
            List<String> errors = new ArrayList<>(OrderImportServiceImpl.validateRow(row));
            errors.addAll(preflight(src, row));
            row.setErrors(errors);
            rows.add(row);
            // One line per shipped item so the packing list / commercial invoice
            // carries every SKU. The first row is the parcel (it owns the
            // container weight); the item lines inherit it and are flagged as
            // such so generation doesn't turn them into extra boxes.
            for (OrderImportRowDTO itemLine : itemLines(src, row, rows.size() + 1)) {
                itemLine.setErrors(new ArrayList<>(OrderImportServiceImpl.validateRow(itemLine)));
                rows.add(itemLine);
            }
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
                // The same set was fetched before but the operator trashed that
                // batch: its labelled orders still exist, so re-importing would
                // ship them twice. Flag every row that already has a live label
                // and say where the original batch went.
                ImportBatch trashed = null;
                try {
                    trashed = hash == null ? null
                            : importBatchRepository.findFirstByContentHashOrderByIdDesc(hash)
                                .filter(b -> b.getDeletedAt() != null).orElse(null);
                } catch (Exception ignore) { /* optional lookup */ }
                if (orderImportService != null) {
                    try { orderImportService.flagOrderRefsAlreadyGenerated(rows); } catch (Exception ignore) { /* advisory */ }
                }
                long flagged = rows.stream().filter(r -> r.getWarnings() != null
                        && r.getWarnings().stream().anyMatch(w -> w.contains("already generated")))
                        .map(r -> r.getOrderRef() == null ? "" : r.getOrderRef().trim().toUpperCase())
                        .distinct().count();
                if (trashed != null) {
                    messages.add("These shipments were fetched before as batch #" + trashed.getId()
                            + ", which is in Trash. Restore it from Data History → Trash instead of generating again"
                            + (flagged > 0 ? " — " + flagged + " order(s) already have a live label and are flagged." : "."));
                } else if (flagged > 0) {
                    messages.add(flagged + " order(s) were already labelled from an earlier import — generating again creates duplicate shipments.");
                }
                importBatchId = recordBatch(requestedBy, rows, invalid, hash);
            }
        }

        log.info("WMS pull ({}): fetched {}, {} row(s) → batch #{}{} ({} need fixes, {} skipped)",
                requestedBy, shippable.size(), total, importBatchId,
                deduped ? " (existing, deduped)" : "", invalid, failed);
        return WmsPullResultDTO.builder()
                .configured(true)
                .fetched(shippable.size())
                .imported(deduped ? 0 : shipments)
                .skipped(deduped ? shipments : 0)     // already-present when the same set was re-fetched
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
        r.setClientCode(trimOrNull(src.getCustomerReferenceId()));
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
        r.setServiceType(resolveService(r.getClientCode(), r.getCarrierCode(), src.getShipVia(),
                src.getShipMethod(), r.getCountryCode(), r));
        r.setAccountNumber(defaultAccount(r.getClientCode(), r.getCarrierCode()));
        r.setWarehouseCode(defaultWarehouse(r.getClientCode()));
        BigDecimal w = totalWeight(src.getContainers());
        if (w != null) {
            r.setWeight(w);
            r.setWeightUnit("LB");
        }
        r.setReference(trimOrNull(src.getPoNumber()));
        applyFirstItem(src, r);
        return r;
    }

    /**
     * WMS ship-via → catalog service, in order of trust:
     * <ol>
     *   <li>the WMS's own shipMethod when it sends one;</li>
     *   <li>the client's Shipping Service Mapping row for that ERP/ship-via code;</li>
     *   <li>the platform resolver on the raw code (handles "03", "UPS Ground",
     *       "GROUND", "U03"-style codes);</li>
     *   <li>the carrier's ground service, with a warning telling the operator to
     *       map the code — a label on the wrong service beats no label only when
     *       the operator can see it, hence the warning.</li>
     * </ol>
     * Returns null (and leaves the row to preflight) when nothing resolves.
     */
    private String resolveService(String clientCode, String carrier, String shipVia, String shipMethod,
                                  String destCountry, OrderImportRowDTO row) {
        if (shippingConfigService == null) {
            return trimOrNull(shipMethod);   // reduced-args unit-test wiring
        }
        String canon = carrier == null ? null : carrier.trim().toUpperCase();
        if (canon == null || canon.isBlank()) return trimOrNull(shipMethod);
        try {
            if (StringUtils.hasText(shipMethod)) {
                var hit = shippingConfigService.resolveServiceCode(canon, shipMethod, null);
                if (hit.isPresent()) return hit.get().getServiceCode();
            }
            if (StringUtils.hasText(shipVia) && StringUtils.hasText(clientCode)
                    && clientShipviaCodeMapRepository != null && shippingServiceRepository != null) {
                var map = clientShipviaCodeMapRepository
                        .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), shipVia.trim());
                if (map.isPresent() && map.get().getServiceId() != null) {
                    var svc = shippingServiceRepository.findById(map.get().getServiceId());
                    if (svc.isPresent() && StringUtils.hasText(svc.get().getServiceCode())) {
                        return svc.get().getServiceCode();
                    }
                }
            }
            if (StringUtils.hasText(shipVia)) {
                var hit = shippingConfigService.resolveServiceCode(canon, shipVia, null);
                if (hit.isPresent()) return hit.get().getServiceCode();
                // "U03" / "F03" — the WMS prefixes the carrier letter to the carrier's own code.
                String stripped = shipVia.trim().replaceFirst("^(?i)(UPS|FEDEX|USPS|DHL|U|F)[-_ ]?", "");
                if (!stripped.equals(shipVia.trim()) && !stripped.isBlank()) {
                    hit = shippingConfigService.resolveServiceCode(canon, stripped, null);
                    if (hit.isPresent()) return hit.get().getServiceCode();
                }
            }
            var ground = shippingConfigService.resolveServiceCode(canon, "GROUND", null);
            if (ground.isPresent()) {
                addWarning(row, "WMS ship-via '" + (StringUtils.hasText(shipVia) ? shipVia.trim() : "(blank)")
                        + "' isn't mapped for " + clientCode + " — defaulted to " + canon + " "
                        + ground.get().getServiceCode() + " (" + ground.get().getName()
                        + "). Map it under Settings → Shipping Service Mapping to stop this warning.");
                return ground.get().getServiceCode();
            }
        } catch (Exception e) {
            log.warn("WMS pull: service resolution for ship-via '{}' failed: {}", shipVia, e.getMessage());
        }
        return trimOrNull(shipMethod);
    }

    /** The client's default billing account for the carrier, when it has one. */
    private String defaultAccount(String clientCode, String carrier) {
        if (carrierAccountRefRepository == null || !StringUtils.hasText(clientCode) || !StringUtils.hasText(carrier)) return null;
        try {
            return carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode.trim()).stream()
                    .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                    .filter(a -> a.getCarrierCode() != null && a.getCarrierCode().equalsIgnoreCase(carrier.trim()))
                    .map(a -> a.getAccountNumber())
                    .filter(StringUtils::hasText)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The client's default warehouse code (what the New Shipment form pre-selects). */
    private String defaultWarehouse(String clientCode) {
        if (clientWarehouseRepository == null || warehouseRepository == null || !StringUtils.hasText(clientCode)) return null;
        try {
            return clientWarehouseRepository.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode.trim())
                    .flatMap(link -> warehouseRepository.findById(link.getWarehouseId()))
                    .filter(w -> !Boolean.FALSE.equals(w.getActive()) && StringUtils.hasText(w.getCode()))
                    .map(w -> w.getCode())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Items the WMS actually shipped (shipQty > 0; back-ordered lines stay off the label). */
    private static List<WmsPendingOrderDTO.WmsItem> shippedItems(WmsPendingOrderDTO src) {
        List<WmsPendingOrderDTO.WmsItem> out = new ArrayList<>();
        if (src.getItems() == null) return out;
        for (WmsPendingOrderDTO.WmsItem it : src.getItems()) {
            if (it == null) continue;
            int qty = it.getShipQty() != null ? it.getShipQty() : (it.getOrderQty() != null ? it.getOrderQty() : 0);
            if (qty > 0) out.add(it);
        }
        return out;
    }

    private static void applyFirstItem(WmsPendingOrderDTO src, OrderImportRowDTO r) {
        List<WmsPendingOrderDTO.WmsItem> items = shippedItems(src);
        if (items.isEmpty()) return;
        applyItem(items.get(0), r);
    }

    private static void applyItem(WmsPendingOrderDTO.WmsItem it, OrderImportRowDTO r) {
        r.setItemSku(trimOrNull(it.getItemNo()));
        r.setItemDescription(trimOrNull(it.getItemDesc()));
        r.setItemQuantity(it.getShipQty() != null ? it.getShipQty() : it.getOrderQty());
    }

    /** Continuation rows for the 2nd..nth shipped item: same shipment, same parcel, own SKU line. */
    private List<OrderImportRowDTO> itemLines(WmsPendingOrderDTO src, OrderImportRowDTO leader, int firstRowNumber) {
        List<OrderImportRowDTO> out = new ArrayList<>();
        List<WmsPendingOrderDTO.WmsItem> items = shippedItems(src);
        for (int i = 1; i < items.size(); i++) {
            OrderImportRowDTO line = new OrderImportRowDTO();
            line.setRowNumber(firstRowNumber + out.size());
            line.setOrderRef(leader.getOrderRef());
            line.setClientCode(leader.getClientCode());
            line.setWarehouseCode(leader.getWarehouseCode());
            line.setRecipientName(leader.getRecipientName());
            line.setRecipientCompany(leader.getRecipientCompany());
            line.setRecipientPhone(leader.getRecipientPhone());
            line.setRecipientEmail(leader.getRecipientEmail());
            line.setAddressLine1(leader.getAddressLine1());
            line.setAddressLine2(leader.getAddressLine2());
            line.setCity(leader.getCity());
            line.setState(leader.getState());
            line.setPostalCode(leader.getPostalCode());
            line.setCountryCode(leader.getCountryCode());
            line.setCarrierCode(leader.getCarrierCode());
            line.setAccountNumber(leader.getAccountNumber());
            line.setServiceType(leader.getServiceType());
            line.setWeight(leader.getWeight());
            line.setWeightUnit(leader.getWeightUnit());
            line.setWeightInherited(Boolean.TRUE);
            line.setReference(leader.getReference());
            applyItem(items.get(i), line);
            out.add(line);
        }
        return out;
    }

    /** Errors the carrier would certainly raise — surfaced now so the row isn't shown as Ready. */
    private List<String> preflight(WmsPendingOrderDTO src, OrderImportRowDTO row) {
        List<String> errors = new ArrayList<>();
        if (shippingConfigService != null && StringUtils.hasText(row.getCarrierCode())
                && !StringUtils.hasText(row.getServiceType())) {
            errors.add("serviceType could not be resolved from WMS ship-via '"
                    + (StringUtils.hasText(src.getShipVia()) ? src.getShipVia().trim() : "(blank)")
                    + "' — pick a " + row.getCarrierCode() + " service, or map the code under Shipping Service Mapping");
        }
        return errors;
    }

    private static void addWarning(OrderImportRowDTO row, String warning) {
        List<String> w = new ArrayList<>(row.getWarnings() == null ? List.of() : row.getWarnings());
        w.add(warning);
        row.setWarnings(w);
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

    /** Keep a phone as its dialable digits (WMS sends "14697017960" and "310.259.0546"-style strings). */
    private static String digitsOrNull(String v) {
        if (!StringUtils.hasText(v)) return null;
        String digits = v.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? v.trim() : digits;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a.trim();
        return StringUtils.hasText(b) ? b.trim() : null;
    }
}
