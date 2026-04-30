package com.equitycart.commons.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Generic paginated response wrapper for REST APIs. Converts Spring Data's {@link Page} into a
 * clean, framework-agnostic DTO. Reusable across all modules (products, orders, users).
 *
 * @param <T> the type of content items
 */
public record PagedResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
  public static <T> PagedResponse<T> from(Page<T> page) {
    return new PagedResponse<>(
        page.getContent(), // List<T> — the actual results
        page.getNumber(), // current page number (0-based)
        page.getSize(), // page size requested
        page.getTotalElements(), // total rows across all pages
        page.getTotalPages(), // ceil(totalElements / size)
        page.isLast() // is this the final page?
        );
  }
}
