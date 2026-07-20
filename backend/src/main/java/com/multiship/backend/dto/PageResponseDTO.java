package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponseDTO<T> {
    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
    private boolean empty;
    private String sortBy;
    private String sortDirection;

    // Method with 4 parameters (without sort)
    public static <T> PageResponseDTO<T> of(List<T> content, int pageNumber, int pageSize, long totalElements) {
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);

        return PageResponseDTO.<T>builder()
                .content(content)
                .pageNumber(pageNumber)
                .pageSize(pageSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(pageNumber == 0)
                .last(pageNumber >= totalPages - 1 || totalPages == 0)
                .empty(content.isEmpty())
                .build();
    }

    // Method with 6 parameters (with sort)
    public static <T> PageResponseDTO<T> of(List<T> content, int pageNumber, int pageSize, long totalElements,
                                            String sortBy, String sortDirection) {
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);

        return PageResponseDTO.<T>builder()
                .content(content)
                .pageNumber(pageNumber)
                .pageSize(pageSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(pageNumber == 0)
                .last(pageNumber >= totalPages - 1 || totalPages == 0)
                .empty(content.isEmpty())
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();
    }
}