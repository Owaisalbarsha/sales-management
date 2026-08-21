package com.salesmanagement.visit.internal.dto;

import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.enums.VisitStatus;

import java.time.Instant;

/**
 * Response projection of a {@link Visit}, returned by every visit endpoint.
 *
 * <p>Enriched with {@code customerName} and {@code representativeName} (resolved via
 * {@code CustomerFacade}/{@code UserFacade}) so the mobile app and dashboard do not need a
 * second call per visit. Enrichment is null-graceful: a deleted customer or user yields a
 * {@code null} name rather than a failed response.</p>
 */
public record VisitResponse(
        Long          id,
        Long          routeId,
        String        routeName,
        Long          customerId,
        String        customerName,
        Long          representativeId,
        String        representativeName,
        VisitStatus   status,
        Instant       checkInTime,
        String        checkInLocation,
        Instant       checkOutTime,
        String        checkOutLocation,
        Instant       createdAt,
        Instant       updatedAt
)  {
    /**
     * Maps a {@link Visit} to its response projection.
     *
     * @param v                  the visit to map
     * @param customerName       resolved customer name (may be {@code null} if unknown)
     * @param representativeName resolved rep name (may be {@code null} if unknown)
     */
    public static VisitResponse from(Visit v, String routeName, String customerName, String representativeName) {
        return new VisitResponse(
                v.getId(),
                v.getRouteId(),
                routeName,
                v.getCustomerId(),
                customerName,
                v.getRepresentativeId(),
                representativeName,
                v.getStatus(),
                v.getCheckInTime(),
                v.getCheckInLocation(),
                v.getCheckOutTime(),
                v.getCheckOutLocation(),
                v.getCreatedAt(),
                v.getUpdatedAt()
        );
    }
}
