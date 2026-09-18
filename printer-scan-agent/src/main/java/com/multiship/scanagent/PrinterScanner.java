package com.multiship.scanagent;

import com.multiship.scanagent.model.DiscoveredRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mDNS scan for network printers. Runs on the customer LAN — requires
 * host networking under Docker ({@code --network host}) because the
 * default bridge does not forward multicast.
 *
 * <p>Guesses {@code connectionGuess} + {@code formatGuess} from the
 * service type + TXT records; the backend leaves the row incomplete and
 * the admin picker fills the gaps before promoting to a real printer.
 */
public class PrinterScanner {

    private static final Logger log = LoggerFactory.getLogger(PrinterScanner.class);

    static final String TYPE_IPP = "_ipp._tcp.local.";
    static final String TYPE_IPPS = "_ipps._tcp.local.";
    static final String TYPE_PDL = "_pdl-datastream._tcp.local.";
    static final String TYPE_PRINTER = "_printer._tcp.local.";

    private static final String[] SERVICE_TYPES = {TYPE_IPP, TYPE_IPPS, TYPE_PDL, TYPE_PRINTER};

    public List<DiscoveredRow> scan(Duration timeout) throws Exception {
        Map<String, DiscoveredRow> byHostPort = new LinkedHashMap<>();
        try (JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost())) {
            for (String type : SERVICE_TYPES) {
                ServiceInfo[] infos = jmdns.list(type, timeout.toMillis());
                for (ServiceInfo info : infos) {
                    DiscoveredRow row = toRow(info, type);
                    if (row.host() == null || row.port() == null) continue;
                    byHostPort.putIfAbsent(row.host() + ":" + row.port(), row);
                }
            }
        }
        log.info("mDNS scan found {} printers across {} service types",
                byHostPort.size(), SERVICE_TYPES.length);
        return new ArrayList<>(byHostPort.values());
    }

    static DiscoveredRow toRow(ServiceInfo info, String type) {
        String host = firstInet4(info);
        Integer port = info.getPort();
        String name = info.getName();
        String queuePath = txt(info, "rp");
        String location = txt(info, "note");
        String pdl = txt(info, "pdl");

        String connectionGuess;
        switch (type) {
            case TYPE_PDL -> {
                connectionGuess = "RAW_9100";
                if (port == null || port <= 0) port = 9100;
            }
            case TYPE_IPP, TYPE_IPPS -> connectionGuess = "IPP";
            default -> connectionGuess = null;
        }

        String formatGuess = null;
        if (pdl != null) {
            String lower = pdl.toLowerCase();
            if (lower.contains("zpl")) formatGuess = "ZPL";
            else if (lower.contains("pdf")) formatGuess = "PDF";
        }

        StringBuilder raw = new StringBuilder();
        Enumeration<String> propNames = info.getPropertyNames();
        while (propNames.hasMoreElements()) {
            String k = propNames.nextElement();
            raw.append(k).append('=').append(info.getPropertyString(k)).append('\n');
        }

        return new DiscoveredRow(host, port, name, location, connectionGuess,
                formatGuess, null, queuePath, raw.length() == 0 ? null : raw.toString());
    }

    private static String firstInet4(ServiceInfo info) {
        if (info.getInet4Addresses().length > 0) return info.getInet4Addresses()[0].getHostAddress();
        if (info.getHostAddresses().length > 0) return info.getHostAddresses()[0];
        return null;
    }

    private static String txt(ServiceInfo info, String key) {
        String v = info.getPropertyString(key);
        return (v == null || v.isBlank()) ? null : v;
    }
}
