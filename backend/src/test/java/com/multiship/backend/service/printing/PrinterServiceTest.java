package com.multiship.backend.service.printing;

import com.multiship.backend.model.Printer;
import com.multiship.backend.model.PrinterAssignment;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.PrinterAssignmentRepository;
import com.multiship.backend.repository.PrinterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrinterServiceTest {

    private PrinterRepository printers;
    private PrinterAssignmentRepository assignments;
    private ClientRepository clients;
    private PrinterService service;

    @BeforeEach
    void setUp() {
        printers = mock(PrinterRepository.class);
        assignments = mock(PrinterAssignmentRepository.class);
        clients = mock(ClientRepository.class);
        service = new PrinterService(printers, assignments, clients, mock(OrderRepository.class));
        when(printers.save(any(Printer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignments.save(any(PrinterAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clients.existsByClientCodeIgnoreCase(anyString())).thenReturn(true);
    }

    private static PrinterService.PrinterInput input(String connection, String host, Integer port, String format, String paper) {
        return new PrinterService.PrinterInput("Dock 1", "Bay A", connection, host, port, null, format, paper, true);
    }

    private static Printer printer(long id, String format, boolean active) {
        Printer p = new Printer();
        p.setId(id);
        p.setName("P" + id);
        p.setFormat(format);
        p.setActive(active);
        return p;
    }

    @Test
    void validationKeepsPrinterConfigurationsThatCanActuallyPrint() {
        String m;
        m = assertThrows(PrinterService.PrinterValidationException.class,
                () -> service.create(input("IPP", "192.168.1.50", null, "ZPL", null))).getMessage();
        assertTrue(m.contains("IPP printers take PDF"), m);
        m = assertThrows(PrinterService.PrinterValidationException.class,
                () -> service.create(input("RAW_9100", "192.168.1.50", null, "ZPL", "A4"))).getMessage();
        assertTrue(m.contains("LABEL_4X6"), m);
        m = assertThrows(PrinterService.PrinterValidationException.class,
                () -> service.create(input("RAW_9100", "http://192.168.1.50", null, "ZPL", null))).getMessage();
        assertTrue(m.contains("without http"), m);
        m = assertThrows(PrinterService.PrinterValidationException.class,
                () -> service.create(input("RAW_9100", "192.168.1.50", 70000, "ZPL", null))).getMessage();
        assertTrue(m.contains("Port"), m);
    }

    @Test
    void privateNetworkPrintersAreAccepted_withSensibleDefaults() {
        Printer zebra = service.create(input("RAW_9100", "192.168.1.50", null, "zpl", null));
        assertEquals(9100, zebra.getPort());
        assertEquals("LABEL_4X6", zebra.getPaper());
        Printer laser = service.create(input("IPP", "10.0.0.20", null, "PDF", null));
        assertEquals(631, laser.getPort());
        assertEquals("ipp/print", laser.getQueuePath());
        assertEquals("LETTER", laser.getPaper());
    }

    @Test
    void invoicesCannotBeAssignedToAZplLabelPrinter() {
        when(printers.findById(1L)).thenReturn(Optional.of(printer(1, "ZPL", true)));
        String m = assertThrows(PrinterService.PrinterValidationException.class,
                () -> service.upsertAssignment(new PrinterService.AssignmentInput("ACME", "COMMERCIAL_INVOICE", 1L))).getMessage();
        assertTrue(m.contains("PDF printer"), m);
    }

    @Test
    void aClientsOwnPrinterWins_elseTheDefault_andSwitchedOffPrintersAreSkipped() {
        PrinterAssignment acme = new PrinterAssignment();
        acme.setPrinterId(1L);
        PrinterAssignment dflt = new PrinterAssignment();
        dflt.setPrinterId(2L);
        when(assignments.findFirstByClientCodeIgnoreCaseAndDocType("ACME", "LABEL")).thenReturn(Optional.of(acme));
        when(assignments.findFirstByClientCodeIgnoreCaseAndDocType("DES875", "LABEL")).thenReturn(Optional.empty());
        when(assignments.findFirstByClientCodeIsNullAndDocType("LABEL")).thenReturn(Optional.of(dflt));
        when(printers.findById(1L)).thenReturn(Optional.of(printer(1, "ZPL", true)));
        when(printers.findById(2L)).thenReturn(Optional.of(printer(2, "ZPL", true)));

        assertEquals(1L, service.resolve("acme", "LABEL").orElseThrow().getId());
        assertEquals(2L, service.resolve("DES875", "LABEL").orElseThrow().getId());
        assertEquals(2L, service.resolve(null, "LABEL").orElseThrow().getId());

        when(printers.findById(1L)).thenReturn(Optional.of(printer(1, "ZPL", false)));
        assertEquals(2L, service.resolve("ACME", "LABEL").orElseThrow().getId(), "a switched-off client printer falls back");
    }
}
