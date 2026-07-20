package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.AddressDTO;
import com.multiship.backend.dto.ClientDTO;
import com.multiship.backend.dto.ClientListFilters;
import com.multiship.backend.dto.ClientUpsertRequest;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.Client;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final ClientRepository clientRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;
    private final OrderRepository orderRepository;
    private final com.multiship.backend.repository.ClientCustomsProfileRepository customsProfileRepository;

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<PageResponseDTO<ClientDTO>> listClients(ClientListFilters filters) {
        int page = Math.max(filters.getPage(), 0);
        int size = Math.min(Math.max(filters.getSize(), 1), 100);
        String keyword = norm(filters.getSearch());
        String status = norm(filters.getStatus());
        String carrier = norm(filters.getCarrier());
        String hasOrders = norm(filters.getHasOrders());
        String code = norm(filters.getCode());
        String name = norm(filters.getName());
        String city = norm(filters.getCity());
        String sortBy = filters.getSortBy() != null ? filters.getSortBy() : "code";
        String sortDirection = filters.getSortDirection() != null ? filters.getSortDirection() : "ASC";

        List<String> codes = clientRepository.filterCodes(
                keyword, status, carrier, hasOrders, code, name, city,
                sortBy, sortDirection, page * size, size);
        long total = clientRepository.countFiltered(keyword, status, carrier, hasOrders, code, name, city);

        List<ClientDTO> clients = codes.stream()
                .map(c -> clientRepository.findByClientCodeIgnoreCase(c).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(this::toDTO)
                .toList();

        return success("Clients retrieved successfully.", PageResponseDTO.of(clients, page, size, total));
    }

    private String norm(String value) {
        return value != null ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<ClientDTO> getClient(String clientCode) {
        Client client = clientRepository.findByClientCodeIgnoreCase(normalize(clientCode)).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + normalize(clientCode) + " was not found.");
        }

        return success("Client retrieved successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> createClient(ClientUpsertRequest request) {
        String code = normalize(request.getClientCode());

        if (clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.CONFLICT, ErrorCode.CLIENT_CODE_TAKEN,
                    "Client code " + code + " is already registered.");
        }

        Client client = Client.builder()
                .clientCode(code)
                .status(Client.STATUS_ACTIVE)
                .build();
        applyFields(client, request);
        clientRepository.save(client);

        return success("Client " + code + " created successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> updateClient(String clientCode, ClientUpsertRequest request) {
        Client client = clientRepository.findByClientCodeIgnoreCase(normalize(clientCode)).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + normalize(clientCode) + " was not found.");
        }

        // The code is the linkage key to orders and accounts — immutable.
        applyFields(client, request);
        clientRepository.save(client);

        return success("Client " + client.getClientCode() + " updated successfully.", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<ClientDTO> toggleActive(String clientCode) {
        Client client = clientRepository.findByClientCodeIgnoreCase(normalize(clientCode)).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + normalize(clientCode) + " was not found.");
        }

        client.setStatus(client.isActive() ? Client.STATUS_INACTIVE : Client.STATUS_ACTIVE);
        clientRepository.save(client);

        return success("Client " + client.getClientCode() + " is now " + client.getStatus() + ".", toDTO(client));
    }

    @Override
    @Transactional
    public ApiResponse<Void> deleteClient(String clientCode) {
        String code = normalize(clientCode);
        Client client = clientRepository.findByClientCodeIgnoreCase(code).orElse(null);

        if (client == null) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }

        long orders = orderRepository.countOrdersUnified("", code, "", "", "", "", "", "", "", "");
        if (orders > 0) {
            return failure(HttpStatus.CONFLICT, ErrorCode.CLIENT_HAS_ORDERS,
                    "Client " + code + " has " + orders + " orders and cannot be deleted — deactivate it instead.");
        }

        // The client's config rides along — customs profiles and carrier accounts
        // are meaningless without their owner and must not linger as orphans
        // (they are linked by client-code string, not FK, so nothing cascades).
        customsProfileRepository.deleteAll(customsProfileRepository.findByClientCodeIgnoreCase(code));
        carrierAccountRefRepository.deleteAll(
                carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code));

        clientRepository.delete(client);
        return success("Client " + code + " deleted successfully (including its carrier accounts and customs profiles).", null);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<CarrierAccountRefDTO>> listClientAccounts(String clientCode) {
        String code = normalize(clientCode);

        if (!clientRepository.existsByClientCodeIgnoreCase(code)) {
            return failure(HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND,
                    "Client " + code + " was not found.");
        }

        List<CarrierAccountRefDTO> accounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(code)
                .stream()
                .map(this::toAccountSummary)
                .toList();

        return success("Client accounts retrieved successfully.", accounts);
    }

    // ===== helpers =====

    private void applyFields(Client client, ClientUpsertRequest request) {
        client.setName(request.getName().trim());
        client.setEmail(trimOrNull(request.getEmail()));
        client.setPhone(trimOrNull(request.getPhone()));

        Address shipFrom = toAddress(request.getShipFrom());
        client.setShipFrom(shipFrom);

        boolean sameAsShipFrom = !Boolean.FALSE.equals(request.getReturnSameAsShipFrom());
        client.setReturnSameAsShipFrom(sameAsShipFrom);
        // Store the distinct return address only when the client keeps one;
        // otherwise clear it so it visibly mirrors ship-from.
        client.setReturnAddress(sameAsShipFrom ? null : toAddress(request.getReturnAddress()));
    }

    /** Trim an address DTO into an entity Address; uppercase the country (defaults US). */
    private Address toAddress(AddressDTO dto) {
        if (dto == null) {
            return Address.builder().country("US").build();
        }
        return Address.builder()
                .name(trimOrNull(dto.getName()))
                .line1(trimOrNull(dto.getLine1()))
                .line2(trimOrNull(dto.getLine2()))
                .city(trimOrNull(dto.getCity()))
                .state(trimOrNull(dto.getState()))
                .zip(trimOrNull(dto.getZip()))
                .country(StringUtils.hasText(dto.getCountry()) ? dto.getCountry().trim().toUpperCase(Locale.ROOT) : "US")
                .phone(trimOrNull(dto.getPhone()))
                .build();
    }

    private AddressDTO toAddressDTO(Address address) {
        if (address == null) {
            return null;
        }
        return AddressDTO.builder()
                .name(address.getName())
                .line1(address.getLine1())
                .line2(address.getLine2())
                .city(address.getCity())
                .state(address.getState())
                .zip(address.getZip())
                .country(address.getCountry())
                .phone(address.getPhone())
                .build();
    }

    private ClientDTO toDTO(Client client) {
        List<CarrierAccountRefDTO> accounts = carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(client.getClientCode())
                .stream()
                .map(this::toAccountSummary)
                .toList();

        return ClientDTO.builder()
                .id(client.getId())
                .clientCode(client.getClientCode())
                .name(client.getName())
                .email(client.getEmail())
                .phone(client.getPhone())
                .status(client.getStatus())
                .shipFrom(toAddressDTO(client.getShipFrom()))
                .returnAddress(toAddressDTO(
                        Boolean.FALSE.equals(client.getReturnSameAsShipFrom()) ? client.getReturnAddress() : null))
                .returnSameAsShipFrom(!Boolean.FALSE.equals(client.getReturnSameAsShipFrom()))
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .carrierAccounts(accounts)
                .orderCount(orderRepository.countOrdersUnified("", client.getClientCode().toUpperCase(Locale.ROOT), "", "", "", "", "", "", "", ""))
                .build();
    }

    private CarrierAccountRefDTO toAccountSummary(CarrierAccountRef account) {
        return CarrierAccountRefDTO.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .carrierCode(account.getCarrierCode())
                .accountName(account.getAccountName())
                .customerNo(account.getCustomerNo())
                .environment(account.getEnvironment())
                .isDefault(Boolean.TRUE.equals(account.getIsDefault()))
                .clientDefault(Boolean.TRUE.equals(account.getClientDefault()))
                .active(!Boolean.FALSE.equals(account.getActive()))
                .complete(account.isComplete())
                .verified(account.getVerified())
                .lastVerifiedAt(account.getLastVerifiedAt())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    private String normalize(String code) {
        return code != null ? code.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(200)
                .message(message)
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();
    }

    private <T> ApiResponse<T> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .code(status.value())
                .errorCode(errorCode.name())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
