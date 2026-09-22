package com.multiship.backend.service.printing;

import com.multiship.backend.model.DocumentPrintEvent;
import com.multiship.backend.repository.DocumentPrintEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which orders' documents were printed — opened in the print dialog
 * or sent to a network printer. Bulk Mailer shows it ("Printed 22 Sep").
 * Recording never breaks a print: a failed write is logged and dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPrintLog {

    public static final String BROWSER = "BROWSER";
    public static final String PRINTER = "PRINTER";

    private final DocumentPrintEventRepository repository;

    public void record(Collection<Integer> orderNos, String docType, String channel, String printerName, String user) {
        if (orderNos == null || orderNos.isEmpty()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            List<DocumentPrintEvent> rows = orderNos.stream().distinct().map(no -> {
                DocumentPrintEvent e = new DocumentPrintEvent();
                e.setOrderNo(no);
                e.setDocType(docType);
                e.setChannel(channel);
                e.setPrinterName(printerName);
                e.setPrintedBy(user);
                e.setPrintedAt(now);
                return e;
            }).toList();
            repository.saveAll(rows);
        } catch (RuntimeException ex) {
            log.warn("Print log: could not record {} {} print(s): {}", orderNos.size(), docType, ex.getMessage());
        }
    }

    /** When each of these orders was last printed (orders never printed are absent). */
    public Map<Integer, LocalDateTime> lastPrintedByOrder(Collection<Integer> orderNos) {
        Map<Integer, LocalDateTime> out = new HashMap<>();
        if (orderNos == null || orderNos.isEmpty()) return out;
        for (Object[] r : repository.lastPrintedByOrder(orderNos)) out.put((Integer) r[0], toTime(r[1]));
        return out;
    }

    /** When anything of each label batch was last printed. */
    public Map<Integer, LocalDateTime> lastPrintedByLabelBatch(Collection<Integer> batchIds) {
        Map<Integer, LocalDateTime> out = new HashMap<>();
        if (batchIds == null || batchIds.isEmpty()) return out;
        for (Object[] r : repository.lastPrintedByLabelBatch(batchIds)) {
            out.put(((Number) r[0]).intValue(), toTime(r[1]));
        }
        return out;
    }

    private static LocalDateTime toTime(Object v) {
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
