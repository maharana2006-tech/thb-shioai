package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSRF (DNS-rebinding) regression for {@link SftpDriver}. The host is validated
 * at admin save, but DNS can be re-pointed to an internal address before a
 * dispatch; openSession re-runs the address-only guard right before connecting.
 *
 * <p>Key point: the guard must NOT reuse {@code PrinterAddressGuard.refusal(host,
 * port)} — SFTP's legitimate port 22 is in that method's service-port blocklist,
 * so it would reject every SFTP connect. It uses {@code refusalForAddress}.
 */
class SftpDriverSsrfTest {

    private SystemSettingRepository settings;
    private CryptoService crypto;
    private SftpSessionPool pool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingRepository.class);
        crypto = mock(CryptoService.class);
        pool = new SftpSessionPool(4, 60);
        when(crypto.isAvailable()).thenReturn(true);
    }

    private ClientOutputDestination dest(String configJson) {
        return ClientOutputDestination.builder()
                .id(9001L).clientCode("ACME").docType(DocType.LABEL)
                .destinationType(DestinationType.SFTP)
                .config(configJson).active(true).build();
    }

    private void stubSecret(String id, String plaintext) {
        SystemSetting row = new SystemSetting();
        row.setKey(id);
        row.setEncryptedValue("cipher(" + plaintext + ")");
        when(settings.findByKey(id)).thenReturn(Optional.of(row));
        when(crypto.decrypt("cipher(" + plaintext + ")")).thenReturn(plaintext);
    }

    private static String cfg(String host) {
        return "{\"host\":\"" + host + "\",\"port\":22,\"username\":\"acme\","
                + "\"authType\":\"PASSWORD\",\"passwordSecretId\":\"sftp.pw\",\"remoteDir\":\"/upload\"}";
    }

    /** JSch that records whether getSession was ever reached. */
    private static class TrackingJSch extends JSch {
        final AtomicBoolean getSessionCalled = new AtomicBoolean(false);

        @Override
        public Session getSession(String username, String host, int port) throws JSchException {
            getSessionCalled.set(true);
            Session s = mock(Session.class);
            when(s.isConnected()).thenReturn(true);
            return s;
        }
    }

    private OutputDeliveryException dispatchExpectingFailure(String host, TrackingJSch jsch) {
        stubSecret("sftp.pw", "hunter2");
        Supplier<JSch> factory = () -> jsch;
        SftpDriver driver = new SftpDriver(mapper, settings, crypto, pool, factory);
        return assertThrows(OutputDeliveryException.class, () -> driver.dispatch(
                dest(cfg(host)), DocType.LABEL, "x".getBytes(),
                new DispatchContext(1L, 1, "ACME", null, "test.zpl")));
    }

    @Test
    void blocksHostResolvingToCloudMetadata() {
        TrackingJSch jsch = new TrackingJSch();
        OutputDeliveryException ex = dispatchExpectingFailure("169.254.169.254", jsch);
        assertTrue(ex.getMessage().contains("SFTP blocked"),
                "link-local metadata host must be SSRF-blocked. Got: " + ex.getMessage());
        assertFalse(jsch.getSessionCalled.get(), "must reject before opening the SSH session");
    }

    @Test
    void blocksHostResolvingToLoopback() {
        TrackingJSch jsch = new TrackingJSch();
        OutputDeliveryException ex = dispatchExpectingFailure("127.0.0.1", jsch);
        assertTrue(ex.getMessage().contains("SFTP blocked"),
                "loopback host must be SSRF-blocked. Got: " + ex.getMessage());
        assertFalse(jsch.getSessionCalled.get(), "must reject before opening the SSH session");
    }

    @Test
    void allowsNormalPublicHostOnPort22() {
        // Proves the guard does NOT reuse the printer port blocklist (port 22 =
        // SSH there). A normal host must reach getSession; the dispatch then
        // fails later on the mocked session, but NOT with "SFTP blocked".
        TrackingJSch jsch = new TrackingJSch();
        OutputDeliveryException ex = dispatchExpectingFailure("8.8.8.8", jsch);
        assertTrue(jsch.getSessionCalled.get(),
                "a normal host on port 22 must reach getSession (port 22 must not be blocked)");
        assertFalse(ex.getMessage().contains("SFTP blocked"),
                "a normal public host must not be SSRF-blocked. Got: " + ex.getMessage());
    }
}
