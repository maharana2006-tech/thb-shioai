package com.multiship.backend.service.printing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands {@link PrinterAddressGuard} what it can only learn from configuration.
 *
 * <ul>
 *   <li>{@code server.port} — this server's own HTTP port, which is never a printer.</li>
 *   <li>{@code multiship.printers.allow-loopback} — lets a printer live on
 *       127.0.0.1, for a fake printer while developing. Off by default and
 *       logged loudly when on: in production it reopens the hole the guard closes.</li>
 * </ul>
 */
@Component
class PrinterAddressGuardConfig {

    private static final Logger log = LoggerFactory.getLogger(PrinterAddressGuardConfig.class);

    PrinterAddressGuardConfig(@Value("${server.port:8080}") int serverPort,
                              @Value("${multiship.printers.allow-loopback:false}") boolean allowLoopback) {
        PrinterAddressGuard.setOwnServerPort(serverPort);
        PrinterAddressGuard.setAllowLoopback(allowLoopback);
        if (allowLoopback) {
            log.warn("multiship.printers.allow-loopback=true — printers may point at this server itself. "
                    + "Development only; never enable this in production.");
        }
    }
}
