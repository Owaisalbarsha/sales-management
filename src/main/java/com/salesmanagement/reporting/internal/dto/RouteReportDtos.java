package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed JSON response models for the route performance reports (FR-125/126/127). Same convention as
 * the other report DTOs.
 */
public final class RouteReportDtos {

    private RouteReportDtos() {}

    /**
     * One row per route: planned stops vs actual outcomes, with a completion rate.
     *
     * <p>Planned = the count of stops on the route (from {@code RouteInfo.stops}). Completed / missed
     * come from the visits for that route. {@code inProgress} counts visits still open (a route not yet
     * finalised). {@code notVisited} = planned − (completed + missed + inProgress): stops with no visit
     * row at all — distinct from MISSED, which is an explicit end-of-day outcome. Completion rate is
     * completed ÷ planned as a percentage.</p>
     */
    public record RoutePerformanceRow(
            Long       routeId,
            String     routeName,
            LocalDate  date,
            Long       representativeId,
            String     representativeName,
            String     status,
            int        plannedStops,
            int        completed,
            int        missed,
            int        inProgress,
            int        notVisited,
            BigDecimal completionRatePercent
    ) {}

    /**
     * FR-127: one row per missed stop — the actionable "who didn't get visited" list, flatter than the
     * per-route summary. Customer resolved to a name.
     */
    public record MissedVisitRow(
            Long      routeId,
            String    routeName,
            LocalDate date,
            Long      representativeId,
            String    representativeName,
            Long      customerId,
            String    customerName
    ) {}

    public record RouteEnvelope<T>(
            LocalDate from,
            LocalDate to,
            List<T>   rows
    ) {}
}
