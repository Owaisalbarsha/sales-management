package com.salesmanagement.shared.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The historical period the demo seeder fills, and the calendar helpers every contributor uses to
 * walk it.
 *
 * <p><strong>The end of the window is "today", resolved once.</strong> Not hardcoded: the seeder is
 * meant to stay useful when run months from now, and a fixed end date would silently stop producing
 * current-month and today activity — which is exactly the data the dashboard tiles read. Every
 * contributor takes the window from here rather than calling {@code LocalDate.now()} itself, so a
 * seeding run that straddles midnight cannot half-populate one day and half another.</p>
 *
 * <p><strong>Business zone.</strong> "Today" is resolved in {@link #BUSINESS_ZONE}, mirroring the
 * reporting module's business zone — the dashboard decides which calendar day an invoice belongs to
 * using that zone, so seeding "today" in any other zone would put the seeded invoices on the wrong
 * side of a date boundary for several hours a day. Reporting owns the authoritative constant; this
 * one is asserted equal to it by {@code DemoSeedBusinessZoneTest} so the two cannot drift apart
 * unnoticed.</p>
 */
public final class SeedWindow {

    /**
     * The business timezone. Must equal {@code ReportingConfig.BUSINESS_ZONE} — that class is the
     * authority and lives inside the reporting module, which shared may not import. A test pins the
     * equality.
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Damascus");

    /** First business day of seeded history. The company "opened" here. */
    public static final LocalDate DEFAULT_START = LocalDate.of(2026, 1, 1);

    /**
     * The Syrian working week: Sunday through Thursday. Routes, visits and van loading happen on
     * these days only, which is what gives the daily trend its natural weekly rhythm instead of a
     * flat line — a chart with no weekends looks synthetic at a glance.
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

    /** The window from {@link #DEFAULT_START} through today (inclusive), in the business zone. */
    public static SeedWindow untilToday() {
        return new SeedWindow(DEFAULT_START, LocalDate.now(BUSINESS_ZONE));
    }

    /** Explicit window — used by tests that need a fixed, non-drifting period. */
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
     * convention — i.e. {@code today + 1}. Handy for logging the analytics range that covers the
     * whole seeded period.
     */
    public LocalDate exclusiveEnd() {
        return today.plusDays(1);
    }

    /** Every calendar day in the window, inclusive of both ends. */
    public List<LocalDate> allDays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /** Every working day (Sun–Thu) in the window. */
    public List<LocalDate> workDays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            if (WORK_DAYS.contains(d.getDayOfWeek())) {
                out.add(d);
            }
        }
        return out;
    }

    /** Whether the given date is a working day. */
    public static boolean isWorkDay(LocalDate date) {
        return WORK_DAYS.contains(date.getDayOfWeek());
    }

    /** Every month touched by the window, ascending. The last one is usually partial. */
    public List<YearMonth> months() {
        List<YearMonth> out = new ArrayList<>();
        YearMonth last = YearMonth.from(today);
        for (YearMonth m = YearMonth.from(start); !m.isAfter(last); m = m.plusMonths(1)) {
            out.add(m);
        }
        return out;
    }

    /** Working days of one month, clipped to the window (so the current month stops at today). */
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

    /** Zero-based index of a month within the window — the x-axis of every seasonal curve here. */
    public int monthIndex(YearMonth month) {
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(start), month);
    }

    @Override
    public String toString() {
        return start + " -> " + today + " (analytics exclusive end " + exclusiveEnd() + ")";
    }
}
