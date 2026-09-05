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
    /** Sprint 52 PR δ — carrier-native shipment validation dispatch.
     *  Resolves the connector for the picked carrier then calls its
     *  validateShipment (default: delegates to validateAddress; MVP
     *  behaviour for all 4 carriers, follow-up PR δ.1 overrides FedEx +
     *  UPS with native validate endpoints). */
    private final CarrierService carrierService;
    private final com.multiship.backend.repository.CarrierAccountRefRepository carrierAccountRefRepository;
    /** Strict-fill sources for intl customs currency + incoterms.
     *  Currency precedence: operator → CarrierAccountRef → Client.defaultCurrency → error.
     *  Incoterms precedence: operator → ClientCustomsProfile.incoterms → error.
     *  No hardcoded USD/DAP defaults. */
    private final com.multiship.backend.repository.ClientRepository clientRepository;
    private final com.multiship.backend.repository.ClientCustomsProfileRepository clientCustomsProfileRepository;
    /** FX conversion for the intl-value threshold rules. Non-USD customs
     *  declarations get their totals converted to USD before comparing
     *  against the $2,500 statutory threshold. Rate-feed outages fall
     *  through as "rule doesn't fire" — safer than blocking on a broken
     *  feed. See docs/flyway-fresh-db-guard-pattern.md for parallel
     *  precedent on infra-outage-safe defaults. */
    private final com.multiship.backend.service.fx.FxRateService fxRateService;

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
            // Strict per-commodity + currency/incoterms pre-checks. No
            // fabricated fallbacks — every missing REQUIRED field surfaces
            // as a local error before the carrier hop.
            checkIntlRequiredFields(req, recipientCountry, errors);
            for (IntlShipmentValidator.ValidationError ve : IntlShipmentValidator.validate(adapted, fxRateService)) {
                errors.add(ValidationIssue.builder()
                        .code(ve.code()).message(ve.message()).build());
            }
            // Generic high-value export-declaration advisory. Fires as a
            // WARNING (not error) — some corridors legitimately have no
            // reference (intra-EU, US→CA §30.36), so we advise but don't
            // block. Per-corridor hard rules land in a follow-up PR.
            checkHighValueExportDeclaration(req, warnings);
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

        // ─── Sprint 52 PR δ — carrier-native shipment validation ───────────
        // Per user's "always call both" scope pick — carrier is invoked
        // even when local flagged errors. Rationale: carrier catches
        // orthogonal issues (ZIP-in-wrong-state, account contract
        // limits, lane availability) the local pass doesn't. Aggregated
        // response carries both sub-results so operator sees everything
        // fixable in one pass.
        //
        // MVP: default validateShipment on CarrierConnector delegates to
        // validateAddress with the recipient block — every carrier gets
        // free address-level truth. Follow-up PR δ.1 overrides in
        // FedEx + UPS with native validate endpoints.
        ShipmentValidationResult.CarrierValidationSubResult carrierResult =
                callCarrierValidateShipment(req, adapted, skipped);
        // PR #542 Fix 1 — suppress positive carrier verdict when local
        // validators flagged hard errors. Prior behavior: local says
        // "Recipient name is required" but carrier hop still shows
        // "EXACT: FedEx confirmed the shipment is valid." (because the
        // sandbox /packages/validate endpoint is lenient on null
        // personName). Operator sees conflicting banners and ignores
        // the local red error. Fix: when local errors exist AND
        // carrier returned a positive verdict (EXACT/CORRECTED),
        // downgrade to NOT_SUPPORTED (renders grey on the FE, not
        // green) with a bypass message. Negative carrier verdicts
        // (NOT_FOUND/ERROR) are informative and stay untouched.
        if (carrierResult != null && !errors.isEmpty()
                && ("EXACT".equals(carrierResult.getMatchLevel())
                    || "CORRECTED".equals(carrierResult.getMatchLevel()))) {
            carrierResult = carrierResult.toBuilder()
                    .valid(false)
                    .matchLevel("NOT_SUPPORTED")
                    .message("Carrier check bypassed — resolve local errors first.")
                    .warnings(List.of())
                    .errors(List.of())
                    .build();
        }
        if (carrierResult != null && Boolean.FALSE.equals(carrierResult.isValid())) {
            // Carrier flagged a hard failure — surface as a top-level
            // localError so the aggregated verdict flips to FAIL. The
            // detailed carrier sub-result stays on .carrier for the FE
            // banner's secondary section.
            if ("NOT_FOUND".equals(carrierResult.getMatchLevel())
                    || "ERROR".equals(carrierResult.getMatchLevel())) {
                errors.add(ValidationIssue.builder()
                        .code(ErrorCode.VALIDATION_ERROR.name())
                        .message(carrierResult.getCarrierCode() + ": " + carrierResult.getMessage())
                        .field(null)
                        .build());
            }
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
        return ok(buildResult(errors, warnings, skipped, carrierResult, international));
    }

    /**
     * Sprint 52 PR δ — resolve credentials + call the connector's
     * validateShipment. Mirrors {@link AddressValidationServiceImpl}'s
     * resolution pattern (client's default account first, platform
     * fallback). Never throws — connector failures come back as a
     * subresult with matchLevel=ERROR / NOT_SUPPORTED so the operator
     * still gets local pre-flight results.
     */
    private ShipmentValidationResult.CarrierValidationSubResult callCarrierValidateShipment(
            ManualShipmentRequest req,
            ShipmentRequestDTO adaptedRequest,
            List<ValidationCheckStatus> skipped) {
        String carrierCode = req.getCarrierCode();
        if (!StringUtils.hasText(carrierCode)) {
            skipped.add(check("carrier_validate_shipment", "no carrierCode picked"));
            return null;
        }
        String carrier = carrierCode.trim().toUpperCase(Locale.ROOT);

        com.multiship.backend.service.carriers.CarrierConnector connector;
        try {
            connector = carrierService.getCarrierConnector(carrier);
        } catch (Exception ex) {
            skipped.add(check("carrier_validate_shipment",
                    "carrier " + carrier + " not configured on this instance"));
            return null;
        }

        // Credential resolution — mirror AddressValidationServiceImpl:
        // client-default first, then any client-owned active account,
        // then platform fallback.
        com.multiship.backend.model.CarrierAccountRef account = resolveAccount(carrier, req.getClientCode());
        if (account == null || !StringUtils.hasText(account.getClientId())
                || !StringUtils.hasText(account.getClientSecret())) {
            skipped.add(check("carrier_validate_shipment",
                    "no live " + carrier + " credentials — cannot call carrier validate"));
            return null;
        }

        // PR #534 — resolve the same inputs generateManualLabel does
        // (service, preset, defaults) and hand them to the shared
        // CarrierService.buildManualShipmentRequestDto so the DTO the
        // native validate hop sends is byte-for-byte identical to the
        // one Generate Label sends. Prior to this the adapted DTO only
        // had recipient + accountNumber + intl block, so FedEx
        // rejected with 5+ NotNull errors on shipper phone / city /
        // country / serviceType / packagingType.
        com.multiship.backend.model.ShippingService service = req.getServiceId() != null
                ? shippingServiceRepository.findById(req.getServiceId()).orElse(null)
                : null;
        com.multiship.backend.model.PackagePreset preset = req.getPackagePresetId() != null
                ? packagePresetRepository.findById(req.getPackagePresetId()).orElse(null)
                : null;
        String serviceType = service != null ? service.getServiceCode()
                : blankTo(connector.getConfiguration().defaultServiceType(), "GROUND");
        String packageType = preset != null
                ? ("CARRIER".equalsIgnoreCase(preset.getKind()) ? preset.getCarrierPackageCode() : "YOUR_PACKAGING")
                : blankTo(connector.getConfiguration().defaultPackageType(), "YOUR_PACKAGING");
        java.math.BigDecimal length = req.getLength() != null ? req.getLength()
                : (preset != null ? preset.getLength() : null);
        java.math.BigDecimal width = req.getWidth() != null ? req.getWidth()
                : (preset != null ? preset.getWidth() : null);
        java.math.BigDecimal height = req.getHeight() != null ? req.getHeight()
                : (preset != null ? preset.getHeight() : null);
        String fromCountry = req.getSender() != null && StringUtils.hasText(req.getSender().getCountryCode())
                ? req.getSender().getCountryCode()
                : null;
        // Rebuild adaptedRequest — replace the sparse builder-shell
        // from adaptForValidators with the full-population DTO.
        adaptedRequest = carrierService.buildManualShipmentRequestDto(
                req,
                req.getSender(),
                req.getRecipient(),
                carrier,
                account.getAccountNumber(),
                serviceType,
                packageType,
                length, width, height,
                fromCountry,
                account,
                // PR #543 — validate hop has no persisted orderNo (label
                // hasn't been generated). Passing null lets the builder
                // fall through to req.getReference() for the PO slot.
                null);
        // adaptForValidators pre-populated intl + DG on the shell DTO;
        // the new builder also sets DG but not the customs-block DTO.
        // Re-attach the intl block so IntlShipmentValidator's earlier
        // pass (via localErrors) can share state with any downstream
        // logic that reads adaptedRequest.getIntl().
        if (req.getRecipient() != null && StringUtils.hasText(req.getRecipient().getCountryCode())) {
            // Reuse the same intl block adaptForValidators computed —
            // avoids duplicating the commodities mapping.
            String senderCountry = req.getSender() != null ? req.getSender().getCountryCode() : null;
            String recipientCountry = req.getRecipient().getCountryCode();
            com.multiship.backend.dto.ShipmentRequestDTO withIntl = adaptForValidators(
                    req, isInternational(senderCountry, recipientCountry));
            adaptedRequest.setIntl(withIntl.getIntl());
        }

        String accessToken;
        try {
            accessToken = connector.getAccessToken(
                    account.getClientId(),
                    account.getClientSecret(),
                    account.getAccountNumber(),
                    account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Shipment validation — {} token acquisition failed: {}", carrier, ex.getMessage());
            return ShipmentValidationResult.CarrierValidationSubResult.builder()
                    .carrierCode(carrier)
                    .valid(false)
                    .matchLevel("ERROR")
                    .kind("ADDRESS_ONLY")
                    .warnings(List.of())
                    .errors(List.of("Token acquisition failed: " + ex.getMessage()))
                    .message(carrier + " token acquisition failed")
                    .build();
        }

        com.multiship.backend.service.carriers.CarrierConnector.ValidateShipmentResult result;
        try {
            result = connector.validateShipment(adaptedRequest, accessToken, account.getEnvironment());
        } catch (Exception ex) {
            log.warn("Shipment validation — {} validateShipment failed: {}", carrier, ex.getMessage());
            return ShipmentValidationResult.CarrierValidationSubResult.builder()
                    .carrierCode(carrier)
                    .valid(false)
                    .matchLevel("ERROR")
                    .kind("ADDRESS_ONLY")
                    .warnings(List.of())
                    .errors(List.of(ex.getMessage()))
                    .message(carrier + " validateShipment call failed")
                    .build();
        }

        // PR #542 Fix 2 — defensive post-response check. FedEx sandbox's
        // /packages/validate endpoint accepts a payload with a null
        // recipient.contact.personName and returns EXACT (production
        // /shipments would reject with NotNull.verifyShipmentInputVO.
        // requestedShipment.recipients[0].contact.personName). Never
        // trust a positive carrier verdict on a payload where WE know
        // required wire fields are missing — override to ERROR with
        // an explicit "sandbox lenience" message so the operator
        // doesn't see conflicting local-FAIL / carrier-EXACT banners.
        String matchLevel = result.matchLevel();
        boolean valid = result.valid();
        String message = result.message();
        java.util.List<String> errorsList = result.errors() != null ? result.errors() : List.of();
        if (("EXACT".equals(matchLevel) || "CORRECTED".equals(matchLevel))) {
            java.util.List<String> missing = missingWireRequiredFields(adaptedRequest);
            if (!missing.isEmpty()) {
                matchLevel = "ERROR";
                valid = false;
                errorsList = java.util.List.of(
                        "Sandbox lenience: " + carrier
                                + " returned success on a payload missing required wire fields ("
                                + String.join(", ", missing)
                                + "). Production would reject.");
                message = carrier + " accepted a payload with missing required fields — sandbox lenience.";
            }
        }
        return ShipmentValidationResult.CarrierValidationSubResult.builder()
                .carrierCode(carrier)
                .valid(valid)
                .matchLevel(matchLevel)
                .kind(result.kind())
                .warnings(result.warnings() != null ? result.warnings() : List.of())
                .errors(errorsList)
                .message(message)
                .build();
    }

    /**
     * PR #542 — wire-level required-field check for the ShipmentRequestDTO
     * we hand to a connector. When a positive validate verdict lands
     * back on a payload with any of these blank, sandbox lenience
     * masked a real production-blocker; override to ERROR.
     */
    private java.util.List<String> missingWireRequiredFields(
            com.multiship.backend.dto.ShipmentRequestDTO dto) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!StringUtils.hasText(dto.getRecipientName())) missing.add("recipient.name");
        if (!StringUtils.hasText(dto.getRecipientAddressLine1())) missing.add("recipient.addressLine1");
        if (!StringUtils.hasText(dto.getRecipientCity())) missing.add("recipient.city");
        if (!StringUtils.hasText(dto.getRecipientPostalCode())) missing.add("recipient.postalCode");
        if (!StringUtils.hasText(dto.getRecipientCountryCode())) missing.add("recipient.countryCode");
        if (!StringUtils.hasText(dto.getShipperName())) missing.add("shipper.name");
        if (!StringUtils.hasText(dto.getShipperAddressLine1())) missing.add("shipper.addressLine1");
        if (!StringUtils.hasText(dto.getShipperCity())) missing.add("shipper.city");
        if (!StringUtils.hasText(dto.getShipperPostalCode())) missing.add("shipper.postalCode");
        if (!StringUtils.hasText(dto.getShipperCountryCode())) missing.add("shipper.countryCode");
        if (!StringUtils.hasText(dto.getServiceType())) missing.add("serviceType");
        if (!StringUtils.hasText(dto.getPackageType())) missing.add("packageType");
        return missing;
    }

    /** 3-tier credential resolution — mirrors AddressValidationServiceImpl. */
    private com.multiship.backend.model.CarrierAccountRef resolveAccount(String carrierCode, String customerNo) {
        if (StringUtils.hasText(customerNo)) {
            java.util.List<com.multiship.backend.model.CarrierAccountRef> ownedDefaults =
                    carrierAccountRefRepository.findByCustomerNoIgnoreCaseAndClientDefaultTrue(customerNo);
            for (com.multiship.backend.model.CarrierAccountRef ref : ownedDefaults) {
                if (matchesCarrierWithCreds(ref, carrierCode)) return ref;
            }
            java.util.List<com.multiship.backend.model.CarrierAccountRef> allOwned =
                    carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(customerNo);
            for (com.multiship.backend.model.CarrierAccountRef ref : allOwned) {
                if (Boolean.FALSE.equals(ref.getActive())) continue;
                if (matchesCarrierWithCreds(ref, carrierCode)) return ref;
            }
        }
        java.util.List<com.multiship.backend.model.CarrierAccountRef> platform =
                carrierAccountRefRepository.findPlatformAccountsByCarrier(carrierCode);
        return platform.isEmpty() ? null : platform.get(0);
    }

    private static boolean matchesCarrierWithCreds(com.multiship.backend.model.CarrierAccountRef ref, String carrierCode) {
        return carrierCode.equalsIgnoreCase(ref.getCarrierCode())
                && StringUtils.hasText(ref.getClientId())
                && StringUtils.hasText(ref.getClientSecret());
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

    /**
     * Strict per-commodity + resolvable currency/incoterms checks for
     * international shipments. Every missing REQUIRED field surfaces as
     * a local error so the operator gets the full punch-list on one
     * pass — no silent USD/DAP defaults, no declared-value spread, no
     * "0.10 kg" fabricated weight.
     *
     * <ul>
     *   <li>Per-commodity: {@code description}, {@code hsCode},
     *       {@code countryOfOrigin}, {@code quantity > 0},
     *       {@code unitValue > 0}, and a derivable
     *       {@code weight} (line-level OR pkg-weight spread).</li>
     *   <li>Currency: resolvable via
     *       {@link #resolveCustomsCurrency(ManualShipmentRequest)} —
     *       operator → CarrierAccountRef → Client.defaultCurrency.</li>
     *   <li>Incoterms: resolvable via
     *       {@link #resolveIncoterms(ManualShipmentRequest, String)} —
     *       operator → ClientCustomsProfile.incoterms.</li>
     * </ul>
     */
    private void checkIntlRequiredFields(ManualShipmentRequest req,
                                         String recipientCountry,
                                         List<ValidationIssue> errors) {
        // Currency + incoterms — resolvable via precedence, else error.
        if (resolveCustomsCurrency(req) == null) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Customs currency is required — pick one on the shipment, "
                            + "or set a default on the carrier account or client.",
                    "currency"));
        }
        if (resolveIncoterms(req, recipientCountry) == null) {
            errors.add(issue(ErrorCode.VALIDATION_ERROR,
                    "Incoterms is required for international shipments — pick one "
                            + "on the shipment, or set a default on the client's "
                            + "customs profile for " + recipientCountry + ".",
                    "incoterms"));
        }
        // Per-commodity strict — hsCode, countryOfOrigin, unitValue > 0,
        // derivable weight. Loop by index so error messages point at the
        // right row.
        java.util.List<ManualShipmentRequest.Item> items = req.getItems();
        if (items == null || items.isEmpty()) return; // IntlShipmentValidator flags commodities.empty
        boolean canSpreadWeight = req.getWeight() != null && req.getWeight().signum() > 0;
        for (int i = 0; i < items.size(); i++) {
            ManualShipmentRequest.Item it = items.get(i);
            if (it == null) continue;
            String rowLabel = "Item " + (i + 1);
            String fieldPrefix = "items[" + i + "].";
            if (!StringUtils.hasText(it.getDescription())) {
                // Description is enforced by IntlShipmentValidator but flag here too
                // so the operator sees the same row-numbered message the other
                // fields use — one banner, one punch-list.
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        rowLabel + ": description is required.",
                        fieldPrefix + "description"));
            }
            if (!StringUtils.hasText(it.getHsCode())) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        rowLabel + ": HS (harmonized) code is required for international shipments.",
                        fieldPrefix + "hsCode"));
            }
            if (!StringUtils.hasText(it.getCountryOfOrigin())) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        rowLabel + ": country of origin is required for international shipments.",
                        fieldPrefix + "countryOfOrigin"));
            }
            if (it.getUnitValue() == null || it.getUnitValue().signum() <= 0) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        rowLabel + ": unit value greater than zero is required.",
                        fieldPrefix + "unitValue"));
            }
            // Weight is derivable when the line has its own OR pkg weight
            // is set (spread across items by qty). If NEITHER is set the
            // line can't be filled — error at the row so the operator
            // knows exactly where to add weight.
            boolean lineHasOwnWeight = it.getWeight() != null && it.getWeight().signum() > 0;
            if (!lineHasOwnWeight && !canSpreadWeight) {
                errors.add(issue(ErrorCode.VALIDATION_ERROR,
                        rowLabel + ": weight is required — either fill it in on the row "
                                + "or set the shipment's package weight.",
                        fieldPrefix + "weight"));
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * PR #534 — local helper mirroring CarrierServiceImpl.firstNonBlank.
     * Kept as a private static so we don't reach across service
     * boundaries for a 3-line utility.
     */
    private static String blankTo(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

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

    /** Documented warning code the FE localises + the FE test asserts on. */
    static final String CODE_EXPORT_DECLARATION_RECOMMENDED = "customs.export.declaration.recommended";

    /**
     * Cross-corridor advisory — most origin countries require an export
     * declaration for shipments over the equivalent of USD $2,500 (US FTR
     * §30.37, CA B13A CAD $2,000, GB CDS £873, EU AES €1,000, AU EDN AUD
     * $2,000, JP ¥200,000). Rather than block, we WARN when a high-value
     * international shipment carries no reference at all — some corridors
     * legitimately need none (intra-EU, US→CA §30.36) so a hard block
     * would false-fire.
     *
     * <p>US-specific hard rule ({@code customs.eei.required}) still runs
     * upstream in {@link IntlShipmentValidator}; this fires only when
     * that hard rule doesn't (non-US origin, or US-origin with the
     * FTR/AES fields already populated but a value ≥ $2,500).
     *
     * <p>PR 2 (FX plumbing): non-USD declarations are now converted to
     * USD via {@link com.multiship.backend.service.fx.FxRateService}. Rate
     * lookup failures fall through as "rule doesn't fire" — same defensive
     * posture as the FTR rule; carrier still catches at its edge.
     */
    private void checkHighValueExportDeclaration(ManualShipmentRequest req,
                                                  List<ValidationIssue> warnings) {
        if (req == null) return;
        String currency = resolveCustomsCurrency(req);
        if (currency == null) return;
        java.math.BigDecimal total = req.getDeclaredValue();
        if (total == null) {
            // Fall back to sum of commodity line totals when declaredValue
            // wasn't set explicitly — mirrors what adaptForValidators does.
            total = buildValidatorCommodities(req).stream()
                    .map(com.multiship.backend.dto.CustomsCommodityDTO::lineTotalValue)
                    .filter(java.util.Objects::nonNull)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        }
        if (total == null || total.signum() <= 0) return;
        // FX-normalize to USD before comparing. USD passes through as-is;
        // other currencies via the injected FxRateService. Rate-feed
        // outages return empty → rule doesn't fire (safer than false-positive).
        java.math.BigDecimal usdEquivalent;
        if ("USD".equalsIgnoreCase(currency)) {
            usdEquivalent = total;
        } else {
            java.util.Optional<java.math.BigDecimal> converted = fxRateService == null
                    ? java.util.Optional.empty()
                    : fxRateService.convert(total, currency, "USD");
            if (converted.isEmpty()) return;
            usdEquivalent = converted.get();
        }
        if (usdEquivalent.compareTo(IntlShipmentValidator.EEI_THRESHOLD_USD) < 0) return;
        boolean anyRefPopulated = StringUtils.hasText(req.getFtrExemption())
                || StringUtils.hasText(req.getAesCitation())
                || StringUtils.hasText(req.getExportDeclarationReference());
        if (anyRefPopulated) return;
        warnings.add(ValidationIssue.builder()
                .code(CODE_EXPORT_DECLARATION_RECOMMENDED)
                .message("Most origin countries require an export declaration for "
                        + "international shipments over $"
                        + IntlShipmentValidator.EEI_THRESHOLD_USD.toPlainString()
                        + " USD (US FTR §30.37, CA B13A, GB CDS, EU AES, AU EDN, "
                        + "JP declaration, IN Shipping Bill). Provide the appropriate "
                        + "reference in the export declaration field, or verify none "
                        + "is required for this corridor.")
                .build());
    }

    /**
     * Minimal adapter — build just enough of ShipmentRequestDTO for
     * IntlShipmentValidator + DangerousGoodsValidator to run their
     * checks. Not the full CarrierServiceImpl.buildShipmentRequest —
     * that method has 20+ dependencies and is overkill for a pre-flight.
     */
    // package-private for direct unit-test coverage of the customs-total
    // + currency roll-up (see ShipmentValidationServiceTest).
    ShipmentRequestDTO adaptForValidators(ManualShipmentRequest req, boolean international) {
        IntlShipmentBlockDTO intl = null;
        if (international) {
            // IntlShipmentValidator short-circuits on intl.international != TRUE
            // (line 88), so the flag must be set even when commodities are
            // empty (so the validator surfaces the "no commodities" error).
            //
            // No fallbacks — the strict pre-checks in validate() already
            // flag missing hsCode/countryOfOrigin/unitValue/currency/
            // incoterms as local errors. This shell just mirrors the
            // operator's payload plus the pkg-weight spread (a
            // derivation, not a fallback — pkg weight is a required
            // shipment field, so spreading it across items is faithful
            // to what the operator entered).
            java.util.List<com.multiship.backend.dto.CustomsCommodityDTO> commodities =
                    buildValidatorCommodities(req);
            BigDecimal commoditySum = commodities.stream()
                    .map(com.multiship.backend.dto.CustomsCommodityDTO::lineTotalValue)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal customsTotal = commoditySum.signum() > 0 ? commoditySum : null;
            String recipientCountry = req.getRecipient() != null
                    ? normCountry(req.getRecipient().getCountryCode()) : null;
            String customsCurrency = resolveCustomsCurrency(req);
            String customsIncoterms = resolveIncoterms(req, recipientCountry);
            intl = IntlShipmentBlockDTO.builder()
                    .international(true)
                    .commodities(commodities)
                    .incoterms(customsIncoterms)
                    .reasonForExport(req.getReasonForExport())
                    .customsCurrency(customsCurrency)
                    .customsTotalValue(customsTotal)
                    .weightUnit(req.getWeightUnit())
                    .ftrExemption(req.getFtrExemption())
                    .aesCitation(req.getAesCitation())
                    .exportDeclarationReference(req.getExportDeclarationReference())
                    .build();
        }
        // Shipper/recipient country populated so IntlShipmentValidator can
        // apply US-origin export rules (§30.37 EEI) at pre-flight — without
        // this, the validator sees a null origin and can't gate the
        // $2,500-threshold rule.
        String shipperCountry = req.getSender() != null
                ? normCountry(req.getSender().getCountryCode()) : null;
        String recipientCountryCode = req.getRecipient() != null
                ? normCountry(req.getRecipient().getCountryCode()) : null;
        return ShipmentRequestDTO.builder()
                .declaredValueCurrency(req.getCurrency())
                .declaredValue(req.getDeclaredValue())
                .shipperCountryCode(shipperCountry)
                .recipientCountryCode(recipientCountryCode)
                .intl(intl)
                .dangerousGoods(req.getDangerousGoods())
                .build();
    }

    /**
     * Commodity shaping for the pre-flight intl block. STRICT — no
     * fabricated values:
     * <ul>
     *   <li>Per-line {@code unitValue} = operator's item.unitValue (no
     *       declared-value spread). Missing → null on the wire; local
     *       pre-checks flag as {@code customs.unitValue.missing}.</li>
     *   <li>Per-line {@code unitWeight} = operator's item.weight if set,
     *       otherwise the shipment's pkgWeight × qty / totalQty share
     *       (a derivation from a required field, not a fallback). No
     *       "0.10" last-resort — missing pkg weight → null on the wire;
     *       local pre-checks flag as {@code customs.weight.missing}.</li>
     * </ul>
     */
    // package-private for direct unit-test coverage of the weight-spread math.
    static java.util.List<com.multiship.backend.dto.CustomsCommodityDTO>
            buildValidatorCommodities(ManualShipmentRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) return java.util.List.of();
        int totalQty = req.getItems().stream()
                .mapToInt(it -> it.getQuantity() != null ? Math.max(it.getQuantity(), 1) : 1).sum();
        java.math.RoundingMode HU = java.math.RoundingMode.HALF_UP;
        BigDecimal pkgWeight = req.getWeight();
        boolean canSpread = pkgWeight != null && pkgWeight.signum() > 0 && totalQty > 0;
        return req.getItems().stream()
                .map(it -> {
                    int qty = it.getQuantity() != null ? Math.max(it.getQuantity(), 1) : 1;
                    BigDecimal lineWeight;
                    if (it.getWeight() != null && it.getWeight().signum() > 0) {
                        lineWeight = it.getWeight();
                    } else if (canSpread) {
                        lineWeight = pkgWeight.multiply(BigDecimal.valueOf(qty))
                                .divide(BigDecimal.valueOf(totalQty), 3, HU);
                    } else {
                        lineWeight = null;
                    }
                    return com.multiship.backend.dto.CustomsCommodityDTO.builder()
                            .description(it.getDescription())
                            .hsCode(it.getHsCode())
                            .countryOfOrigin(it.getCountryOfOrigin())
                            .quantity(qty)
                            .unitValue(it.getUnitValue())
                            .unitWeight(lineWeight)
                            .sku(it.getSku())
                            .boxSeq(it.getBoxSeq())
                            .build();
                })
                .toList();
    }

    /**
     * Currency precedence: operator → CarrierAccountRef.currency →
     * Client.defaultCurrency → null. Customs profile is intentionally
     * skipped (user decision — carrier billing currency governs intl
     * customs, not the client-scoped customs profile).
     */
    String resolveCustomsCurrency(ManualShipmentRequest req) {
        if (StringUtils.hasText(req.getCurrency())) {
            return req.getCurrency().trim().toUpperCase(Locale.ROOT);
        }
        if (StringUtils.hasText(req.getCarrierCode()) && StringUtils.hasText(req.getAccountNumber())) {
            com.multiship.backend.model.CarrierAccountRef account = resolveAccount(
                    req.getCarrierCode().trim().toUpperCase(Locale.ROOT), req.getClientCode());
            if (account != null && StringUtils.hasText(account.getCurrency())) {
                return account.getCurrency().trim().toUpperCase(Locale.ROOT);
            }
        }
        if (StringUtils.hasText(req.getClientCode())) {
            java.util.Optional<com.multiship.backend.model.Client> client =
                    clientRepository.findByClientCodeIgnoreCase(req.getClientCode());
            if (client.isPresent() && StringUtils.hasText(client.get().getDefaultCurrency())) {
                return client.get().getDefaultCurrency().trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Incoterms precedence: operator → ClientCustomsProfile.incoterms
     * (looked up by clientCode + recipient country) → null.
     */
    String resolveIncoterms(ManualShipmentRequest req, String recipientCountry) {
        if (StringUtils.hasText(req.getIncoterms())) {
            return req.getIncoterms().trim().toUpperCase(Locale.ROOT);
        }
        if (StringUtils.hasText(req.getClientCode()) && StringUtils.hasText(recipientCountry)) {
            java.util.Optional<com.multiship.backend.model.ClientCustomsProfile> profile =
                    clientCustomsProfileRepository.findByClientAndCountry(
                            req.getClientCode(), recipientCountry);
            if (profile.isPresent() && StringUtils.hasText(profile.get().getIncoterms())) {
                return profile.get().getIncoterms().trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
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
                                                  ShipmentValidationResult.CarrierValidationSubResult carrier,
                                                  boolean international) {
        String overall;
        String message;
        if (!errors.isEmpty()) {
            overall = "FAIL";
            message = errors.size() == 1
                    ? errors.get(0).getMessage()
                    : errors.size() + " issues must be fixed before this shipment can be generated.";
        } else if (!warnings.isEmpty()
                || (carrier != null && ("CORRECTED".equals(carrier.getMatchLevel())
                        || "AMBIGUOUS".equals(carrier.getMatchLevel())))) {
            overall = "WARN";
            message = carrier != null && "CORRECTED".equals(carrier.getMatchLevel())
                    ? carrier.getCarrierCode() + " suggests a corrected address."
                    : warnings.size() == 1
                        ? "Ready to ship — review 1 suggestion."
                        : "Ready to ship — review " + warnings.size() + " suggestions.";
        } else {
            overall = "PASS";
            message = carrier != null && carrier.isValid()
                    ? "Local + " + carrier.getCarrierCode() + " checks passed. Ready to generate the label."
                    : "All server-side checks passed. Ready to generate the label.";
        }

        return ShipmentValidationResult.builder()
                .overall(overall)
                .message(message)
                .localErrors(errors)
                .localWarnings(warnings)
                .skipped(skipped)
                .address(null) // Sprint 52 PR δ — deprecated; carrier subresult moved to .carrier
                .carrier(carrier)
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
