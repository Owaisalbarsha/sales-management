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

    // ── Window-level roll-up (dashboard) ──────────────────────────────────────

    /**
     * The whole window's field execution collapsed to one set of counts — the dashboard's route
     * outcome chart.
     *
     * <p><strong>Why it lives here and not in the dashboard service.</strong> The interesting part of
     * this calculation is not the addition, it is deciding what COMPLETED / MISSED / IN_PROGRESS mean
     * and how an unmarked planned stop is classified. That judgement already exists in
     * {@link #routePerformance}, and having a second copy in another service is how two screens end up
     * disagreeing about the same day. So the semantics stay in one file and this method folds them.</p>
     *
     * <p><strong>Why it re-derives instead of summing {@code routePerformance} rows.</strong> That
     * method resolves a rep name per route — cheap for a report of a few dozen routes, but a
     * per-route identity lookup the dashboard has no use for at all. This path needs no names, so it
     * takes the same two batch reads (routes in range, then their visits in one query) and skips the
     * name resolution entirely: two queries for any window, regardless of how many routes it holds.</p>
     *
     * @param range half-open business-date window
     * @param repId optional rep filter; {@code null} for all reps
     */
    public com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RouteOutcomes routeOutcomes(
            DateRange range, Long repId) {

        List<RouteInfo> routes = routingFacade.findRoutesInRange(range.from(), range.to(), repId);
        if (routes.isEmpty()) {
            return new com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RouteOutcomes(
                    0, 0, 0, 0, 0, BigDecimal.ZERO.setScale(1));
        }

        Map<Long, List<VisitInfo>> visitsByRoute = visitFacade
                .findVisitsByRouteIds(routes.stream().map(RouteInfo::id).toList())
                .stream()
                .collect(Collectors.groupingBy(VisitInfo::routeId));

        long planned = 0, completed = 0, missed = 0, inProgress = 0, notVisited = 0;
        for (RouteInfo route : routes) {
            List<VisitInfo> visits = visitsByRoute.getOrDefault(route.id(), List.of());
            int p = route.stops() == null ? 0 : route.stops().size();
            int c = (int) visits.stream().filter(v -> "COMPLETED".equals(v.status())).count();
            int m = (int) visits.stream().filter(v -> "MISSED".equals(v.status())).count();
            int ip = (int) visits.stream().filter(v -> "IN_PROGRESS".equals(v.status())).count();

            planned += p;
            completed += c;
            missed += m;
            inProgress += ip;
            // Clamped per route, exactly as routePerformance does: an ad-hoc visit to a customer who
            // was not a planned stop would otherwise push this negative and make the slices unusable.
            notVisited += Math.max(0, p - (c + m + ip));
        }

        BigDecimal completionPercent = planned == 0
                ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(completed)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(planned), 1, RoundingMode.HALF_UP);

        return new com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RouteOutcomes(
                planned, completed, missed, inProgress, notVisited, completionPercent);
    }
}
