package com.salesmanagement.tracking.internal.seed;

import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.SeedRoute;
import com.salesmanagement.shared.seed.SeedWindow;
import com.salesmanagement.tracking.internal.entity.GpsLog;
import com.salesmanagement.tracking.internal.repository.GpsLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * GPS breadcrumbs for the reps who are out in the field right now.
 *
 * <h2>Deliberately small</h2>
 * A real fleet emits a point every thirty seconds, which over two months and eight reps is on the
 * order of half a million rows - a slow seed and a heavy local database, in exchange for nothing:
 * no dashboard tile and no analytics chart reads GPS. So this seeds only today, only for reps who
 * are actually out, and only enough points to draw a plausible trail on the live map. The
 * historical record simply does not pretend to have tracking, which is more honest than a thin fake
 * one that would make trail queries look fast when they would not be.
 *
 * <h2>The trail ends now, and walks backwards from there</h2>
 * {@code TrackingService.record} rejects any {@code recordedAt} beyond a small clock skew, on the
 * grounds that a future fix would sit permanently at the top of the live map and never age out. An
 * earlier version laid points out forwards from 08:00 at fixed intervals, which was fine in the
 * afternoon and wrong every other time of day - seeding at breakfast wrote a full day of fixes that
 * had not happened yet, and the live map showed a fleet reporting from the future. The trail is
 * therefore anchored at the current instant and stepped backwards, so the newest point is always
 * "just now" and the oldest never precedes the start of the working day.
 *
 * <p>Only reps on an <strong>ACTIVE</strong> route get a trail. A rep whose route is still PLANNED
 * has not left the depot, and a device that is not out is not reporting.</p>
 *
 * <p>Points walk outward from the Damascus centre along a rep-specific bearing, so two reps do not
 * sit on top of each other, and the trail moves in a direction rather than jittering in place. Every
 * coordinate is a real position inside the city - never {@code 0,0}, which would place the fleet in
 * the Gulf of Guinea and quietly break any distance calculation.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TrackingDemoData implements DemoDataContributor {

    private static final double DAMASCUS_LAT = 33.5138;
    private static final double DAMASCUS_LNG = 36.2765;

    /** Points per rep across the working day - a trail, not a telemetry feed. */
    private static final int POINTS_PER_REP = 12;

    /** Gap between consecutive fixes on a seeded trail. */
    private static final Duration POINT_INTERVAL = Duration.ofMinutes(20);

    /** The earliest a device can have reported today. Matches the field day used by routing. */
    private static final LocalTime FIELD_DAY_START = LocalTime.of(8, 0);

    private final GpsLogRepository gpsLogRepository;

    @Override
    public int order() {
        return SeedOrder.TRACKING;
    }

    @Override
    public String label() {
        return "tracking points";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        LocalDate today = context.window().today();

        // Only reps who are genuinely out. A PLANNED route means the rep has not set off, so there
        // is no device in the field to report a position.
        List<Long> repsInField = context.routes().stream()
                .filter(r -> r.date().equals(today))
                .filter(r -> "ACTIVE".equals(r.status()))
                .map(SeedRoute::representativeId)
                .distinct()
                .toList();

        if (repsInField.isEmpty()) {
            log.info("No rep is out on an active route right now, so no tracking points seeded");
            return;
        }

        Instant now = Instant.now();
        Instant dayStart = today.atTime(FIELD_DAY_START).atZone(SeedWindow.BUSINESS_ZONE).toInstant();
        if (!now.isAfter(dayStart)) {
            log.info("The field day has not begun yet, so no tracking points seeded");
            return;
        }

        // How many fixes fit between the start of the day and now, capped at the trail length.
        long elapsedMinutes = Duration.between(dayStart, now).toMinutes();
        int points = (int) Math.min(POINTS_PER_REP, 1 + elapsedMinutes / POINT_INTERVAL.toMinutes());

        // One query for the whole check rather than one per rep.
        Set<Long> alreadyTrailed = new HashSet<>();
        gpsLogRepository.findAll().stream()
                .filter(g -> g.getRecordedAt().atZone(SeedWindow.BUSINESS_ZONE)
                        .toLocalDate().equals(today))
                .forEach(g -> alreadyTrailed.add(g.getRepresentativeId()));

        List<GpsLog> batch = new ArrayList<>();

        for (int repIndex = 0; repIndex < repsInField.size(); repIndex++) {
            Long repId = repsInField.get(repIndex);
            if (alreadyTrailed.contains(repId)) {
                continue;   // a re-run must not double the trail
            }

            // One stream per rep: a rep who already has a trail is skipped, and that skip must
            // not move the jitter of everyone after them.
            Random random = context.randomFor("tracking", repId);

            double bearing = (2 * Math.PI * repIndex) / Math.max(1, repsInField.size());

            // Oldest fix first, newest last: point index counts up, the offset back from now
            // counts down, so the final point lands within one interval of the present.
            for (int point = 0; point < points; point++) {
                int stepsBack = points - 1 - point;
                Instant recordedAt = now.minus(POINT_INTERVAL.multipliedBy(stepsBack));

                double distance = 0.004 + point * 0.0025;
                double lat = DAMASCUS_LAT + Math.cos(bearing) * distance
                        + (random.nextDouble() - 0.5) * 0.001;
                double lng = DAMASCUS_LNG + Math.sin(bearing) * distance
                        + (random.nextDouble() - 0.5) * 0.001;

                batch.add(new GpsLog(repId, coordinate(lat), coordinate(lng), recordedAt));
            }
        }

        gpsLogRepository.saveAll(batch);
        context.count("tracking points", batch.size());
        log.info("Demo tracking ready: {} GPS points ({} per rep) for {} representatives out on an "
                + "active route", batch.size(), points, repsInField.size());
    }

    /**
     * Deletes today's GPS trails so a reset rebuilds them against the current clock.
     *
     * <p>Bounded to today because that is the only day this contributor ever writes. A GPS log
     * carries no nullable column to stamp with a seed marker, so the date is the only handle - but
     * a trail is pure telemetry that nothing else references, so unlike a visit it cannot orphan
     * another module's rows.</p>
     */
    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        LocalDate today = context.window().today();
        List<GpsLog> doomed = gpsLogRepository.findAll().stream()
                .filter(g -> g.getRecordedAt().atZone(SeedWindow.BUSINESS_ZONE)
                        .toLocalDate().equals(today))
                .toList();
        gpsLogRepository.deleteAll(doomed);
        log.warn("Reset: deleted {} GPS points dated {}", doomed.size(), today);
    }

    private BigDecimal coordinate(double raw) {
        return BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP);
    }
}
