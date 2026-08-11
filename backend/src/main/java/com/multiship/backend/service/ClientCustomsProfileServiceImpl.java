package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.dto.CustomsProfileFilters;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
import com.multiship.backend.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientCustomsProfileServiceImpl implements ClientCustomsProfileService {

    private final ClientCustomsProfileRepository repository;
    private final ClientRepository clientRepository;

    /**
     * Sprint 50 Tier 0.5 PR H - clamp {@link #listPaginated},
     * {@link #getStats} and {@link #exportProfilesCsv} so a scoped USER only
     * sees their own client's importer/broker data. Optional so pre-Sprint-50
     * unit tests still compile.
     */
    @Autowired(required = false)
    private TenantScopeEnforcer tenantScope;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientCustomsProfileDTO>> listAll() {
        Map<String, String> names = clientRepository.findAll().stream()
                .collect(Collectors.toMap(
                        c -> c.getClientCode().toUpperCase(Locale.ROOT),
                        com.multiship.backend.model.Client::getName, (a, b) -> a));
        List<ClientCustomsProfileDTO> all = repository.findAll().stream()
                .sorted(Comparator.comparing(ClientCustomsProfile::getClientCode)
                        .thenComparing(p -> firstCountry(p)))
                .map(p -> {
                    ClientCustomsProfileDTO dto = toDTO(p);
                    dto.setClientName(names.get(p.getClientCode().toUpperCase(Locale.ROOT)));
                    return dto;
                })
                .toList();
        return success("Customs profiles retrieved.", all);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PageResponseDTO<ClientCustomsProfileDTO>> listPaginated(CustomsProfileFilters filters) {
        // Sprint 50 Tier 0.5 PR H - a scoped USER only ever sees their own
        // client's profiles. Clamp filters.clientCode: foreign → 403, own
        // stays, unset → forced to their tenant.
        clampTenantFilter(filters);
        List<ClientCustomsProfileDTO> filtered = fetchFiltered(filters);
        int page = Math.max(filters.getPage(), 0);
        int size = Math.min(Math.max(filters.getSize(), 1), 200);
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<ClientCustomsProfileDTO> slice = filtered.subList(from, to);
        return success("Customs profiles retrieved.", PageResponseDTO.of(slice, page, size, filtered.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Long>> getStats() {
        // Sprint 49 Tier 3 Fix 5 — the page's health strip called findAll()
        // and derived three totals in Java. Now three lightweight count
        // queries run at the DB and return scalars. Response identical.
        // Sprint 50 Tier 0.5 PR H — a scoped USER sees only their tenant's
        // slice; clientsConfigured collapses to 1 (their own has ≥1 profile)
        // or 0 (they've configured none). Platform operators unchanged.
        Optional<String> scope = tenantScope == null ? Optional.empty() : tenantScope.resolveScope();
        Map<String, Long> out = new LinkedHashMap<>();
        if (scope.isPresent()) {
            String code = scope.get();
            List<ClientCustomsProfile> mine = repository.findByClientCodeIgnoreCase(code);
            out.put("profiles", (long) mine.size());
            Set<String> destinations = new java.util.HashSet<>();
            for (ClientCustomsProfile p : mine) {
                for (String c : p.getCountries()) {
                    if (c != null && !c.isBlank()) destinations.add(c.toUpperCase(Locale.ROOT));
                }
            }
            out.put("destinationsCovered", (long) destinations.size());
            out.put("clientsConfigured", mine.isEmpty() ? 0L : 1L);
        } else {
            out.put("profiles", repository.count());
            out.put("destinationsCovered", repository.countDistinctCountries());
            out.put("clientsConfigured", repository.countDistinctClientCodes());
        }
        return success("Customs profile stats.", out);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportProfilesCsv(CustomsProfileFilters filters) {
        // Sprint 50 Tier 0.5 PR H — same clamp as listPaginated so a scoped
        // USER's CSV export never leaks foreign tenants' rows.
        clampTenantFilter(filters);
        List<ClientCustomsProfileDTO> profiles = fetchFiltered(filters);
        StringBuilder csv = new StringBuilder(96 + profiles.size() * 128);
        csv.append("Client code,Client name,Countries,Importer type,Importer,Importer city,Broker,Broker city,Account carrier,Account no,Incoterms,Currency\r\n");
        for (ClientCustomsProfileDTO p : profiles) {
            String countries = p.getCountries() == null ? "" : String.join(";", p.getCountries());
            csv.append(csvCell(p.getClientCode())).append(',')
               .append(csvCell(p.getClientName())).append(',')
               .append(csvCell(countries)).append(',')
               .append(csvCell(p.getImporterType())).append(',')
               .append(csvCell(p.getImporterName())).append(',')
               .append(csvCell(p.getImporterCity())).append(',')
               .append(csvCell(nvl(p.getBrokerName(), p.getBrokerCompany()))).append(',')
               .append(csvCell(p.getBrokerCity())).append(',')
               .append(csvCell(p.getAccountCarrier())).append(',')
               .append(csvCell(p.getAccountNo())).append(',')
               .append(csvCell(p.getIncoterms())).append(',')
               .append(csvCell(p.getCurrency())).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * In-memory apply-all-filters over every profile. Small dataset (typically
     * fewer than a few hundred rows across the whole system), so the simplicity
     * of a single fetch beats bespoke SQL. Sort + slice happen after.
     *
     * <p>TODO(sprint49-tier3-fix5-followup): once the profile count crosses
     * a few thousand this becomes a scale wall. Migrate to a
     * {@code JpaSpecificationExecutor<ClientCustomsProfile>} + Specifications
     * per filter (clientCode, carrier, broker, countries, search) so
     * pagination is a real DB LIMIT + OFFSET rather than a Java sublist over
     * a full result set. Frontend already sends the filters in the shape
     * needed.
     */
    private List<ClientCustomsProfileDTO> fetchFiltered(CustomsProfileFilters filters) {
        Map<String, String> clientNames = clientRepository.findAll().stream()
                .collect(Collectors.toMap(
                        c -> c.getClientCode().toUpperCase(Locale.ROOT),
                        com.multiship.backend.model.Client::getName, (a, b) -> a));

        String search = norm(filters.getSearch());
        String clientCode = norm(filters.getClientCode());
        String carrier = norm(filters.getCarrier());
        String broker = norm(filters.getBroker());
        Set<String> countries = filters.getCountries() == null
                ? Set.of()
                : filters.getCountries().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());

        return repository.findAll().stream()
                .filter(p -> matchesClient(p, clientCode))
                .filter(p -> matchesCarrier(p, carrier))
                .filter(p -> matchesBroker(p, broker))
                .filter(p -> matchesCountries(p, countries))
                .filter(p -> matchesSearch(p, clientNames, search))
                .map(p -> {
                    ClientCustomsProfileDTO dto = toDTO(p);
                    dto.setClientName(clientNames.get(
                            p.getClientCode() == null ? "" : p.getClientCode().toUpperCase(Locale.ROOT)));
                    return dto;
                })
                .sorted(profileComparator(filters.getSortBy(), filters.getSortDirection()))
                .toList();
    }

    private boolean matchesClient(ClientCustomsProfile p, String clientCode) {
        if (clientCode.isEmpty()) return true;
        return p.getClientCode() != null && p.getClientCode().equalsIgnoreCase(clientCode);
    }

    private boolean matchesCarrier(ClientCustomsProfile p, String carrier) {
        if (carrier.isEmpty()) return true;
        return p.getAccountCarrier() != null && p.getAccountCarrier().equalsIgnoreCase(carrier);
    }

    private boolean matchesBroker(ClientCustomsProfile p, String broker) {
        if (broker.isEmpty()) return true;
        boolean has = (p.getBrokerName() != null && !p.getBrokerName().isBlank())
                || (p.getBrokerCompany() != null && !p.getBrokerCompany().isBlank());
        return "YES".equals(broker) ? has : !has;
    }

    private boolean matchesCountries(ClientCustomsProfile p, Set<String> countries) {
        if (countries.isEmpty()) return true;
        for (com.multiship.backend.model.CustomsProfileCountry link : p.getCountryLinks()) {
            if (link.getCountry() != null && countries.contains(link.getCountry().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean matchesSearch(ClientCustomsProfile p, Map<String, String> clientNames, String search) {
        if (search.isEmpty()) return true;
        String needle = search.toLowerCase(Locale.ROOT);
        StringBuilder hay = new StringBuilder(160);
        hay.append(p.getClientCode() == null ? "" : p.getClientCode()).append(' ');
        String name = clientNames.get(p.getClientCode() == null ? "" : p.getClientCode().toUpperCase(Locale.ROOT));
        if (name != null) hay.append(name).append(' ');
        if (p.getImporterName() != null) hay.append(p.getImporterName()).append(' ');
        if (p.getBrokerName() != null) hay.append(p.getBrokerName()).append(' ');
        if (p.getBrokerCompany() != null) hay.append(p.getBrokerCompany()).append(' ');
        for (com.multiship.backend.model.CustomsProfileCountry link : p.getCountryLinks()) {
            if (link.getCountry() != null) hay.append(link.getCountry()).append(' ');
        }
        return hay.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private Comparator<ClientCustomsProfileDTO> profileComparator(String sortBy, String sortDirection) {
        Comparator<ClientCustomsProfileDTO> cmp;
        switch (sortBy == null ? "client" : sortBy) {
            case "destinations":
                cmp = Comparator.comparingInt(d -> d.getCountries() == null ? 0 : d.getCountries().size());
                break;
            case "importer":
                cmp = Comparator.comparing(d -> d.getImporterName() == null ? "" : d.getImporterName(),
                        String.CASE_INSENSITIVE_ORDER);
                break;
            case "broker":
                cmp = Comparator.comparing(d -> nvl(d.getBrokerName(), d.getBrokerCompany()),
                        String.CASE_INSENSITIVE_ORDER);
                break;
            case "client":
            default:
                cmp = Comparator.comparing(d -> d.getClientCode() == null ? "" : d.getClientCode(),
                        String.CASE_INSENSITIVE_ORDER);
        }
        return "DESC".equalsIgnoreCase(sortDirection) ? cmp.reversed() : cmp;
    }

    private static String nvl(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return "";
    }

    /** RFC-4180 CSV field: quote when the value contains ", newline, or a comma. */
    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<ClientCustomsProfileDTO>> list(String clientCode) {
        String code = norm(clientCode);
        // Sprint 50 Tier 0.5 PR H - defence-in-depth belt on path-param
        // clientCode. Controller SpEL from PR F already blocks scoped USERs;
        // this survives future refactors.
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ApiResponse<List<ClientCustomsProfileDTO>> guard = requireClient(code);
        if (guard != null) return guard;

        List<ClientCustomsProfileDTO> profiles = repository.findByClientCodeIgnoreCase(code)
                .stream()
                .sorted(Comparator.comparing(this::firstCountry))
                .map(this::toDTO)
                .toList();
        return success("Customs profiles retrieved.", profiles);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientCustomsProfileDTO> get(String clientCode, Long id) {
        String code = norm(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ApiResponse<ClientCustomsProfileDTO> guard = requireClient(code);
        if (guard != null) return guard;

        return repository.findById(id)
                .filter(p -> p.getClientCode().equalsIgnoreCase(code))
                .map(p -> success("Customs profile retrieved.", toDTO(p)))
                .orElseGet(() -> success("No such profile.", null));
    }

    @Override
    @Transactional
    public ApiResponse<ClientCustomsProfileDTO> upsert(String clientCode, ClientCustomsProfileDTO r) {
        String code = norm(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ApiResponse<ClientCustomsProfileDTO> guard = requireClient(code);
        if (guard != null) return guard;

        // Normalise & validate the destination set.
        Set<String> countries = new LinkedHashSet<>();
        if (r.getCountries() != null) {
            for (String c : r.getCountries()) {
                String cc = upper(c);
                if (cc != null && cc.length() == 2) countries.add(cc);
            }
        }
        if (countries.isEmpty()) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "Select at least one destination country.");
        }

        // Load target (update) or start fresh (create).
        ClientCustomsProfile p = null;
        if (r.getId() != null) {
            p = repository.findById(r.getId())
                    .filter(x -> x.getClientCode().equalsIgnoreCase(code))
                    .orElse(null);
            if (p == null) {
                return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR,
                        "Profile " + r.getId() + " was not found for " + code + ".");
            }
        }
        if (p == null) {
            p = ClientCustomsProfile.builder().clientCode(code).build();
        }

        // No destination may belong to another profile of the same client.
        Long selfId = p.getId();
        for (ClientCustomsProfile other : repository.findByClientCodeIgnoreCase(code)) {
            if (selfId != null && selfId.equals(other.getId())) continue;
            for (String c : other.getCountries()) {
                if (countries.contains(c)) {
                    return failure(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                            c + " is already covered by another " + code + " profile. "
                                    + "Remove it there first, or edit that profile instead.");
                }
            }
        }

        // RECEIVER (DAP): the consignee is the importer — no fixed identity needed.
        // BUSINESS (DDP): a real registered importer identity is mandatory.
        String importerType = "RECEIVER".equalsIgnoreCase(trimOrEmpty(r.getImporterType()))
                ? "RECEIVER" : "BUSINESS";
        if ("BUSINESS".equals(importerType)
                && (!StringUtils.hasText(r.getImporterName())
                        || !StringUtils.hasText(r.getImporterAddress1())
                        || !StringUtils.hasText(r.getImporterCity()))) {
            return failure(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_ERROR,
                    "A business importer needs a name, address line 1 and city — "
                            + "or switch the profile to 'Receiver is importer (DAP)'.");
        }

        p.syncCountries(countries);
        p.setImporterType(importerType);
        p.setImporterName(trim(r.getImporterName()));
        p.setImporterContact(trim(r.getImporterContact()));
        p.setImporterCountry(upper(r.getImporterCountry()));
        p.setImporterAddress1(trim(r.getImporterAddress1()));
        p.setImporterAddress2(trim(r.getImporterAddress2()));
        p.setImporterPhone(trim(r.getImporterPhone()));
        p.setImporterCity(trim(r.getImporterCity()));
        p.setImporterState(trim(r.getImporterState()));
        p.setImporterPostcode(trim(r.getImporterPostcode()));
        p.setImporterTaxId(trim(r.getImporterTaxId()));
        p.setImporterTaxIdType(upper(r.getImporterTaxIdType()));
        p.setImporterEori(trim(r.getImporterEori()));
        p.setImporterIoss(trim(r.getImporterIoss()));
        p.setImporterCompanyReg(trim(r.getImporterCompanyReg()));
        p.setImporterIec(trim(r.getImporterIec()));
        p.setImporterGstin(trim(r.getImporterGstin()));
        p.setBrokerName(trim(r.getBrokerName()));
        p.setBrokerCompany(trim(r.getBrokerCompany()));
        p.setBrokerCountry(upper(r.getBrokerCountry()));
        p.setBrokerAddress1(trim(r.getBrokerAddress1()));
        p.setBrokerAddress2(trim(r.getBrokerAddress2()));
        p.setBrokerPhone(trim(r.getBrokerPhone()));
        p.setBrokerCity(trim(r.getBrokerCity()));
        p.setBrokerState(trim(r.getBrokerState()));
        p.setBrokerPostcode(trim(r.getBrokerPostcode()));
        p.setBrokerId(trim(r.getBrokerId()));
        p.setBrokerLicense(trim(r.getBrokerLicense()));
        p.setIncoterms(upper(r.getIncoterms()));
        p.setDutiesBillTo(upper(r.getDutiesBillTo()));
        p.setDutiesAccount(trim(r.getDutiesAccount()));
        p.setReasonForExport(trim(r.getReasonForExport()));
        p.setCurrency(upper(r.getCurrency()));
        p.setAccountCarrier(upper(r.getAccountCarrier()));
        p.setAccountNo(trim(r.getAccountNo()));

        try {
            // Flush now so the unique(client_code, country) constraint fires inside
            // this method — the service pre-check above gives the friendly message;
            // this is the race-proof backstop.
            repository.saveAndFlush(p);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return failure(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "One of those countries was just claimed by another " + code
                            + " profile. Refresh and try again.");
        }
        return success("Importer/Broker profile for " + code + " saved (" + countries.size()
                + " destination" + (countries.size() == 1 ? "" : "s") + ").", toDTO(p));
    }

    @Override
    @Transactional
    public ApiResponse<Void> delete(String clientCode, Long id) {
        String code = norm(clientCode);
        if (tenantScope != null) tenantScope.requireTenantMatch(code);
        ApiResponse<Void> guard = requireClient(code);
        if (guard != null) return guard;

        repository.findById(id)
                .filter(p -> p.getClientCode().equalsIgnoreCase(code))
                .ifPresent(repository::delete);
        return success("Customs profile removed.", null);
    }

    // ===== helpers =====

    private String firstCountry(ClientCustomsProfile p) {
        return p.getCountries().stream().sorted().findFirst().orElse("");
    }

    private <T> ApiResponse<T> requireClient(String code) {
        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND, "Client " + code + " was not found.");
        }
        return null;
    }

    private ClientCustomsProfileDTO toDTO(ClientCustomsProfile p) {
        return ClientCustomsProfileDTO.builder()
                .id(p.getId())
                .clientCode(p.getClientCode())
                .countries(new ArrayList<>(p.getCountries()).stream().sorted().collect(Collectors.toList()))
                .importerType(p.getImporterType())
                .importerName(p.getImporterName())
                .importerContact(p.getImporterContact())
                .importerCountry(p.getImporterCountry())
                .importerAddress1(p.getImporterAddress1())
                .importerAddress2(p.getImporterAddress2())
                .importerPhone(p.getImporterPhone())
                .importerCity(p.getImporterCity())
                .importerState(p.getImporterState())
                .importerPostcode(p.getImporterPostcode())
                .importerTaxId(p.getImporterTaxId())
                .importerTaxIdType(p.getImporterTaxIdType())
                .importerEori(p.getImporterEori())
                .importerIoss(p.getImporterIoss())
                .importerCompanyReg(p.getImporterCompanyReg())
                .importerIec(p.getImporterIec())
                .importerGstin(p.getImporterGstin())
                .brokerName(p.getBrokerName())
                .brokerCompany(p.getBrokerCompany())
                .brokerCountry(p.getBrokerCountry())
                .brokerAddress1(p.getBrokerAddress1())
                .brokerAddress2(p.getBrokerAddress2())
                .brokerPhone(p.getBrokerPhone())
                .brokerCity(p.getBrokerCity())
                .brokerState(p.getBrokerState())
                .brokerPostcode(p.getBrokerPostcode())
                .brokerId(p.getBrokerId())
                .brokerLicense(p.getBrokerLicense())
                .incoterms(p.getIncoterms())
                .dutiesBillTo(p.getDutiesBillTo())
                .dutiesAccount(p.getDutiesAccount())
                .reasonForExport(p.getReasonForExport())
                .currency(p.getCurrency())
                .accountCarrier(p.getAccountCarrier())
                .accountNo(p.getAccountNo())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    /**
     * Sprint 50 Tier 0.5 PR H — apply the tenant scope to
     * {@link CustomsProfileFilters#getClientCode()}. Platform operators
     * (empty scope) leave the filter alone; scoped USERs get their filter
     * clamped to their tenant (foreign value → 403, unset → forced to
     * their tenant).
     */
    private void clampTenantFilter(CustomsProfileFilters filters) {
        if (tenantScope == null) return;
        String clamped = tenantScope.clampClientCode(filters.getClientCode());
        // Only mutate when clamping actually changed something to avoid
        // mutating the caller-supplied DTO for platform operators.
        if (clamped != null) {
            filters.setClientCode(clamped);
        }
    }

    private String norm(String v) { return v != null ? v.trim().toUpperCase(Locale.ROOT) : ""; }
    private String trimOrEmpty(String v) { return v != null ? v.trim() : ""; }
    private String upper(String v) { return StringUtils.hasText(v) ? v.trim().toUpperCase(Locale.ROOT) : null; }
    private String trim(String v) { return StringUtils.hasText(v) ? v.trim() : null; }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder().status("SUCCESS").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(data).build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder().status("ERROR").code(status.value()).errorCode(errorCode.name())
                .message(message).timestamp(LocalDateTime.now()).build();
    }
}
