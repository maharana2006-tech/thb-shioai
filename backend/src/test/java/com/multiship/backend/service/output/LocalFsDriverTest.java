package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ClientOutputDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 52 — {@link LocalFsDriver} unit tests. Uses JUnit @TempDir so
 * every test gets an isolated directory and cleanup is automatic.
 */
class LocalFsDriverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalFsDriver driver = new LocalFsDriver(mapper);

    private ClientOutputDestination destination(String path) {
        return ClientOutputDestination.builder()
                .id(11L)
                .clientCode("ACME")
                .docType(DocType.LABEL)
                .destinationType(DestinationType.LOCAL_FS)
                .config("{\"path\":\"" + path.replace("\\", "\\\\") + "\"}")
                .active(true)
                .build();
    }

    @Test
    void supportsReturnsLocalFs() {
        assertEquals(DestinationType.LOCAL_FS, driver.supports());
    }

    @Test
    void dispatchWritesBytesToConfiguredPath(@TempDir Path dir) throws Exception {
        ClientOutputDestination dest = destination(dir.toString());
        DispatchContext ctx = new DispatchContext(
                42L, 100, "ACME", "application/pdf", null);
        byte[] payload = "hello-label".getBytes();

        driver.dispatch(dest, DocType.LABEL, payload, ctx);

        List<Path> files = Files.list(dir).toList();
        assertEquals(1, files.size(), "exactly one file should be written");
        Path written = files.get(0);
        assertTrue(written.getFileName().toString().endsWith(".pdf"));
        assertArrayEquals(payload, Files.readAllBytes(written));
    }

    @Test
    void dispatchUsesFileNameHintWhenPresent(@TempDir Path dir) throws Exception {
        ClientOutputDestination dest = destination(dir.toString());
        DispatchContext ctx = new DispatchContext(
                1L, 2, "ACME", "text/plain", "custom_name.txt");

        driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx);

        assertTrue(Files.exists(dir.resolve("custom_name.txt")));
    }

    @Test
    void dispatchFailsWhenDirectoryMissing() {
        ClientOutputDestination dest = destination("/nonexistent/definitely/missing/path");
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);

        OutputDeliveryException ex = assertThrows(OutputDeliveryException.class, () ->
                driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx));
        assertTrue(ex.getMessage().contains("existing directory"));
        assertEquals(DestinationType.LOCAL_FS, ex.getDestinationType());
    }

    @Test
    void dispatchFailsWhenPathBlank() {
        ClientOutputDestination dest = ClientOutputDestination.builder()
                .id(12L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.LOCAL_FS)
                .config("{\"path\":\"\"}").active(true).build();
        DispatchContext ctx = new DispatchContext(1L, 1, "ACME", null, null);

        assertThrows(OutputDeliveryException.class, () ->
                driver.dispatch(dest, DocType.LABEL, "x".getBytes(), ctx));
    }

    @Test
    void extensionForZplContentTypeUsesZplExtension() {
        assertEquals("zpl", LocalFsDriver.extensionFor(DocType.LABEL, "application/vnd.zebra.zpl"));
        assertEquals("pdf", LocalFsDriver.extensionFor(DocType.LABEL, "application/pdf"));
        assertEquals("pdf", LocalFsDriver.extensionFor(DocType.COMMERCIAL_INVOICE, null));
    }

    @Test
    void dispatchWritesAtomically(@TempDir Path dir) throws Exception {
        // Verify no .part temp file is left after a successful write.
        ClientOutputDestination dest = destination(dir.toString());
        DispatchContext ctx = new DispatchContext(99L, 199, "ACME", null, null);

        driver.dispatch(dest, DocType.LABEL, "y".getBytes(), ctx);

        List<Path> partFiles = Files.list(dir)
                .filter(p -> p.getFileName().toString().endsWith(".part"))
                .toList();
        assertTrue(partFiles.isEmpty(), "no .part temp file should remain");
    }
}
