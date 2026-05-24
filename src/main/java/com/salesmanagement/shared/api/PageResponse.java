package com.salesmanagement.shared.api;

import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Unified pagination response envelope.
 *
 * Wraps Spring's {@link Page} into a stable, mobile-friendly JSON shape.
 * Spring's raw Page serialises to a massive JSON blob with Pageable internals
 * that clients don't need. This type controls exactly what goes over the wire.
 *
 * Wire shape:
 * {
 *   "content":       [ ... ],
 *   "page":          0,
 *   "size":          20,
 *   "totalElements": 543,
 *   "totalPages":    28,
 *   "first":         true,
 *   "last":          false
 * }
 *
 * Usage in a service/controller:
 *   Page<Customer> page = repo.findAll(pageable);
 *   return ApiResponse.ok(PageResponse.of(page.map(CustomerResponse::from)));
 */
@Getter
public final class PageResponse<T> {

    private final List<T> content;
    private final int     page;
    private final int     size;
    private final long    totalElements;
    private final int     totalPages;
    private final boolean first;
    private final boolean last;

    private PageResponse(Page<T> springPage) {
        this.content       = springPage.getContent();
        this.page          = springPage.getNumber();
        this.size          = springPage.getSize();
        this.totalElements = springPage.getTotalElements();
        this.totalPages    = springPage.getTotalPages();
        this.first         = springPage.isFirst();
        this.last          = springPage.isLast();
    }

    /**
     * Wrap any Spring {@link Page} into this response type.
     * The Page's content is already mapped to the desired DTO type T by the caller.
     */
    public static <T> PageResponse<T> of(Page<T> springPage) {
        return new PageResponse<>(springPage);
    }
}