package com.salesmanagement.shared.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unified pagination request that every list endpoint accepts as query params.
 *
 * Usage in a controller:
 *   GET /api/customers?page=0&size=20&sortBy=name&sortDir=asc
 *
 *   public ApiResponse<PageResponse<CustomerResponse>> list(
 *       @Valid PageRequest pageRequest) { ... }
 *
 * Converts to Spring's Pageable via toPageable() — module services never
 * construct Pageable themselves, they receive it from the controller layer.
 *
 * Constraints:
 * - page  >= 0         (zero-indexed, consistent with Spring Data)
 * - size  1..100       (hard cap at 100 prevents accidental full-table dumps)
 * - sortDir must be "asc" or "desc" (validated in toPageable())
 *
 * Default sortBy is "id" — always a valid column, gives stable ordering.
 * Modules that need a different default override it at the controller level
 * before calling toPageable().
 */
@Getter
@Setter
public class PageRequest {

    @Min(value = 0, message = "page must be 0 or greater")
    private int page = 0;

    @Min(value = 1,   message = "size must be at least 1")
    @Max(value = 100, message = "size must not exceed 100")
    private int size = 20;

    private String sortBy  = "id";
    private String sortDir = "asc";

    /**
     * Converts this request into a Spring Data {@link Pageable}.
     * sortDir is normalised to lowercase and validated; anything that is not
     * "desc" falls back to ascending to avoid injection via the sort direction.
     */
    public Pageable toPageable() {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return org.springframework.data.domain.PageRequest.of(
                page,
                size,
                Sort.by(direction, sortBy)
        );
    }
}