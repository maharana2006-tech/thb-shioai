package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.repository.ClientOutputDestinationRepository;
import com.multiship.backend.repository.SystemSettingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 output-polish (follow-up #3) — end-to-end verification that
 * {@link OutputDestinationAdminService#test(Long)} routes a doc-type-
 * appropriate payload through {@link OutputDestinationService#testDispatch}
 * to the matching driver. The driver here is a mock — no network I/O.
 */
class OutputDestinationAdminServiceTestDispatchTest {

    private ClientOutputDestinationRepository destinationRepo;
    private SystemSettingRepository systemSettingRepo;
    private CryptoService cryptoService;
    private OutputDestinationService outputService;
    private ObjectMapper mapper;
    private OutputDestinationAdminService admin;

    private OutputDriver localDriver;
    private OutputDriver printerDriver;
    private OutputDriver sftpDriver;

    @BeforeEach
    void setUp() {
        destinationRepo   = mock(ClientOutputDestinationRepository.class);
        systemSettingRepo = mock(SystemSettingRepository.class);
        cryptoService     = mock(CryptoService.class);
        mapper            = new ObjectMapper();

        // Real OutputDestinationService with mocked drivers so we can
        // assert exactly what bytes reach each driver.
        DatabaseCopyDriver dbCopy = mock(DatabaseCopyDriver.class);
        localDriver   = mock(OutputDriver.class);
        printerDriver = mock(OutputDriver.class);
        sftpDriver    = mock(OutputDriver.class);
        when(localDriver.supports()).thenReturn(DestinationType.LOCAL_FS);
        when(printerDriver.supports()).thenReturn(DestinationType.PRINTER);
        when(sftpDriver.supports()).thenReturn(DestinationType.SFTP);

        outputService = new OutputDestinationService(
                destinationRepo, dbCopy,
                List.of(localDriver, printerDriver, sftpDriver),
                new SimpleMeterRegistry());

        admin = new OutputDestinationAdminService(destinationRepo, systemSettingRepo,
                cryptoService, mapper, outputService, new TestPayloadFactory());
    }

    private ClientOutputDestination stubDest(Long id, DestinationType type, DocType doc, String config) {
        ClientOutputDestination d = ClientOutputDestination.builder()
                .id(id).clientCode("ACME").docType(doc)
                .destinationType(type).config(config).active(true).build();
        when(destinationRepo.findById(id)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void testPrinterRawSendsZplPayload() {
        stubDest(1L, DestinationType.PRINTER, DocType.LABEL,
                "{\"host\":\"127.0.0.1\",\"protocol\":\"RAW_9100\"}");

        DispatchResult result = admin.test(1L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<DispatchContext> ctx = ArgumentCaptor.forClass(DispatchContext.class);
        verify(printerDriver).dispatch(any(), any(), bytes.capture(), ctx.capture());
        assertEquals(1, result.getSuccessCount());
        String body = new String(bytes.getValue(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("^XA"), "printer RAW should get ZPL");
        assertEquals("application/zpl", ctx.getValue().contentType());
    }

    @Test
    void testPrinterIppSendsPdfPayload() {
        stubDest(2L, DestinationType.PRINTER, DocType.LABEL,
                "{\"host\":\"127.0.0.1\",\"protocol\":\"IPP\",\"queueName\":\"lp1\"}");

        admin.test(2L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<DispatchContext> ctx = ArgumentCaptor.forClass(DispatchContext.class);
        verify(printerDriver).dispatch(any(), any(), bytes.capture(), ctx.capture());
        String head = new String(bytes.getValue(), 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", head, "printer IPP should get a PDF");
        assertEquals("application/pdf", ctx.getValue().contentType());
    }

    @Test
    void testLocalFsSendsZplPayload() {
        stubDest(3L, DestinationType.LOCAL_FS, DocType.LABEL, "{\"path\":\"/tmp\"}");

        admin.test(3L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(localDriver).dispatch(any(), any(), bytes.capture(), any());
        assertTrue(new String(bytes.getValue(), StandardCharsets.UTF_8).contains("^XA"));
    }

    @Test
    void testCommercialInvoiceAlwaysSendsPdfEvenToPrinter() {
        stubDest(4L, DestinationType.PRINTER, DocType.COMMERCIAL_INVOICE,
                "{\"host\":\"127.0.0.1\",\"protocol\":\"RAW_9100\"}");

        admin.test(4L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<DispatchContext> ctx = ArgumentCaptor.forClass(DispatchContext.class);
        verify(printerDriver).dispatch(any(), any(), bytes.capture(), ctx.capture());
        String head = new String(bytes.getValue(), 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", head, "CI must always be PDF");
        assertEquals("application/pdf", ctx.getValue().contentType());
    }

    @Test
    void testUnknownIdThrowsIllegalArgument() {
        when(destinationRepo.findById(anyLong())).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> admin.test(999L));
    }
}
