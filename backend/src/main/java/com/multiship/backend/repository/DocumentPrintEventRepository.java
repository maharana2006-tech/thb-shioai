package com.multiship.backend.repository;

import com.multiship.backend.model.DocumentPrintEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentPrintEventRepository extends JpaRepository<DocumentPrintEvent, Long> {

    /** [orderNo, last printed at] for each of these orders that has been printed. */
    @Query("SELECT e.orderNo, MAX(e.printedAt) FROM DocumentPrintEvent e WHERE e.orderNo IN :orderNos GROUP BY e.orderNo")
    List<Object[]> lastPrintedByOrder(@Param("orderNos") Collection<Integer> orderNos);

    /** [label batch id, last printed at] — any order of the label batch printed. */
    @Query(value = """
            SELECT lb.batch_id, MAX(e.printed_at)
              FROM document_print_event e
              JOIN label_batch lb ON lb.order_no = e.order_no
             WHERE lb.batch_id IN (:batchIds)
             GROUP BY lb.batch_id
            """, nativeQuery = true)
    List<Object[]> lastPrintedByLabelBatch(@Param("batchIds") Collection<Integer> batchIds);
}
