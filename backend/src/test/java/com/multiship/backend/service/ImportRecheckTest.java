package com.multiship.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.Client;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A saved import is checked again when the settings it depends on change. */
class ImportRecheckTest {

    private final ObjectMapper json = new ObjectMapper();

    private static OrderImportRowDTO row(int n, String ref) {
        return OrderImportRowDTO.builder().rowNumber(n).orderRef(ref).clientCode("POB250")
                .recipientName("WS Store").recipientPhone("6167723513").addressLine1("10 Columbus Circle")
                .city("New York").state("NY").postalCode("10019").countryCode("US")
                .weight(new BigDecimal("7")).weightUnit("LB").build();
    }

    @Test
    void addingTheClientClearsNotRegistered_butUpsAnswersStayUntilTheNextValidateAll() throws Exception {
        OrderImportRowDTO a = row(1, "A-1");
        a.setErrors(new ArrayList<>(List.of("clientCode POB250 is not registered")));
        OrderImportRowDTO b = row(2, "B-1");
        String ups = "serviceType — UPS doesn't offer UPS Ground (U11) to this address. Use U43 (UPS 2nd Day Air) instead";
        b.setErrors(new ArrayList<>(List.of("clientCode POB250 is not registered", ups)));

        ImportBatch batch = new ImportBatch();
        batch.setId(7L);
        batch.setStatus("DRAFT");
        batch.setSource("BULK");   // a file import: its rows are stored back on the batch here
        batch.setRowsJson(json.writeValueAsString(List.of(a, b)));
        ImportBatchRepository repo = mock(ImportBatchRepository.class);
        when(repo.findById(7L)).thenReturn(Optional.of(batch));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // POB250 is registered now.
        Client pob = new Client();
        pob.setClientCode("POB250");
        pob.setStatus("ACTIVE");
        ClientRepository clients = mock(ClientRepository.class);
        when(clients.findByClientCodeInIgnoreCase(any())).thenReturn(List.of(pob));

        OrderImportServiceImpl service = new OrderImportServiceImpl(null, null, null, null, clients, null, null, null);
        ReflectionTestUtils.setField(service, "importBatchRepository", repo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);

        service.recheckInBackground(7L);

        List<OrderImportRowDTO> after = json.readValue(batch.getRowsJson(), new TypeReference<>() {});
        assertFalse(after.get(0).getErrors().contains("clientCode POB250 is not registered"), String.valueOf(after.get(0).getErrors()));
        assertFalse(after.get(1).getErrors().contains("clientCode POB250 is not registered"));
        assertTrue(after.get(1).getErrors().contains(ups), "UPS's answer stays until Validate all asks again");
        verify(repo).save(batch);
    }

    @Test
    void changesAreGatheredAndOnlyThatClientsUnfinishedImportsAreChecked() {
        ImportBatchRepository repo = mock(ImportBatchRepository.class);
        OrderImportServiceImpl service = mock(OrderImportServiceImpl.class);
        when(repo.findIdsToRecheckForClients(Set.of("POB250"))).thenReturn(List.of(11L, 12L));
        ImportRevalidator revalidator = new ImportRevalidator(repo, service);

        revalidator.queue("POB250");
        revalidator.queue("POB250");
        revalidator.run();

        verify(service).recheckInBackground(11L);
        verify(service).recheckInBackground(12L);
    }

    @Test
    void aPlatformWideChangeChecksEveryUnfinishedImport() {
        ImportBatchRepository repo = mock(ImportBatchRepository.class);
        OrderImportServiceImpl service = mock(OrderImportServiceImpl.class);
        when(repo.findIdsToRecheck()).thenReturn(List.of(3L));
        ImportRevalidator revalidator = new ImportRevalidator(repo, service);

        revalidator.queue("");
        revalidator.run();

        verify(service).recheckInBackground(3L);
    }
}
