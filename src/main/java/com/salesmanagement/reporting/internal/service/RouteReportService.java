package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.reporting.internal.dto.RouteReportDtos.MissedVisitRow;
import com.salesmanagement.reporting.internal.dto.RouteReportDtos.RoutePerformanceRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportTable;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.visit.api.VisitFacade;
import com.salesmanagement.visit.api.VisitInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the route performance reports (FR-125 planned-vs-actual, FR-126 completion rate, FR-127 missed
 * visits) by pairing the planned side (routing) with the actual side (visits).
 *
 * <p><strong>The composition (D1, and the correctness reason for the facade shapes).</strong> Routes in
 * the window come from {@code RoutingFacade.findRoutesInRange} — each carries its planned stops. Their
 * visits come from ONE {@code VisitFacade.findVisitsByRouteIds} batch over all route ids. Visits are
 * anchored by route id, not date, precisely so a MISSED visit (null check-in time) is not dropped —
 * which is the whole point of FR-127. Then it is arithmetic: group visits by route, count by status,
 * compare to planned.</p>
 *
 * <p>Rep names per-row (bounded); customer names (for the missed list) in one batch.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String UNRESOLVED = "—";

    private final RoutingFacade routingFacade;
    private final VisitFacade visitFacade;
    private final UserFacade userFacade;
    private final CustomerFacade customerFacade;

    // ── FR-125 / FR-126: per-route performance ────────────────────────────────

    /**
     * One performance row per route in the window: planned stops vs completed / missed / in-progress /
     * never-visited, with completion rate. Ordered by date then route.
     */
    public List<RoutePerformanceRow> routePerformance(DateRange range, Long repId) {
        List<RouteInfo> routes = routingFacade.findRoutesInRange(range.from(), range.to(), repId);
        if (routes.isEmpty()) {
            return List.of();
        }

        // All visits for these routes in one batch, grouped by route id.
        List<Long> routeIds = routes.stream().map(RouteInfo::id).toList();
        Map<Long, List<VisitInfo>> visitsByRoute = visitFacade.findVisitsByRouteIds(routeIds).stream()
                .collect(Collectors.groupingBy(VisitInfo::routeId));

        return routes.stream()
                .map(route -> {
                    List<VisitInfo> visits = visitsByRoute.getOrDefault(route.id(), List.of());
                    int planned = route.stops() == null ? 0 : route.stops().size();
                    int completed = (int) visits.stream().filter(v -> "COMPLETED".equals(v.status())).count();
                    int missed = (int) visits.stream().filter(v -> "MISSED".equals(v.status())).count();
                    int inProgress = (int) visits.stream().filter(v -> "IN_PROGRESS".equals(v.status())).count();
                    int notVisited = Math.max(0, planned - (completed + missed + inProgress));

                    return new RoutePerformanceRow(
                            route.id(), route.name(), route.date(),
                            route.representativeId(), safeUserName(route.representativeId()),
                            route.status(),
                            planned, completed, missed, inProgress, notVisited,
                            completionRate(completed, planned));
                })
                .toList();
    }

    public ReportTable routePerformanceTable(DateRange range, Long repId) {
        List<List<String>> rows = routePerformance(range, repId).stream()
                .map(r -> List.of(
                        r.date().format(DATE_FMT),
                        r.routeName(),
                        r.representativeName(),
                        String.valueOf(r.plannedStops()),
                        String.valueOf(r.completed()),
                        String.valueOf(r.missed()),
                        String.valueOf(r.notVisited()),
                        r.completionRatePercent().toPlainString() + "%"))
                .toList();
        return new ReportTable("تقرير أداء المسارات",  // "Route performance report"
                List.of("التاريخ", "المسار", "المندوب", "المخطط", "المكتمل", "الفائت", "غير المزار", "نسبة الإنجاز"),
                rows);
    }

    // ── FR-127: missed visits ─────────────────────────────────────────────────

    /**
     * Flat list of every MISSED visit in the window — the actionable per-stop view. Customer names
     * batch-resolved.
     */
    public List<MissedVisitRow> missedVisits(DateRange range, Long repId) {
        List<RouteInfo> routes = routingFacade.findRoutesInRange(range.from(), range.to(), repId);
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<Long, RouteInfo> routeById = routes.stream()
                .collect(Collectors.toMap(RouteInfo::id, r -> r));

        List<VisitInfo> missed = visitFacade.findVisitsByRouteIds(routeById.keySet()).stream()
                .filter(v -> "MISSED".equals(v.status()))
                .toList();

        Map<Long, String> customerNames = customerFacade.getNamesByIds(
                missed.stream().map(VisitInfo::customerId).collect(Collectors.toSet()));

        return missed.stream()
                .map(v -> {
                    RouteInfo route = routeById.get(v.routeId());
                    return new MissedVisitRow(
                            v.routeId(),
                            route != null ? route.name() : UNRESOLVED,
                            route != null ? route.date() : null,
                            v.representativeId(), safeUserName(v.representativeId()),
                            v.customerId(), customerNames.getOrDefault(v.customerId(), UNRESOLVED));
                })
                .toList();
    }

    public ReportTable missedVisitsTable(DateRange range, Long repId) {
        List<List<String>> rows = missedVisits(range, repId).stream()
                .map(r -> List.of(
                        r.date() == null ? "—" : r.date().format(DATE_FMT),
                        r.routeName(),
                        r.representativeName(),
                        r.customerName()))
                .toList();
        return new ReportTable("تقرير الزيارات الفائتة",  // "Missed visits report"
                List.of("التاريخ", "المسار", "المندوب", "العميل"),
                rows);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BigDecimal completionRate(int completed, int planned) {
        if (planned == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(planned), 1, RoundingMode.HALF_UP);
    }

    private String safeUserName(Long userId) {
        if (userId == null) {
            return UNRESOLVED;
        }
        try {
            String name = userFacade.getNameById(userId);
            return (name == null || name.isBlank()) ? UNRESOLVED : name;
        } catch (RuntimeException e) {
            return UNRESOLVED;
        }
    }
}
