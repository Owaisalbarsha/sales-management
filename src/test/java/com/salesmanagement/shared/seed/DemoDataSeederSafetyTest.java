package com.salesmanagement.shared.seed;

import com.salesmanagement.reporting.internal.config.ReportingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things about the seeder that must never regress: it cannot run outside development, and it
 * agrees with reporting about what day it is.
 *
 * <p>Everything else about the seeder is checked by running it. These are the properties where
 * "we would have noticed" is not true — a seeder that quietly became production-eligible would look
 * exactly like one that had not, right up until someone deployed it.</p>
 */
class DemoDataSeederSafetyTest {

    /**
     * The default context — no {@code local}/{@code dev} profile, no {@code app.seed.enabled} — must
     * not contain a seeder or any of its contributors.
     *
     * <p>This is the test that matters most in the whole seeding feature. It asserts absence, which
     * is awkward to test and easy to skip, and it is the only automated statement that the demo data
     * cannot reach a real database. If someone later widens {@code @Profile} or drops the
     * {@code @ConditionalOnProperty}, this fails immediately rather than at deploy time.</p>
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class ProductionSafety {

        @Autowired
        ApplicationContext context;

        @Test
        @DisplayName("no seeder bean exists outside a local/dev profile with seeding switched on")
        void seederAbsentByDefault() {
            assertThat(context.getBeansOfType(DemoDataSeeder.class))
                    .as("DemoDataSeeder must not exist in a non-development context")
                    .isEmpty();

            assertThat(context.getBeansOfType(DemoDataContributor.class))
                    .as("no demo-data contributor may exist in a non-development context")
                    .isEmpty();
        }
    }

    /**
     * Even under the {@code test} profile, merely switching the property on must not be enough — the
     * profile lock has to hold on its own.
     */
    @Nested
    @SpringBootTest(properties = {"app.seed.enabled=true", "app.seed.reset=true"})
    @ActiveProfiles("test")
    class PropertyAloneIsNotEnough {

        @Autowired
        ApplicationContext context;

        @Test
        @DisplayName("app.seed.enabled=true alone does not create the seeder outside local/dev")
        void propertyWithoutProfileDoesNothing() {
            assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
            assertThat(context.getBeansOfType(DemoDataContributor.class)).isEmpty();
        }
    }

    /** Plain unit tests — no Spring context needed. */
    @Nested
    class WindowSemantics {

        @Test
        @DisplayName("the seeder's business zone matches reporting's, so 'today' means the same day")
        void businessZoneMatchesReporting() {
            // Reporting owns the authoritative constant but lives in a module shared may not import,
            // so the value is duplicated. This is the guard against the two drifting apart: if they
            // did, seeded "today" invoices could land on the wrong side of the dashboard's day
            // boundary for several hours out of every day.
            assertThat(SeedWindow.BUSINESS_ZONE).isEqualTo(ReportingConfig.BUSINESS_ZONE);
        }

        @Test
        @DisplayName("the window ends today and exposes the half-open analytics bound")
        void windowEndsToday() {
            SeedWindow window = SeedWindow.untilToday();

            assertThat(window.start()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(window.today()).isEqualTo(LocalDate.now(SeedWindow.BUSINESS_ZONE));
            assertThat(window.exclusiveEnd()).isEqualTo(window.today().plusDays(1));
        }

        @Test
        @DisplayName("every month from January to the current one is covered, the last one partial")
        void coversEveryMonth() {
            SeedWindow window = SeedWindow.of(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 23));

            List<YearMonth> months = window.months();

            assertThat(months).hasSize(8);
            assertThat(months.get(0)).isEqualTo(YearMonth.of(2026, 1));
            assertThat(months.get(7)).isEqualTo(YearMonth.of(2026, 8));

            // The current month stops at today, not at the end of the calendar month.
            assertThat(window.workDaysOf(YearMonth.of(2026, 8)))
                    .allSatisfy(d -> assertThat(d).isBeforeOrEqualTo(LocalDate.of(2026, 8, 23)));
        }

        @Test
        @DisplayName("working days are Sunday to Thursday, so the trend has a weekly rhythm")
        void workingWeekIsSyrian() {
            SeedWindow window = SeedWindow.of(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

            assertThat(window.workDays()).allSatisfy(d ->
                    assertThat(d.getDayOfWeek())
                            .isNotIn(java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY));
            assertThat(window.workDays()).isNotEmpty();
            assertThat(window.allDays()).hasSize(31);
        }

        @Test
        @DisplayName("the random stream is fixed, and independent per purpose")
        void randomnessIsDeterministicAndIndependent() {
            SeedContext first = new SeedContext(SeedWindow.untilToday(), false);
            SeedContext second = new SeedContext(SeedWindow.untilToday(), false);

            // Same purpose, two runs -> identical sequence. This is what makes a seeded dashboard
            // comparable between restarts instead of a new set of numbers every time.
            assertThat(first.random("invoices").nextInt(1_000_000))
                    .isEqualTo(second.random("invoices").nextInt(1_000_000));

            // Different purposes must not share a stream, so adding a contributor cannot shift
            // everyone else's data.
            assertThat(first.random("invoices").nextInt(1_000_000))
                    .isNotEqualTo(first.random("routes").nextInt(1_000_000));
        }
    }
}
