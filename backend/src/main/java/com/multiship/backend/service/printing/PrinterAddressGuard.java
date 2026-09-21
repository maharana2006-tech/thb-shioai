package com.multiship.backend.service.printing;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;

/**
 * Where a "printer" is allowed to live.
 *
 * <p>Printers sit on the warehouse LAN, so private addresses (10.x, 172.16.x,
 * 192.168.x) have to be allowed — a blanket SSRF block would make the feature
 * useless. What is refused is everything that is never a printer and is
 * valuable to reach from inside the server:
 * <ul>
 *   <li>loopback (127.0.0.0/8, ::1) — the server's own database, cache and API;</li>
 *   <li>link-local (169.254.0.0/16, fe80::/10) — cloud metadata at 169.254.169.254
 *       hands out credentials;</li>
 *   <li>the unspecified address (0.0.0.0) and multicast;</li>
 *   <li>well-known service ports (Postgres, Redis, SSH…) on any host, plus the
 *       server's own HTTP port.</li>
 * </ul>
 *
 * <p>Without this an admin could register "127.0.0.1:5432" as a printer, and
 * the test print's "could not reach" versus "connected" answer made it a port
 * scanner for the server's own network. A USER could then send label ZPL —
 * which carries customer-typed text — into any of those services.
 *
 * <p>Checked when a printer is saved (a clear message) and again on every
 * connection, because a hostname can resolve somewhere else later.
 */
public final class PrinterAddressGuard {

    private PrinterAddressGuard() { }

    /** Ports that belong to a service, never to a printer. */
    private static final Map<Integer, String> SERVICE_PORTS = Map.ofEntries(
            Map.entry(22, "SSH"), Map.entry(23, "Telnet"), Map.entry(25, "SMTP"), Map.entry(53, "DNS"),
            Map.entry(110, "POP3"), Map.entry(143, "IMAP"), Map.entry(389, "LDAP"), Map.entry(445, "SMB"),
            Map.entry(1433, "SQL Server"), Map.entry(1521, "Oracle"), Map.entry(2375, "Docker"),
            Map.entry(2376, "Docker"), Map.entry(2379, "etcd"), Map.entry(3306, "MySQL"),
            Map.entry(3389, "Remote Desktop"), Map.entry(5432, "PostgreSQL"), Map.entry(5672, "RabbitMQ"),
            Map.entry(6379, "Redis"), Map.entry(9200, "Elasticsearch"), Map.entry(9300, "Elasticsearch"),
            Map.entry(11211, "Memcached"), Map.entry(27017, "MongoDB"));

    /** Test sockets and a local fake printer live on loopback; production never does. */
    private static volatile boolean allowLoopback = false;

    /** This server's own HTTP port — never a printer. 0 until configured. */
    private static volatile int ownServerPort = 0;

    static void setAllowLoopback(boolean allow) {
        allowLoopback = allow;
    }

    static void setOwnServerPort(int port) {
        ownServerPort = port;
    }

    /**
     * Why this address may not be used as a printer, or empty when it may.
     * A hostname that doesn't resolve yet is allowed: the printer may be on a
     * network the server can't see at save time, and a send will fail on its own.
     */
    public static Optional<String> refusal(String host, int port) {
        String service = SERVICE_PORTS.get(port);
        if (service != null) {
            return Optional.of("Port " + port + " is used by " + service + ", not a printer. "
                    + "Label printers use 9100; office printers use 631 (IPP).");
        }
        if (ownServerPort > 0 && port == ownServerPort) {
            return Optional.of("Port " + port + " is this server's own port, not a printer.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException notYet) {
            return Optional.empty();
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() && !allowLoopback) {
                return Optional.of(host + " is this server itself, not a printer. Use the printer's own network address.");
            }
            if (a.isAnyLocalAddress()) {
                return Optional.of(host + " is not a real address. Use the printer's own network address.");
            }
            if (a.isLinkLocalAddress()) {
                return Optional.of(host + " is a link-local address (169.254.x.x) — that range holds the cloud "
                        + "metadata service, not printers. Give the printer a normal LAN address.");
            }
            if (a.isMulticastAddress()) {
                return Optional.of(host + " is a multicast address, not a printer.");
            }
        }
        return Optional.empty();
    }

    /** Throws when the address is refused — called right before every connection. */
    static void check(String host, int port) throws IOException {
        Optional<String> why = refusal(host, port);
        if (why.isPresent()) throw new IOException(why.get());
    }
}
