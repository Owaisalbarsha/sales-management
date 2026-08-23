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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GPS breadcrumbs for the reps who are in the field today.
 *
 * <h2>Deliberately small</h2>
 * A real fleet emits a point every thirty seconds, which over eight months and eight reps is on the
 * order of two million rows — minutes of seeding time and a heavy local database, in exchange for
 * nothing: no dashboard tile and no analytics chart reads GPS. So this seeds only today, only for
 * reps who are actually out, and only enough points to draw a plausible trail on the live map. The
 * historical record simply does not pretend to have tracking, which is more honest than a thin fake
 * one that would make trail queries look fast when they would not be.
 *
 * <p>Points walk outward from the Damascus centre along a rep-specific bearing, so two reps do not
 * sit on top of each other, and the trail moves in a direction rather than jittering in place. Every
 * coordinate is a real position inside the city — never {@code 0,0}, which would place the fleet in
 * the Gulf of Guinea and quietly break any distance calculation.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class TrackingDemoData implements DemoDataContributor {

    private static final double DAMASCUS_LAT = 33.5138;
    private static final double DAMASCUS_LNG = 36.2765;

    /** Points per rep across the working day — a trail, not a telemetry feed. */
    private static final int POINTS_PER_REP = 12;

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

        List<Long> repsInField = context.routes().stream()
                .filter(r -> r.date().equals(today))
                .map(SeedRoute::representativeId)
                .distinct()
                .toList();

        if (repsInField.isEmpty()) {
            log.info("No routes today, so no tracking points seeded");
            return;
        }

        Random random = context.random("tracking");
        List<GpsLog> batch = new ArrayList<>();

        for (int repIndex = 0; repIndex < repsInField.size(); repIndex++) {
            Long repId = repsInField.get(repIndex);

            // Skip a rep who already has a trail today, so a re-run does not double it.
            if (!gpsLogRepository.findAll().stream()
                    .filter(g -> g.getRepresentativeId().equals(repId))
                    .filter(g -> g.getRecordedAt().atZone(SeedWindow.BUSINESS_ZONE)
                            .toLocalDate().equals(today))
                    .toList().isEmpty()) {
                continue;
            }

            double bearing = (2 * Math.PI * repIndex) / Math.max(1, repsInField.size());

            for (int point = 0; point < POINTS_PER_REP; point++) {
                double distance = 0.004 + point * 0.0025;
                double lat = DAMASCUS_LAT + Math.cos(bearing) * distance
                        + (random.nextDouble() - 0.5) * 0.001;
                double lng = DAMASCUS_LNG + Math.sin(bearing) * distance
                        + (random.nextDouble() - 0.5) * 0.001;

                Instant recordedAt = today
                        .atTime(LocalTime.of(8, 0).plusMinutes(point * 35L))
                        .atZone(SeedWindow.BUSINESS_ZONE)
                        .toInstant();

                batch.add(new GpsLog(repId, coordinate(lat), coordinate(lng), recordedAt));
            }
        }

        gpsLogRepository.saveAll(batch);
        context.count("tracking points", batch.size());
        log.info("Demo tracking ready: {} GPS points for {} representatives in the field today",
                batch.size(), repsInField.size());
    }

    private BigDecimal coordinate(double raw) {
        return BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP);
    }
}
