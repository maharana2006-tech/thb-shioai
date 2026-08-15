package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ClientOutputDestination;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 output-polish (follow-up #2) — verifies {@link SftpDriver}
 * flips {@code StrictHostKeyChecking} on when a {@code knownHostsSecretId}
 * is configured, and preserves the legacy "off" behaviour when it isn't.
 *
 * <p>Uses an instrumented {@link JSch} subclass that observes both the
 * {@code setKnownHosts} call and the properties passed to
 * {@link Session#setConfig(Properties)} — no real network I/O.
 */
class SftpDriverKnownHostsTest {

    private SystemSettingRepository settings;
    private CryptoService crypto;
    private SftpSessionPool pool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingRepository.class);
        crypto   = mock(CryptoService.class);
        pool     = new SftpSessionPool(4, 60);

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

    /** JSch that lets the test observe setKnownHosts + captures the last
     *  session's setConfig(Properties) call. */
    private static class InstrumentedJSch extends JSch {
        final AtomicBoolean knownHostsCalled = new AtomicBoolean(false);
        final AtomicReference<Properties> lastSessionProps = new AtomicReference<>();

        @Override
        public void setKnownHosts(InputStream stream) {
            knownHostsCalled.set(true);
            // Do NOT delegate — the real parser would need a valid
            // known_hosts body; the test only cares that we invoked it.
        }

        @Override
        public Session getSession(String username, String host, int port) throws com.jcraft.jsch.JSchException {
            Session s = mock(Session.class);
            // Spy the setConfig call so the test can assert on it.
            org.mockito.Mockito.doAnswer(inv -> {
                lastSessionProps.set(inv.getArgument(0));
                return null;
            }).when(s).setConfig(org.mockito.ArgumentMatchers.any(Properties.class));
            when(s.isConnected()).thenReturn(true);
            return s;
        }
    }

    @Test
    void whenKnownHostsSecretIdPresentStrictCheckingIsOn() {
        stubSecret("sftp.pw.1", "hunter2");
        stubSecret("sftp.kh.1", "example.com ssh-rsa AAAAB3...\n");

        InstrumentedJSch instrumented = new InstrumentedJSch();
        Supplier<JSch> factory = () -> instrumented;
        SftpDriver driver = new SftpDriver(mapper, settings, crypto, pool, factory);

        String cfg = "{\"host\":\"example.com\",\"port\":22,\"username\":\"acme\","
                + "\"authType\":\"PASSWORD\",\"passwordSecretId\":\"sftp.pw.1\","
                + "\"knownHostsSecretId\":\"sftp.kh.1\",\"remoteDir\":\"/upload\"}";

        // The session's openChannel will fail (mocked null) — that's fine,
        // we assert the configuration side-effects that happen BEFORE the
        // channel open.
        assertThrows(OutputDeliveryException.class, () -> driver.dispatch(
                dest(cfg), DocType.LABEL, "x".getBytes(),
                new DispatchContext(1L, 1, "ACME", null, "test.zpl")));

        assertTrue(instrumented.knownHostsCalled.get(),
                "setKnownHosts must be invoked when knownHostsSecretId is present");
        Properties props = instrumented.lastSessionProps.get();
        assertEquals("yes", props.getProperty("StrictHostKeyChecking"),
                "StrictHostKeyChecking must be 'yes' when known-hosts are provided");
    }

    @Test
    void withoutKnownHostsSecretIdLegacyBehaviourPreserved() {
        stubSecret("sftp.pw.2", "hunter2");

        InstrumentedJSch instrumented = new InstrumentedJSch();
        Supplier<JSch> factory = () -> instrumented;
        SftpDriver driver = new SftpDriver(mapper, settings, crypto, pool, factory);

        String cfg = "{\"host\":\"example.com\",\"port\":22,\"username\":\"acme\","
                + "\"authType\":\"PASSWORD\",\"passwordSecretId\":\"sftp.pw.2\","
                + "\"remoteDir\":\"/upload\"}";

        assertThrows(OutputDeliveryException.class, () -> driver.dispatch(
                dest(cfg), DocType.LABEL, "x".getBytes(),
                new DispatchContext(1L, 1, "ACME", null, "test.zpl")));

        assertFalse(instrumented.knownHostsCalled.get(),
                "setKnownHosts must NOT be invoked when knownHostsSecretId is absent");
        Properties props = instrumented.lastSessionProps.get();
        assertEquals("no", props.getProperty("StrictHostKeyChecking"),
                "legacy self-signed customer drops keep working — checking stays off");
    }
}
