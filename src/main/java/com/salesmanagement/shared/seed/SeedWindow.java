package com.salesmanagement.shared.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The historical period the demo seeder fills, and the calendar helpers every contributor uses to
 * walk it.
 *
 * <p><strong>Two months, ending today.</strong> The window is a rolling {@link #HISTORY_DAYS}-day
 * span whose last day is the current business date. It is deliberately relative rather than
 * anchored to a fixed start: a hardcoded start date makes the dataset grow without bound as the
 * calendar moves past it, and eventually stops resembling recent trading at all. Sixty days is the
 * shortest span that still contains two monthly boundaries, several complete Sun-Thu weeks, and a
 * dormancy period long enough for the churn report to have real subjects.</p>
 *
 * <p><strong>The end of the window is "today", resolved once.</strong> Every contributor takes the
 * window from here rather than calling {@code LocalDate.now()} itself, so a seeding run that
 * straddles midnight cannot half-populate one day and half another.</p>
 *
 * <p><strong>Business zone.</strong> "Today" is resolved in {@link #BUSINESS_ZONE}, mirroring the
 * reporting module's business zone - the dashboard decides which calendar day an invoice belongs to
 * using that zone, so seeding "today" in any other zone would put the seeded invoices on the wrong
 * side of a date boundary for several hours a day. Reporting owns the authoritative constant; this
 * one is asserted equal to it by {@code DemoDataSeederSafetyTest} so the two cannot drift apart
 * unnoticed.</p>
 */
public final class SeedWindow {

    /**
     * The business timezone. Must equal {@code ReportingConfig.BUSINESS_ZONE} - that class is the
     * authority and lives inside the reporting module, which shared may not import. A test pins the
     * equality.
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Damascus");

    /** Length of seeded history, in calendar days, inclusive of today. */
    public static final int HISTORY_DAYS = 60;

    /**
     * The Syrian working week: Sunday through Thursday. Routes, visits and van loading happen on
     * these days only, which is what gives the daily trend its natural weekly rhythm instead of a
     * flat line - a chart with no weekends looks synthetic at a glance.
     */
    private static final List<DayOfWeek> WORK_DAYS = List.of(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);

    private final LocalDate start;
    private final LocalDate today;

    private SeedWindow(LocalDate start, LocalDate today) {
        this.start = start;
        this.today = today;
    }

    /** The rolling {@link #HISTORY_DAYS}-day window ending today (inclusive), in the business zone. */
    public static SeedWindow untilToday() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return new SeedWindow(today.minusDays(HISTORY_DAYS - 1L), today);
    }

    /** Explicit window - used by tests that need a fixed, non-drifting period. */
    public static SeedWindow of(LocalDate start, LocalDate today) {
        return new SeedWindow(start, today);
    }

    /** Inclusive first day of seeded history. */
    public LocalDate start() {
        return start;
    }

    /** The current business date; the last day that receives seeded activity (inclusive). */
    public LocalDate today() {
        return today;
    }

    /**
     * The exclusive upper bound matching the project's half-open {@code [from, to)} report
     * convention - i.e. {@code today + 1}.
     */
    public LocalDate exclusiveEnd() {
        return today.plusDays(1);
    }

    /** Whether the given date is the window's current business date. */
    public boolean isToday(LocalDate date) {
        return today.equals(date);
    }

    /** Whether the given date falls inside the window, both ends inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(today);
    }

    /** Every calendar day in the window, inclusive of both ends. */
    public List<LocalDate> allDays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /** Every working day (Sun-Thu) in the window, today included if it is one. */
    public List<LocalDate> workDays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            if (WORK_DAYS.contains(d.getDayOfWeek())) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Every working day strictly before today - the days whose field work is finished.
     *
     * <p>The distinction matters more than it looks. A route dated in the past may not be left
     * PLANNED or ACTIVE: the nightly {@code VisitSweepJob} finalises every such route at 02:00, so
     * seeding one produces a row the application itself rewrites overnight. Contributors that write
     * finished documents walk this list; contributors that write live, open documents use
     * {@link #today()} alone.</p>
     */
    public List<LocalDate> pastWorkDays() {
        return workDays().stream().filter(d -> d.isBefore(today)).toList();
    }

    /** Whether the given date is a working day. */
    public static boolean isWorkDay(LocalDate date) {
        return WORK_DAYS.contains(date.getDayOfWeek());
    }

    /** Every month touched by the window, ascending. The first and last are usually partial. */
    public List<YearMonth> months() {
        List<YearMonth> out = new ArrayList<>();
        YearMonth last = YearMonth.from(today);
        for (YearMonth m = YearMonth.from(start); !m.isAfter(last); m = m.plusMonths(1)) {
            out.add(m);
        }
        return out;
    }

    /** Working days of one month, clipped to the window at both ends. */
    public List<LocalDate> workDaysOf(YearMonth month) {
        LocalDate from = month.atDay(1).isBefore(start) ? start : month.atDay(1);
        LocalDate to = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkDay(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Zero-based index of a month within the window. */
    public int monthIndex(YearMonth month) {
        return (int) ChronoUnit.MONTHS.between(YearMonth.from(start), month);
    }

    /**
     * Zero-based index of a day within the window; {@code 0} is {@link #start()}.
     * The x-axis of every trend curve in the seeders.
     */
    public int dayIndex(LocalDate date) {
        return (int) ChronoUnit.DAYS.between(start, date);
    }

    /**
     * Zero-based week index within the window.
     *
     * <p>Over sixty days a per-<em>month</em> seasonal curve has only two or three points to work
     * with, which is not enough to shape a trend - every day inside a month would draw the identical
     * multiplier and the daily chart would come out as two flat plateaus with a step between them.
     * The weekly index gives roughly nine points instead, so the trend has visible movement while
     * each individual week stays internally coherent.</p>
     */
    public int weekIndex(LocalDate date) {
        return dayIndex(date) / 7;
    }

    /** Total weeks the window spans, rounded up. */
    public int weekCount() {
        return (HISTORY_DAYS + 6) / 7;
    }

    @Override
    public String toString() {
        return start + " -> " + today + " (" + allDays().size() + " days, "
                + workDays().size() + " working; analytics exclusive end " + exclusiveEnd() + ")";
    }
}
