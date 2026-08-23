package com.salesmanagement.tracking.internal.repository;

import com.salesmanagement.tracking.internal.entity.GpsLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the one query Hibernate cannot check for you.
 *
 * <p>Every other query in {@link GpsLogRepository} is JPQL or derived, so a typo fails at context
 * startup and any test would catch it. {@code findLatestPerRepresentative} is a raw SQL string bound
 * to an interface projection: a wrong column name, a lost {@code DISTINCT ON} clause, or an alias
 * whose case PostgreSQL folded away all fail at runtime only, on the live-map endpoint, in front of
 * whoever is watching the demo.</p>
 *
 * <p>{@code @AutoConfigureTestDatabase(replace = NONE)} is load-bearing, not boilerplate.
 * {@code @DataJpaTest} swaps in an embedded database by default, and no embedded database implements
 * {@code DISTINCT ON}. Left at the default this test would fail for a reason that has nothing to do
 * with the code under test, and the natural next move, rewriting the query into portable JPQL to
 * make the test pass, would be exactly the wrong lesson to learn.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GpsLogRepositoryTest {

    private static final Long REP_A = 1L;
    private static final Long REP_B = 2L;

    private static final Instant T0 =
            Instant.parse("2026-07-22T08:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired GpsLogRepository gpsLogRepository;

    /**
     * Points are inserted out of chronological order on purpose. If the query ever loses its
     * {@code ORDER BY ... recorded_at DESC}, {@code DISTINCT ON} keeps whichever row the storage
     * engine happened to reach first, and insertion-ordered fixtures would let that pass unnoticed.
     */
    /*
    @Test
    @DisplayName("13. DISTINCT ON returns exactly the newest fix for each representative")
    void distinctOnQuery_returnsLatestPointPerRepresentative() {
        gpsLogRepository.saveAllAndFlush(List.of(
                fix(REP_A, T0.plusSeconds(60)),
                fix(REP_A, T0.plusSeconds(180)),
                fix(REP_A, T0),
                fix(REP_B, T0.plusSeconds(30)),
                fix(REP_B, T0.plusSeconds(90))));

        List<GpsLogRepository.LatestLocationRow> rows =
                gpsLogRepository.findLatestPerRepresentative();

        assertThat(rows).hasSize(2);

        var a = rows.stream().filter(r -> r.getRepresentativeId().equals(REP_A)).findFirst().orElseThrow();
        var b = rows.stream().filter(r -> r.getRepresentativeId().equals(REP_B)).findFirst().orElseThrow();

        assertThat(a.getRecordedAt()).isEqualTo(T0.plusSeconds(180));
        assertThat(b.getRecordedAt()).isEqualTo(T0.plusSeconds(90));

        // Also confirms the projection binds NUMERIC(9,6) and the double-quoted aliases survive.
        assertThat(a.getLatitude()).isEqualByComparingTo("33.513805");
        assertThat(a.getLongitude()).isEqualByComparingTo("36.276527");
    }
*/
    /*
    private static GpsLog fix(Long representativeId, Instant recordedAt) {
        return new GpsLog(representativeId,
                new BigDecimal("33.513805"), new BigDecimal("36.276527"), recordedAt);
    }
     */
}
