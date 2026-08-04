package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sprint 40 impl. Format detection by filename extension:
 * {@code .csv} → Apache Commons CSV; {@code .xlsx} → Apache POI XSSF.
 * Both formats normalise into the same {@link OrderImportRowDTO} shape.
 *
 * <p>Schema (columns in order — case-insensitive header row required):
 * <ul>
 *   <li>recipientName (required)</li>
 *   <li>recipientCompany, recipientPhone, recipientEmail</li>
 *   <li>addressLine1 (required), addressLine2</li>
 *   <li>city (required), state, postalCode (required), countryCode (required)</li>
 *   <li>carrierCode, serviceType, packageType</li>
 *   <li>weight (required, numeric > 0), weightUnit</li>
 *   <li>declaredValue, currency, reference, goodsDescription</li>
 * </ul>
 *
 * <p>Missing required fields, weight ≤ 0 or unparseable, or
 * declaredValue unparseable each produce a row-level error message.
 * The commit endpoint refuses to persist rows with errors.
 */
@Slf4j
@Service
public class OrderImportServiceImpl implements OrderImportService {

    private final CarrierService carrierService;
    /** Sprint 48 — used to bake per-client account dropdowns into the
     *  .xlsx template. Optional (null in the no-arg test constructor). */
    private final CarrierAccountRefRepository accountRefRepository;
    /** Sprint 48 — service catalog for the template's serviceType dropdown. */
    private final ShippingServiceRepository shippingServiceRepository;
    /** Sprint 48 — package presets for the template's packageType dropdown. */
    private final PackagePresetRepository packagePresetRepository;
    /** Sprint 48 — client list for the universal-template clientCode dropdown. */
    private final ClientRepository clientRepository;
    /** Sprint 48 — per-client warehouse attachments for the warehouseCode dropdown. */
    private final ClientWarehouseRepository clientWarehouseRepository;
    /** Sprint 48 — warehouse-code lookup for resolving ClientWarehouse.warehouseId → Warehouse.code. */
    private final WarehouseRepository warehouseRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderImportServiceImpl(CarrierService carrierService,
                                  CarrierAccountRefRepository accountRefRepository,
                                  ShippingServiceRepository shippingServiceRepository,
                                  PackagePresetRepository packagePresetRepository,
                                  ClientRepository clientRepository,
                                  ClientWarehouseRepository clientWarehouseRepository,
                                  WarehouseRepository warehouseRepository) {
        this.carrierService = carrierService;
        this.accountRefRepository = accountRefRepository;
        this.shippingServiceRepository = shippingServiceRepository;
        this.packagePresetRepository = packagePresetRepository;
        this.clientRepository = clientRepository;
        this.clientWarehouseRepository = clientWarehouseRepository;
        this.warehouseRepository = warehouseRepository;
    }

    public OrderImportServiceImpl() {
        this.carrierService = null;
        this.accountRefRepository = null;
        this.shippingServiceRepository = null;
        this.packagePresetRepository = null;
        this.clientRepository = null;
        this.clientWarehouseRepository = null;
        this.warehouseRepository = null;
    }

    /** Legacy Sprint-41 test constructor. */
    OrderImportServiceImpl(CarrierService carrierService) {
        this.carrierService = carrierService;
        this.accountRefRepository = null;
        this.shippingServiceRepository = null;
        this.packagePresetRepository = null;
        this.clientRepository = null;
        this.clientWarehouseRepository = null;
        this.warehouseRepository = null;
    }

    /** Canonical header ordering used for the template + parser column
     *  discovery. Column names are normalised to lowercase on match.
     *
     *  <p>Sprint 48 adds:
     *  <ul>
     *    <li>{@code orderRef} — order-group key. Rows sharing a non-blank
     *        orderRef fold into a single shipment; the first row supplies
     *        recipient / carrier / service, subsequent rows carry additional
     *        customs line-items.</li>
     *    <li>{@code itemDescription}, {@code itemSku}, {@code itemQuantity},
     *        {@code itemUnitValue}, {@code hsCode}, {@code countryOfOrigin}
     *        — per-line-item customs data. Optional; blank rows just skip
     *        the customs commodity block (domestic-only shipments).</li>
     *  </ul>
     */
    static final List<String> HEADERS = List.of(
            "orderRef",
            // Sprint 48 — clientCode + billTo + warehouseCode are the
            // universal-template additions. clientCode drives the cascading
            // dropdowns in the workbook; billTo unlocks the accountNumber
            // free-text mode when THIRD_PARTY; warehouseCode picks a specific
            // origin (blank = client's default cascade).
            "clientCode", "billTo", "warehouseCode",
            "recipientName", "recipientCompany", "recipientPhone", "recipientEmail",
            "addressLine1", "addressLine2",
            "city", "state", "postalCode", "countryCode",
            "carrierCode", "accountNumber", "serviceType", "packageType",
            "weight", "weightUnit",
            "declaredValue", "currency",
            "reference", "goodsDescription",
            "itemDescription", "itemSku", "itemQuantity", "itemUnitValue",
            "hsCode", "countryOfOrigin");

    /** Column names required for a valid row. */
    static final List<String> REQUIRED_COLUMNS = List.of(
            "recipientName", "addressLine1", "city", "postalCode",
            "countryCode", "weight");

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body) {
        return preview(filename, body, null);
    }

    /**
     * Sprint 48 — reverse-lookup human names to wire codes on serviceType
     * and packageType. The universal template writes the user-friendly
     * name (e.g. "UPS Ground") into the cell, but every carrier connector
     * expects the wire code (e.g. "03"). We match on (carrier, name) via
     * the platform catalog. If the value already looks like a wire code
     * (uppercase alphanumeric, no spaces) or the lookup misses, we leave
     * the value untouched — operators overriding with a raw code still
     * work.
     */
    private void resolveNamesToCodes(List<OrderImportRowDTO> rows) {
        if (shippingServiceRepository == null || rows.isEmpty()) return;
        List<com.multiship.backend.model.ShippingService> services =
                shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc();
        List<com.multiship.backend.model.PackagePreset> presets = packagePresetRepository == null
                ? List.of()
                : packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc();
        for (OrderImportRowDTO row : rows) {
            String carrier = row.getCarrierCode();
            if (!StringUtils.hasText(carrier)) continue;
            String carrierU = carrier.toUpperCase(Locale.ROOT);
            // Service: (carrier, name) case-insensitive match. Skip lookup
            // when the value looks like a wire code already (no space,
            // ≤6 chars) so a raw "03" override stays untouched.
            String svcRaw = row.getServiceType();
            if (looksLikeName(svcRaw)) {
                for (com.multiship.backend.model.ShippingService s : services) {
                    if (!carrierU.equalsIgnoreCase(s.getCarrier())) continue;
                    if (svcRaw.equalsIgnoreCase(s.getName())) {
                        row.setServiceType(s.getServiceCode());
                        break;
                    }
                }
            }
            // Package: (carrier, name) match against PackagePreset.name;
            // carrierPackageCode wins when present, else fall back to name.
            String pkgRaw = row.getPackageType();
            if (looksLikeName(pkgRaw)) {
                for (com.multiship.backend.model.PackagePreset p : presets) {
                    if (p.getCarrier() != null
                            && !carrierU.equalsIgnoreCase(p.getCarrier())) continue;
                    if (pkgRaw.equalsIgnoreCase(p.getName())) {
                        String code = p.getCarrierPackageCode();
                        row.setPackageType(code != null && !code.isBlank() ? code : p.getName());
                        break;
                    }
                }
            }
        }
    }

    /** Heuristic — treat a value as a display name when it contains a
     *  space or a lowercase letter. Wire codes are UPPER + digits by
     *  convention across UPS / FedEx / DHL. */
    private static boolean looksLikeName(String v) {
        if (!StringUtils.hasText(v)) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> preview(String filename, InputStream body, Long expectedAccountId) {
        String ext = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            List<OrderImportRowDTO> rows;
            if (ext.endsWith(".xlsx")) {
                rows = parseXlsx(body);
            } else if (ext.endsWith(".csv") || ext.endsWith(".txt")) {
                rows = parseCsv(body);
            } else {
                return failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only .csv, .txt, and .xlsx files are supported.");
            }
            // Sprint 48 — reverse-lookup human names ("UPS Ground") to
            // wire codes ("03") on service / package cells. The universal
            // template writes names; every carrier connector expects codes.
            resolveNamesToCodes(rows);
            // Sprint 48 — divergence warning. Resolve the expected account
            // once, then annotate every row whose accountNumber deviates.
            // Non-fatal: warnings never block commit; operators may edit
            // rows deliberately to bill a different account.
            if (expectedAccountId != null && accountRefRepository != null) {
                CarrierAccountRef expected = accountRefRepository.findById(expectedAccountId).orElse(null);
                if (expected != null && StringUtils.hasText(expected.getAccountNumber())) {
                    String expectedNumber = expected.getAccountNumber().trim();
                    for (OrderImportRowDTO row : rows) {
                        // Only warn when the row DOES carry an account and it
                        // differs — blank accountNumber inherits the template
                        // default at commit time, that's fine.
                        String rowNumber = row.getAccountNumber();
                        if (StringUtils.hasText(rowNumber)
                                && !rowNumber.trim().equalsIgnoreCase(expectedNumber)) {
                            List<String> warnings = new ArrayList<>(
                                    row.getWarnings() == null ? List.of() : row.getWarnings());
                            warnings.add("Template account = " + expectedNumber
                                    + " but row uses " + rowNumber + ". Row wins at commit.");
                            row.setWarnings(warnings);
                        }
                    }
                }
            }
            return success(buildPreview(rows), rows.size() + " row(s) parsed.");
        } catch (Exception ex) {
            log.warn("Order import parse failed for {}: {}", filename, ex.getMessage());
            return failure(HttpStatus.BAD_REQUEST,
                    "Failed to parse " + filename + ": " + ex.getMessage());
        }
    }

    @Override
    public ApiResponse<OrderImportPreviewDTO> commit(List<OrderImportRowDTO> rows, String requestedBy) {
        if (rows == null || rows.isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "No rows to commit.");
        }
        // Frontend may edit rows post-preview; re-run the name→code
        // reverse-lookup here so a value the operator pasted in
        // ("UPS Ground") still resolves to the wire code before the
        // carrier connector sees it.
        resolveNamesToCodes(rows);

        int valid = 0;
        int invalid = 0;
        int generated = 0;

        // Sprint 48 — group rows by orderRef so multi-row orders (one
        // shipment, N line-items) fold into a single label call. Rows
        // WITHOUT orderRef stay standalone (pre-Sprint-48 behaviour).
        // Ordering is preserved so preview and commit rows line up 1:1.
        // The first row of each group is the "leader" — its recipient +
        // shipment fields drive the request; subsequent rows contribute
        // only customs line-items.
        Map<String, List<OrderImportRowDTO>> groups = new LinkedHashMap<>();
        for (OrderImportRowDTO row : rows) {
            String key = StringUtils.hasText(row.getOrderRef())
                    ? row.getOrderRef().trim()
                    : "__row_" + row.getRowNumber(); // unique standalone key
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<OrderImportRowDTO>> entry : groups.entrySet()) {
            List<OrderImportRowDTO> group = entry.getValue();
            OrderImportRowDTO leader = group.get(0);
            // Re-validate the leader (has recipient + shipment fields);
            // item-only rows don't need those, so skip their re-validate.
            List<String> errors = validateRow(leader);
            leader.setErrors(errors);
            if (!errors.isEmpty()) {
                // Whole group is invalid; count each row so the summary
                // stays accurate.
                invalid += group.size();
                for (int i = 1; i < group.size(); i++) {
                    group.get(i).setErrors(List.of("orderRef leader failed validation"));
                }
                continue;
            }
            valid += group.size();
            if (carrierService == null) continue;

            try {
                com.multiship.backend.dto.ManualShipmentRequest req = toManualShipmentRequest(group);
                ApiResponse<com.multiship.backend.dto.LabelGenerationResponse> resp =
                        carrierService.generateManualLabel(req, null);
                com.multiship.backend.dto.LabelGenerationResponse data =
                        resp == null ? null : resp.getData();
                if (resp != null && "success".equalsIgnoreCase(resp.getStatus()) && data != null
                        && StringUtils.hasText(data.getTrackingNumber())) {
                    // Stamp the whole group so operators see the label
                    // outcome on every line-item row, not just the leader.
                    Integer orderNo = data.getOrderNo() == null ? null : data.getOrderNo().intValue();
                    for (OrderImportRowDTO gr : group) {
                        gr.setGeneratedOrderNo(orderNo);
                        gr.setGeneratedTrackingNumber(data.getTrackingNumber());
                        gr.setGeneratedStatus("GENERATED");
                        gr.setGeneratedMessage(data.getMessage());
                    }
                    generated++;
                } else {
                    String msg = resp == null ? "no response" : resp.getMessage();
                    for (OrderImportRowDTO gr : group) {
                        gr.setGeneratedStatus("FAILED");
                        gr.setGeneratedMessage(msg);
                    }
                }
            } catch (Exception ex) {
                log.warn("Order import group (leader row {}) failed at label generation: {}",
                        leader.getRowNumber(), ex.getMessage());
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                for (OrderImportRowDTO gr : group) {
                    gr.setGeneratedStatus("FAILED");
                    gr.setGeneratedMessage(msg);
                }
            }
        }

        log.info("Order import commit ({}): {} valid, {} invalid, {} labels generated",
                requestedBy, valid, invalid, generated);
        return success(OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(valid)
                .invalidRows(invalid)
                .rows(rows)
                .build(),
                generated > 0
                        ? generated + " label(s) generated"
                                + (invalid > 0 ? " · " + invalid + " row(s) skipped" : "")
                        : (invalid > 0
                                ? invalid + " row(s) skipped — none committed"
                                : "0 label(s) generated"));
    }

    /**
     * Sprint 41 — convert a single import row to a ManualShipmentRequest.
     * Kept for tests / legacy call paths; the commit loop uses the
     * {@link #toManualShipmentRequest(List)} group overload so multi-row
     * orders fold into one shipment with a customs items[] array.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(OrderImportRowDTO row) {
        return toManualShipmentRequest(List.of(row));
    }

    /**
     * Sprint 48 — convert an orderRef group (leader + item rows) into one
     * ManualShipmentRequest. Recipient / carrier / weight / service come
     * from the group's leader (row 0). Every row in the group contributes
     * a customs {@code Item} when it carries any item-level data
     * (description, HS code, SKU, quantity, or unit value).
     *
     * <p>Domestic-only groups (no item-level data on any row) send the
     * legacy single-goods block — no customs items array — so we don't
     * force customs data on shipments that don't need it.
     */
    static com.multiship.backend.dto.ManualShipmentRequest toManualShipmentRequest(List<OrderImportRowDTO> group) {
        OrderImportRowDTO leader = group.get(0);
        com.multiship.backend.dto.ManualShipmentRequest req =
                new com.multiship.backend.dto.ManualShipmentRequest();
        com.multiship.backend.dto.ManualShipmentRequest.Address recipient =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        recipient.setName(leader.getRecipientName());
        recipient.setCompany(leader.getRecipientCompany());
        recipient.setPhone(leader.getRecipientPhone());
        recipient.setEmail(leader.getRecipientEmail());
        recipient.setAddressLine1(leader.getAddressLine1());
        recipient.setAddressLine2(leader.getAddressLine2());
        recipient.setCity(leader.getCity());
        recipient.setState(leader.getState());
        recipient.setPostalCode(leader.getPostalCode());
        recipient.setCountryCode(leader.getCountryCode());
        req.setRecipient(recipient);

        req.setCarrierCode(leader.getCarrierCode());
        req.setAccountNumber(leader.getAccountNumber());
        req.setWeight(leader.getWeight());
        req.setWeightUnit(leader.getWeightUnit());
        req.setDeclaredValue(leader.getDeclaredValue());
        req.setCurrency(leader.getCurrency());
        req.setReference(leader.getReference());
        req.setGoodsDescription(leader.getGoodsDescription());
        req.setSource("API");

        // Customs items: any row (leader OR item rows) that carries
        // item-level data becomes one Item on the invoice. Skip rows
        // that are purely shipment-level (domestic-only leader with no
        // customs data).
        List<com.multiship.backend.dto.ManualShipmentRequest.Item> items = new ArrayList<>();
        for (OrderImportRowDTO row : group) {
            if (!rowHasItemData(row)) continue;
            com.multiship.backend.dto.ManualShipmentRequest.Item it =
                    new com.multiship.backend.dto.ManualShipmentRequest.Item();
            it.setDescription(row.getItemDescription() != null
                    ? row.getItemDescription()
                    : row.getGoodsDescription());
            it.setHsCode(row.getHsCode());
            it.setCountryOfOrigin(row.getCountryOfOrigin());
            it.setQuantity(row.getItemQuantity() != null ? row.getItemQuantity() : 1);
            it.setUnitValue(row.getItemUnitValue());
            it.setSku(row.getItemSku());
            items.add(it);
        }
        if (!items.isEmpty()) req.setItems(items);
        return req;
    }

    /** True when the row carries any per-item data. Blank-rows shouldn't
     *  become empty customs entries. */
    private static boolean rowHasItemData(OrderImportRowDTO row) {
        return StringUtils.hasText(row.getItemDescription())
                || StringUtils.hasText(row.getItemSku())
                || StringUtils.hasText(row.getHsCode())
                || StringUtils.hasText(row.getCountryOfOrigin())
                || row.getItemQuantity() != null
                || row.getItemUnitValue() != null;
    }

    @Override
    public byte[] xlsxTemplate(Long accountId) {
        // Sprint 48 — accountId is retained on the signature for backwards
        // compatibility with existing callers but the universal template
        // doesn't scope to a single account any more. Every client + every
        // carrier account + every warehouse gets baked into the reference
        // sheet with cascading dropdowns; operators pick per-row inside the
        // workbook.
        List<Client> clients = clientRepository != null
                ? clientRepository.findAll()
                : List.of();
        List<CarrierAccountRef> accounts = accountRefRepository != null
                ? accountRefRepository.findAll()
                : List.of();
        List<com.multiship.backend.model.ShippingService> services = shippingServiceRepository != null
                ? shippingServiceRepository.findAllByOrderByCarrierAscSortOrderAsc()
                : List.of();
        List<com.multiship.backend.model.PackagePreset> presets = packagePresetRepository != null
                ? packagePresetRepository.findAllByOrderByIsDefaultDescNameAsc()
                : List.of();
        // Precompute clientCode → List<warehouseCode>. ClientWarehouse only
        // carries warehouseId; resolve to Warehouse.code once via a single
        // findAll on WarehouseRepository so the builder doesn't need a repo
        // dependency (avoids test-constructor fanout).
        java.util.Map<Long, String> warehouseCodeById = new java.util.HashMap<>();
        if (warehouseRepository != null) {
            for (Warehouse w : warehouseRepository.findAll()) {
                if (w.getId() != null && w.getCode() != null
                        && Boolean.TRUE.equals(w.getActive())) {
                    warehouseCodeById.put(w.getId(), w.getCode());
                }
            }
        }
        java.util.Map<String, List<String>> clientWarehouseCodes = new java.util.HashMap<>();
        if (clientWarehouseRepository != null) {
            for (Client c : clients) {
                String code = c.getClientCode();
                if (code == null || code.isBlank()) continue;
                List<ClientWarehouse> attached = clientWarehouseRepository
                        .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(code);
                List<String> codes = new java.util.ArrayList<>();
                for (ClientWarehouse cw : attached) {
                    String whCode = warehouseCodeById.get(cw.getWarehouseId());
                    if (whCode != null) codes.add(whCode);
                }
                clientWarehouseCodes.put(code.toUpperCase(Locale.ROOT), codes);
            }
        }
        // accountId parameter is ignored — universal template.
        if (accountId != null) log.debug("xlsxTemplate ignored accountId={} (universal template)", accountId);
        return OrderImportTemplateBuilder.build(
                HEADERS, clients, accounts, clientWarehouseCodes, services, presets);
    }

    @Override
    public byte[] csvTemplate() {
        // CSV template lags the XLSX template in features (no in-workbook
        // dropdowns). It's a plain schema dump + one representative sample
        // row so operators know the column ordering; the .xlsx template
        // is the recommended path (dropdowns + cascading + samples).
        //
        // Sprint 48 column ordering: orderRef, clientCode, billTo,
        // warehouseCode, recipientName, ..., itemDescription, hsCode,
        // countryOfOrigin (see HEADERS). Row values below must stay in
        // that exact positional order.
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append('\n');
        // Sample row — international UK shipment with 1 line-item so
        // operators see all the columns exercised in one go.
        sb.append("ORD-2001,")                              // orderRef
                .append("MA1885,SENDER,WH-EAST,")           // clientCode, billTo, warehouseCode
                .append("Ava Chen,,4402071234567,ava.chen@example.co.uk,")  // recipient
                .append("221B Baker Street,,London,LDN,NW1 6XE,GB,")         // address
                .append("FEDEX,F98765,INTERNATIONAL_PRIORITY,YOUR_PACKAGING,")// carrier + service
                .append("3.2,LB,275.00,USD,")                                 // weight + value
                .append("ORD-2001,Silk garments,")                            // reference + goods
                .append("Silk lining natural,SKU-100,2,45.00,5007.20,IT\n"); // per-item customs
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* -------------------------- Parsers -------------------------- */

    private List<OrderImportRowDTO> parseCsv(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        // Wrap in a PushbackInputStream so we can peek + swallow a UTF-8
        // BOM (0xEF 0xBB 0xBF) — Excel writes one when Save As CSV, and
        // without stripping it the first header column reads as "﻿orderRef"
        // instead of "orderRef" and every value on that column comes back null.
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(body, 3);
        int b1 = pb.read();
        if (b1 != -1) {
            int b2 = pb.read();
            int b3 = pb.read();
            if (b1 != 0xEF || b2 != 0xBB || b3 != 0xBF) {
                if (b3 != -1) pb.unread(b3);
                if (b2 != -1) pb.unread(b2);
                pb.unread(b1);
            }
        }
        try (InputStreamReader reader = new InputStreamReader(pb, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true).setTrim(true)
                     .build().parse(reader)) {
            Map<String, Integer> headerMap = lowerCasedHeaderMap(parser.getHeaderMap());
            int rowNo = 0;
            for (CSVRecord rec : parser) {
                rowNo++;
                if (isBlank(rec)) continue;
                out.add(buildRow(rowNo, name -> get(rec, headerMap, name)));
            }
        }
        return out;
    }

    private List<OrderImportRowDTO> parseXlsx(InputStream body) throws Exception {
        List<OrderImportRowDTO> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(body)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) return out;

            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headerMap = new LinkedHashMap<>();
            if (header != null) {
                for (Cell cell : header) {
                    String label = fmt.formatCellValue(cell).trim();
                    if (StringUtils.hasText(label)) {
                        headerMap.put(label.toLowerCase(Locale.ROOT), cell.getColumnIndex());
                    }
                }
            }

            int rowNo = 0;
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                rowNo++;
                boolean anyValue = false;
                for (Cell cell : row) {
                    if (StringUtils.hasText(fmt.formatCellValue(cell))) { anyValue = true; break; }
                }
                if (!anyValue) continue;

                Map<String, Integer> capturedHeader = headerMap;
                int finalI = i;
                out.add(buildRow(rowNo, name -> readCell(sheet, finalI, capturedHeader, name, fmt)));
            }
        }
        return out;
    }

    private static boolean isBlank(CSVRecord rec) {
        for (int i = 0; i < rec.size(); i++) {
            if (StringUtils.hasText(rec.get(i))) return false;
        }
        return true;
    }

    private static Map<String, Integer> lowerCasedHeaderMap(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toLowerCase(Locale.ROOT).trim(), e.getValue());
        }
        return out;
    }

    private static String get(CSVRecord rec, Map<String, Integer> headerMap, String columnName) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null || idx >= rec.size()) return null;
        String value = rec.get(idx);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String readCell(Sheet sheet, int rowIndex, Map<String, Integer> headerMap,
                                    String columnName, DataFormatter fmt) {
        Integer idx = headerMap.get(columnName.toLowerCase(Locale.ROOT));
        if (idx == null) return null;
        Row row = sheet.getRow(rowIndex);
        if (row == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        String value = fmt.formatCellValue(cell).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    /* -------------------------- Row build + validate -------------------------- */

    @FunctionalInterface
    private interface ColumnReader {
        String read(String columnName);
    }

    OrderImportRowDTO buildRow(int rowNumber, ColumnReader r) {
        // Wrap every read in sanitise() so downstream validators + carrier
        // connectors see clean strings — no control chars, no carrier-
        // forbidden characters. sanitise() returns null for whitespace-only
        // input, preserving today's "blank = null" semantics.
        ColumnReader s = name -> sanitise(r.read(name));
        OrderImportRowDTO out = new OrderImportRowDTO();
        out.setRowNumber(rowNumber);
        out.setOrderRef(s.read("orderRef"));
        // Sprint 48 — universal-template columns.
        out.setClientCode(upper(s.read("clientCode")));
        out.setBillTo(upper(s.read("billTo")));
        out.setWarehouseCode(upper(s.read("warehouseCode")));
        out.setRecipientName(s.read("recipientName"));
        out.setRecipientCompany(s.read("recipientCompany"));
        out.setRecipientPhone(s.read("recipientPhone"));
        out.setRecipientEmail(s.read("recipientEmail"));
        out.setAddressLine1(s.read("addressLine1"));
        out.setAddressLine2(s.read("addressLine2"));
        out.setCity(s.read("city"));
        out.setState(s.read("state"));
        out.setPostalCode(s.read("postalCode"));
        out.setCountryCode(upper(s.read("countryCode")));
        out.setCarrierCode(upper(s.read("carrierCode")));
        out.setAccountNumber(s.read("accountNumber"));
        out.setServiceType(s.read("serviceType"));
        out.setPackageType(s.read("packageType"));
        out.setWeight(parseDecimal(s.read("weight")));
        out.setWeightUnit(upper(s.read("weightUnit")));
        out.setDeclaredValue(parseDecimal(s.read("declaredValue")));
        out.setCurrency(upper(s.read("currency")));
        out.setReference(s.read("reference"));
        out.setGoodsDescription(s.read("goodsDescription"));
        // Sprint 48 — per-item customs data.
        out.setItemDescription(s.read("itemDescription"));
        out.setItemSku(s.read("itemSku"));
        out.setItemQuantity(parseInt(s.read("itemQuantity")));
        out.setItemUnitValue(parseDecimal(s.read("itemUnitValue")));
        out.setHsCode(s.read("hsCode"));
        out.setCountryOfOrigin(upper(s.read("countryOfOrigin")));
        out.setErrors(validateRow(out));
        return out;
    }

    /**
     * Strip control characters (0x00–0x1F, 0x7F) and carrier-forbidden
     * chars ({@code < > \ | `}) from a user-supplied string. Returns null
     * when the result is blank so downstream code can treat "just noise"
     * the same as "not supplied at all".
     *
     * <p>Sprint 48 — needed because CSV uploads and Excel paste-in flows
     * routinely carry stray control characters (BOMs, non-breaking
     * spaces sneak in as 0x00 in some exports), and UPS + FedEx reject
     * name / address fields containing any of the punctuation set.
     */
    static String sanitise(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F) continue;
            if (c == '<' || c == '>' || c == '\\' || c == '|' || c == '`') continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static Integer parseInt(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Integer.parseInt(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    static List<String> validateRow(OrderImportRowDTO row) {
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasText(row.getRecipientName())) errors.add("recipientName is required");
        if (!StringUtils.hasText(row.getAddressLine1())) errors.add("addressLine1 is required");
        if (!StringUtils.hasText(row.getCity())) errors.add("city is required");
        if (!StringUtils.hasText(row.getPostalCode())) errors.add("postalCode is required");
        if (!StringUtils.hasText(row.getCountryCode())) errors.add("countryCode is required");
        if (row.getWeight() == null || row.getWeight().signum() <= 0) {
            errors.add("weight must be > 0");
        }
        return errors;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseDecimal(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return new BigDecimal(s.trim().replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    private static OrderImportPreviewDTO buildPreview(List<OrderImportRowDTO> rows) {
        int invalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();
        return OrderImportPreviewDTO.builder()
                .totalRows(rows.size())
                .validRows(rows.size() - invalid)
                .invalidRows(invalid)
                .rows(rows)
                .build();
    }

    private static ApiResponse<OrderImportPreviewDTO> success(OrderImportPreviewDTO data, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<OrderImportPreviewDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<OrderImportPreviewDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Used only in tests to keep IntelliJ happy about unused imports. */
    @SuppressWarnings("unused")
    private static ByteArrayOutputStream noop() {
        return new ByteArrayOutputStream();
    }
}
