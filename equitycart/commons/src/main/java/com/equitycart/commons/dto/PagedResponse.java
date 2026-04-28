package com.equitycart.commons.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T> (
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),       // List<T> — the actual results
                page.getNumber(),        // current page number (0-based)
                page.getSize(),          // page size requested
                page.getTotalElements(), // total rows across all pages
                page.getTotalPages(),    // ceil(totalElements / size)
                page.isLast()            // is this the final page?
        );
    }
}
