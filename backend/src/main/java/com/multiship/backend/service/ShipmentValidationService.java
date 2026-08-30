package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.dto.ShipmentValidationResult;
import com.multiship.backend.dto.ShipmentValidationResult.ValidationCheckStatus;
import com.multiship.backend.dto.ShipmentValidationResult.ValidationIssue;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.resolution.PackagingCompatibilityGuard;
import com.multiship.backend.service.resolution.ShipmentResolutionException;
import com.multiship.backend.service.resolution.ShipmentResolutionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sprint 52 — server-side pre-flight for a manual shipment. Powers the
 * FE "Validate shipment" button (formerly "Validate with Carrier",
 * which sent only recipient address fields). Runs the same guards that
 * {@link CarrierServiceImpl#generateManualLabel} runs, without calling
 * the carrier's createShipment.
 *
 * <p>No carrier calls are made. This is strictly a local-only pre-flight
 * — every field that would block label generation surfaces as an error
 * here first, so the operator sees the problem before clicking Generate.
 * Fields that don't block but are recommended (e.g. state code missing
 * for US/CA/AU addresses) surface as warnings.
 *
 * <p>Return contract: never throws — the shape is
 * {@link ShipmentValidationResult} with structured errors + warnings +
 * skipped-checks list. HTTP response is always 200 unless the payload
 * itself is malformed; validation failures are IN the body.
 */
@Service
@RequiredArgsConstructor
public class ShipmentValidationService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentValidationService.class);

    /** US and its outlying territories — treated as one territory per
     *  the "US → PR is domestic" UX rule (Sprint 52 shipment-validation
     *  design pick). Mirrors the FE sameTerritory helper. */
    private static final Set<String> US_FAMILY = Set.of("US", "PR", "VI", "GU", "AS", "MP");

    /** Same EU set the FE uses to treat intra-EU shipments as domestic. */
    private static final Set<String> EU_FAMILY = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    /** Carriers where the destination country requires a state code —
     *  most carriers reject the label call without it even though our
     *  DTO leaves state optional. Sprint 51 polish shipped a similar
     *  client-side warning; this brings the check server-side too. */
    private static final Set<String> STATE_REQUIRED_COUNTRIES =
            Set.of("US", "CA", "AU", "MX", "BR");

    private final PackagingCompatibilityGuard packagingCompatibilityGuard;
    private final ShipmentResolutionService resolutionService;
    private final ShippingServiceRepository shippingServiceRepository;
    private final PackagePresetRepository packagePresetRepository;

    @Transactional(readOnly = true)
    public ApiResponse<ShipmentValidationResult> validate(ManualShipmentRequest req) {
        if (req == null || req.getRecipient() == null) {
            return failure("Request body and recipient are required.");
        }

        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        List<ValidationCheckStatus> skipped = new ArrayList<>();

        ManualShipmentRequest.Address to = req.getRecipient();
        ManualShipmentRequest.Address from = req.getSender();
        String clientCode = req.getClientCode();
        boolean hasClient = StringUtils.hasText(clientCode);

        // ─── Required-field checks — every missing field errors ────────────
        checkRecipientRequired(to, errors, warnings);
        checkSenderRequired(from, req, errors);
        checkShipmentRequired(req, errors);

        // ─── Sprint 52 PR β — format checks (postal + state per country).
        // Fast-fail on typos like "Delaware" instead of "DE" or ZIP
        // "abcde" before any downstream check runs. Runs even when
        // presence checks flagged errors — one call covers both blocks
        // so the operator sees every fixable format issue in a single
        // pass. Countries without rules (see AddressFormatValidator's
        // per-country maps) fall through as unvalidated.
        errors.addAll(AddressFormatValidator.validate(
                to.getCountryCode(), to.getPostalCode(), to.getState(), "recipient"));
        if (from != null) {
            errors.addAll(AddressFormatValidator.validate(
                    from.getCountryCode(), from.getPostalCode(), from.getState(), "sender"));
        }

        String senderCountry = from != null ? normCountry(from.getCountryCode()) : null;
        String recipientCountry = normCountry(to.getCountryCode());
        boolean international = isInternational(senderCountry, recipientCountry);

        // ─── Ship-to allowlist gate (clientCode-scoped) ─────────────────────
        if (hasClient) {
            try {
                resolutionService.assertShipToAllowed(clientCode, to.getCountryCode());
            } catch (ShipmentResolutionException e) {
                errors.add(issue(e.getErrorCode(), e.getMessage(), "recipient.countryCode"));
            }
        } else {
            skipped.add(check("ship_to_allowlist", "ad-hoc shipment (blank clientCode) — no allowlist to enforce"));
        }

        // ─── Service + preset resolution + allowlist + packaging compat ─────
        ShippingService service = req.getServiceId() != null
                ? shippingServiceRepository.findById(req.getServiceId()).orElse(null)
                : null;
        PackagePreset preset = req.getPackagePresetId() != null
                ? packagePresetRepository.findById(req.getPackagePresetId()).orElse(null)
                : null;
        if (req.getServiceId() != null && service == null) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Picked service (id=" + req.getServiceId() + ") does not exist in the catalog.",
                    "serviceId"));
        }
        if (req.getPackagePresetId() != null && preset == null) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Picked package preset (id=" + req.getPackagePresetId() + ") does not exist in the catalog.",
                    "packagePresetId"));
        }

        if (hasClient) {
            if (service != null) {
                try {
                    resolutionService.assertServiceAllowed(clientCode, service.getId(),
                            to.getCountryCode(), null);
                } catch (ShipmentResolutionException e) {
                    errors.add(issue(e.getErrorCode(), e.getMessage(), "serviceId"));
                }
            }
            if (preset != null) {
                try {
                    resolutionService.assertPackageAllowed(clientCode, preset.getId());
                } catch (ShipmentResolutionException e) {
                    errors.add(issue(e.getErrorCode(), e.getMessage(), "packagePresetId"));
                }
            }
        }

        if (service != null && preset != null) {
            try {
                packagingCompatibilityGuard.assertCompatible(service, preset);
            } catch (ShipmentResolutionException e) {
                errors.add(issue(e.getErrorCode(), e.getMessage(), "packagePresetId"));
            }
        } else {
            skipped.add(check("packaging_compatibility",
                    (service == null ? "no serviceId picked" : "no packagePresetId picked (custom dims)")));
        }

        // ─── Markup required (Sprint 50 Tier 1 finding #11 dry-run) ────────
        if (hasClient) {
            try {
                resolutionService.applyMarkup(clientCode, BigDecimal.ONE, "USD");
            } catch (ShipmentResolutionException e) {
                if (e.getErrorCode() == ErrorCode.MARKUP_REQUIRED_FOR_CLIENT) {
                    errors.add(issue(e.getErrorCode(), e.getMessage(), "clientCode"));
                } else if (e.getErrorCode() == ErrorCode.MARKUP_INVALID) {
                    // Currency mismatch — the probe uses USD; the real label
                    // call uses the actual carrier rate currency and may pass.
                    // Downgrade to a suggestion.
                    warnings.add(issue(e.getErrorCode(),
                            "Markup currency check skipped in pre-flight "
                                    + "(uses actual carrier rate currency at label time).",
                            "clientCode"));
                }
            }
        } else {
            skipped.add(check("markup", "ad-hoc shipment (blank clientCode) — no markup owner"));
        }

        // ─── Customs / international validation ─────────────────────────────
        ShipmentRequestDTO adapted = adaptForValidators(req, international);
        if (international) {
            for (IntlShipmentValidator.ValidationError ve : IntlShipmentValidator.validate(adapted)) {
                errors.add(ValidationIssue.builder()
                        .code(ve.code()).message(ve.message()).build());
            }
        } else {
            skipped.add(check("customs", "domestic shipment (sender/recipient in same territory)"));
        }

        // ─── Dangerous goods ────────────────────────────────────────────────
        if (req.getDangerousGoods() != null) {
            for (IntlShipmentValidator.ValidationError ve : DangerousGoodsValidator.validate(adapted)) {
                errors.add(ValidationIssue.builder()
                        .code(ve.code()).message(ve.message()).build());
            }
        } else {
            skipped.add(check("dangerous_goods", "no DG block on the request"));
        }

        // Sprint 52 diagnostic — one INFO line per validate call so future
        // "validate said PASS but shouldn't have" reports can be triaged
        // from log alone. Captures the exact inputs the guard saw + the
        // membership answer service_package returned. Verbose on purpose:
        // shipment payloads are typically small and this log is high-signal
        // on the exact class of bug the operator complained about.
        boolean existsInSvcPkg = (service != null && preset != null)
                && packagingCompatibilityGuard.isCompatible(service, preset);
        String overallForLog;
        if (!errors.isEmpty()) overallForLog = "FAIL";
        else if (!warnings.isEmpty()) overallForLog = "WARN";
        else overallForLog = "PASS";
        log.info(
                "ShipmentValidation: verdict={}, service.id={}, service.code={}, "
                        + "preset.id={}, preset.kind={}, preset.code={}, "
                        + "existsInServicePackage={}, brandedAllowed={}, "
                        + "clientCode={}, intl={}, errors={}, warnings={}, skipped={}",
                overallForLog,
                req.getServiceId(),
                service != null ? service.getServiceCode() : null,
                req.getPackagePresetId(),
                preset != null ? preset.getKind() : null,
                preset != null ? preset.getCarrierPackageCode() : null,
                existsInSvcPkg,
                service != null ? service.isBrandedPackagingAllowed() : null,
                clientCode,
                international,
                errors.size(),
                warnings.size(),
                skipped.stream().map(ValidationCheckStatus::getName).toList()
        );
        return ok(buildResult(errors, warnings, skipped, international));
    }

    // ─── Field checks (comprehensive — every missing field errors) ──────────

    private void checkRecipientRequired(ManualShipmentRequest.Address to,
                                         List<ValidationIssue> errors,
                                         List<ValidationIssue> warnings) {
        if (!StringUtils.hasText(to.getName())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Recipient name is required.", "recipient.name"));
        }
        if (!StringUtils.hasText(to.getAddressLine1())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Recipient address line 1 is required.", "recipient.addressLine1"));
        }
        if (!StringUtils.hasText(to.getCity())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Recipient city is required.", "recipient.city"));
        }
        if (!StringUtils.hasText(to.getPostalCode())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Recipient postal code is required.", "recipient.postalCode"));
        }
        if (!StringUtils.hasText(to.getCountryCode())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Recipient country is required.", "recipient.countryCode"));
            return; // country-conditional checks below need this to be non-blank
        }
        String country = normCountry(to.getCountryCode());
        // State — required by most carriers for US / CA / AU / MX / BR.
        // The DTO allows blank so it's an ERROR here (would cause a wire
        // rejection at label time otherwise).
        if (STATE_REQUIRED_COUNTRIES.contains(country) && !StringUtils.hasText(to.getState())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Recipient state/province is required for " + country + " addresses.",
                    "recipient.state"));
        }
        // Phone — most carriers require a recipient phone for delivery
        // exceptions. Downgraded to a warning because some domestic ground
        // services accept a blank phone; the label call will surface the
        // hard error if the specific service requires it.
        if (!StringUtils.hasText(to.getPhone())) {
            warnings.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Recipient phone is recommended — most carriers require it for delivery exceptions.",
                    "recipient.phone"));
        }
    }

    private void checkSenderRequired(ManualShipmentRequest.Address from,
                                      ManualShipmentRequest req,
                                      List<ValidationIssue> errors) {
        // Sender may be null when a warehouseCode is supplied — the label
        // pipeline resolves the warehouse address into the sender block at
        // build time. Pre-flight can't do the resolution itself (avoids
        // an extra dep), so accept null-sender only when warehouseCode is
        // set; otherwise every field errors like the recipient block.
        if (from == null) {
            if (!StringUtils.hasText(req.getWarehouseCode())) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        "Sender address is required. Either fill it in, or pick a warehouse to source it from.",
                        "sender"));
            }
            return;
        }
        if (!StringUtils.hasText(from.getName())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Sender name is required.", "sender.name"));
        }
        if (!StringUtils.hasText(from.getAddressLine1())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Sender address line 1 is required.", "sender.addressLine1"));
        }
        if (!StringUtils.hasText(from.getCity())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Sender city is required.", "sender.city"));
        }
        if (!StringUtils.hasText(from.getPostalCode())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Sender postal code is required.", "sender.postalCode"));
        }
        if (!StringUtils.hasText(from.getCountryCode())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Sender country is required.", "sender.countryCode"));
        } else {
            String country = normCountry(from.getCountryCode());
            if (STATE_REQUIRED_COUNTRIES.contains(country) && !StringUtils.hasText(from.getState())) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        "Sender state/province is required for " + country + " addresses.",
                        "sender.state"));
            }
        }
    }

    private void checkShipmentRequired(ManualShipmentRequest req, List<ValidationIssue> errors) {
        if (!StringUtils.hasText(req.getCarrierCode())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR, "Carrier is required.", "carrierCode"));
        }
        if (req.getAccountId() == null && !StringUtils.hasText(req.getAccountNumber())) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Bill-to account is required — either pick an account or enter its number.",
                    "accountNumber"));
        }
        if (req.getServiceId() == null) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Shipping service is required.", "serviceId"));
        }
        // Package: either a preset OR full custom dimensions (all three).
        if (req.getPackagePresetId() == null) {
            List<String> missingDims = new ArrayList<>();
            if (req.getLength() == null || req.getLength().signum() <= 0) missingDims.add("length");
            if (req.getWidth() == null || req.getWidth().signum() <= 0) missingDims.add("width");
            if (req.getHeight() == null || req.getHeight().signum() <= 0) missingDims.add("height");
            if (!missingDims.isEmpty()) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        "Pick a package preset OR fill in custom dimensions. Missing: "
                                + String.join(", ", missingDims) + ".",
                        "packagePresetId"));
            }
        }
        if (req.getWeight() == null || req.getWeight().signum() <= 0) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "A shipment weight greater than zero is required.", "weight"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private boolean isInternational(String sender, String recipient) {
        if (sender == null || recipient == null) return false;
        if (sender.equals(recipient)) return false;
        if (US_FAMILY.contains(sender) && US_FAMILY.contains(recipient)) return false;
        if (EU_FAMILY.contains(sender) && EU_FAMILY.contains(recipient)) return false;
        return true;
    }

    private String normCountry(String c) {
        return c == null ? null : c.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Minimal adapter — build just enough of ShipmentRequestDTO for
     * IntlShipmentValidator + DangerousGoodsValidator to run their
     * checks. Not the full CarrierServiceImpl.buildShipmentRequest —
     * that method has 20+ dependencies and is overkill for a pre-flight.
     */
    private ShipmentRequestDTO adaptForValidators(ManualShipmentRequest req, boolean international) {
        IntlShipmentBlockDTO intl = null;
        if (international) {
            // IntlShipmentValidator short-circuits on intl.international != TRUE
            // (line 88), so the flag must be set even when commodities are
            // empty (so the validator surfaces the "no commodities" error).
            java.util.List<com.multiship.backend.dto.CustomsCommodityDTO> commodities = req.getItems() == null
                    ? java.util.List.of()
                    : req.getItems().stream()
                            .map(it -> com.multiship.backend.dto.CustomsCommodityDTO.builder()
                                    .description(it.getDescription())
                                    .hsCode(it.getHsCode())
                                    .countryOfOrigin(it.getCountryOfOrigin())
                                    .quantity(it.getQuantity())
                                    .unitValue(it.getUnitValue())
                                    .build())
                            .toList();
            intl = IntlShipmentBlockDTO.builder()
                    .international(true)
                    .commodities(commodities)
                    .incoterms(req.getIncoterms())
                    .reasonForExport(req.getReasonForExport())
                    .customsCurrency(req.getCurrency())
                    .customsTotalValue(req.getDeclaredValue())
                    .build();
        }
        return ShipmentRequestDTO.builder()
                .declaredValueCurrency(req.getCurrency())
                .intl(intl)
                .dangerousGoods(req.getDangerousGoods())
                .build();
    }

    private ValidationIssue issue(ErrorCode code, String message, String field) {
        return ValidationIssue.builder()
                .code(code != null ? code.name() : null)
                .message(message)
                .field(field)
                .build();
    }

    private ValidationCheckStatus check(String name, String reason) {
        return ValidationCheckStatus.builder().name(name).reason(reason).build();
    }

    private ShipmentValidationResult buildResult(List<ValidationIssue> errors,
                                                  List<ValidationIssue> warnings,
                                                  List<ValidationCheckStatus> skipped,
                                                  boolean international) {
        String overall;
        String message;
        if (!errors.isEmpty()) {
            overall = "FAIL";
            message = errors.size() == 1
                    ? errors.get(0).getMessage()
                    : errors.size() + " issues must be fixed before this shipment can be generated.";
        } else if (!warnings.isEmpty()) {
            overall = "WARN";
            message = warnings.size() == 1
                    ? "Ready to ship — review 1 suggestion."
                    : "Ready to ship — review " + warnings.size() + " suggestions.";
        } else {
            overall = "PASS";
            message = "All server-side checks passed. Ready to generate the label.";
        }

        return ShipmentValidationResult.builder()
                .overall(overall)
                .message(message)
                .localErrors(errors)
                .localWarnings(warnings)
                .skipped(skipped)
                .address(null) // no carrier calls in this pre-flight; field kept for future
                .international(international)
                .build();
    }

    private ApiResponse<ShipmentValidationResult> failure(String message) {
        return ApiResponse.<ShipmentValidationResult>builder()
                .status("error")
                .code(HttpStatus.BAD_REQUEST.value())
                .message(message)
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .build();
    }

    private ApiResponse<ShipmentValidationResult> ok(ShipmentValidationResult data) {
        return ApiResponse.<ShipmentValidationResult>builder()
                .status("success")
                .code(HttpStatus.OK.value())
                .message(data.getMessage())
                .data(data)
                .build();
    }
}
