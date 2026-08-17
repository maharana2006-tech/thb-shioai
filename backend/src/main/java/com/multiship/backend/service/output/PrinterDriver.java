package com.multiship.backend.service.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ClientOutputDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * Sprint 52 — {@link DestinationType#PRINTER} driver. Two protocols:
 *
 * <ul>
 *   <li><b>RAW_9100</b>: dumb TCP socket. Standard for Zebra ZPL — the
 *       printer accepts a stream of ZPL commands and starts printing as
 *       bytes arrive. No handshake, no ACK. 10s connect + write timeout.</li>
 *   <li><b>IPP</b>: HTTP POST to the printer's IPP endpoint (typically
 *       {@code http://host:631/printers/{queue}}) with a minimal
 *       {@code Print-Job} operation and the payload as the document.
 *       Uses {@link HttpURLConnection} to avoid pulling in a new dep;
 *       the payload is sent as-is and the printer picks up
 *       {@code application/pdf} / {@code application/octet-stream} from
 *       the {@code Content-Type} header.</li>
 * </ul>
 *
 * <p>Both paths honour a 10s network timeout and never buffer the whole
 * payload again in memory — the socket / URLConnection stream is written
 * to directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrinterDriver implements OutputDriver {

    static final int  NETWORK_TIMEOUT_MS = 10_000;
    static final int  DEFAULT_RAW_PORT   = 9100;
    static final int  DEFAULT_IPP_PORT   = 631;

    static final String PROTO_RAW_9100 = "RAW_9100";
    static final String PROTO_IPP      = "IPP";

    private final ObjectMapper objectMapper;

    @Override
    public DestinationType supports() {
        return DestinationType.PRINTER;
    }

    @Override
    public void dispatch(ClientOutputDestination destination,
                         DocType docType,
                         byte[] payload,
                         DispatchContext ctx) {
        OutputConfig.PrinterConfig cfg =
                OutputConfig.parse(objectMapper, destination.getConfig(), OutputConfig.PrinterConfig.class);
        if (cfg.getHost() == null || cfg.getHost().isBlank()) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "PRINTER destination " + destination.getId() + " has no 'host' set");
        }
        String protocol = cfg.getProtocol() == null ? "" : cfg.getProtocol().toUpperCase();
        switch (protocol) {
            case PROTO_RAW_9100 -> dispatchRaw(destination, cfg, payload);
            case PROTO_IPP      -> dispatchIpp(destination, cfg, docType, payload);
            default -> throw new OutputDeliveryException(destination.getId(), supports(),
                    "PRINTER protocol must be RAW_9100 or IPP; got '" + cfg.getProtocol() + "'");
        }
        log.info("PRINTER delivered {} bytes to {}:{} (protocol={}, destination_id={}, doc={})",
                payload.length, cfg.getHost(),
                cfg.getPort() != null ? cfg.getPort() : defaultPort(protocol),
                protocol, destination.getId(), docType);
    }

    /** RAW_9100 — open TCP socket, write bytes, close. Bounded by 10s timeout. */
    private void dispatchRaw(ClientOutputDestination destination,
                             OutputConfig.PrinterConfig cfg,
                             byte[] payload) {
        int port = cfg.getPort() != null ? cfg.getPort() : DEFAULT_RAW_PORT;
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(NETWORK_TIMEOUT_MS);
            socket.connect(new InetSocketAddress(cfg.getHost(), port), NETWORK_TIMEOUT_MS);
            try (OutputStream os = socket.getOutputStream()) {
                os.write(payload);
                os.flush();
            }
        } catch (Exception ex) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "RAW_9100 socket write failed to " + cfg.getHost() + ":" + port
                            + " — " + ex.getMessage(), ex);
        }
    }

    /**
     * IPP — HTTP POST the raw document. Not a full RFC 8010 IPP client
     * (we don't build an IPP operation packet); many modern print servers
     * (CUPS, Zebra ZebraNet) accept a plain HTTP POST with the document
     * body when the queue is configured for raw / auto-detect. If a
     * customer's queue rejects this, they can front the printer with
     * CUPS and configure the queue as {@code raw} or use LOCAL_FS +
     * a lpr sidecar. Documented in the PR body.
     */
    private void dispatchIpp(ClientOutputDestination destination,
                             OutputConfig.PrinterConfig cfg,
                             DocType docType,
                             byte[] payload) {
        int port = cfg.getPort() != null ? cfg.getPort() : DEFAULT_IPP_PORT;
        String queue = cfg.getQueueName();
        String path = queue == null || queue.isBlank()
                ? "/ipp/print"
                : (queue.startsWith("/") ? queue : "/printers/" + queue);
        String urlStr = "http://" + cfg.getHost() + ":" + port + path;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type",
                    docType == DocType.LABEL ? "application/pdf" : "application/pdf");
            conn.setRequestProperty("Content-Length", Integer.toString(payload.length));
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                throw new OutputDeliveryException(destination.getId(), supports(),
                        "IPP POST returned HTTP " + status + " from " + urlStr);
            }
        } catch (OutputDeliveryException ode) {
            throw ode;
        } catch (Exception ex) {
            throw new OutputDeliveryException(destination.getId(), supports(),
                    "IPP POST failed to " + urlStr + " — " + ex.getMessage(), ex);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int defaultPort(String protocol) {
        return PROTO_RAW_9100.equals(protocol) ? DEFAULT_RAW_PORT : DEFAULT_IPP_PORT;
    }
}
