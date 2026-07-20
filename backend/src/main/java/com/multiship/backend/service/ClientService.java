package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.ClientDTO;
import com.multiship.backend.dto.ClientListFilters;
import com.multiship.backend.dto.ClientUpsertRequest;
import com.multiship.backend.dto.PageResponseDTO;

import java.util.List;

public interface ClientService {

    ApiResponse<PageResponseDTO<ClientDTO>> listClients(ClientListFilters filters);

    ApiResponse<ClientDTO> getClient(String clientCode);

    ApiResponse<ClientDTO> createClient(ClientUpsertRequest request);

    ApiResponse<ClientDTO> updateClient(String clientCode, ClientUpsertRequest request);

    ApiResponse<ClientDTO> toggleActive(String clientCode);

    ApiResponse<Void> deleteClient(String clientCode);

    ApiResponse<List<CarrierAccountRefDTO>> listClientAccounts(String clientCode);
}
