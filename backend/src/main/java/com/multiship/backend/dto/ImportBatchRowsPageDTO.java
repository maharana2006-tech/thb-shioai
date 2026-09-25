package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One page of a saved import's rows, filtered and searched by the server —
 * what the batch page shows, instead of every row of the batch at once.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatchRowsPageDTO {
    /** The rows of this page, in row order. */
    private List<OrderImportRowDTO> rows;
    /** Rows matching the view and search, over every page. */
    private long total;
    /** Every row of the batch. */
    private long all;
    /** Rows with errors or a failed label ("Needs attention"). */
    private long attention;
    /** Rows without a live label ("Not labelled"). */
    private long pending;
    /** Every client code in the batch, for the ship via panel. */
    private List<String> clientCodes;
}
