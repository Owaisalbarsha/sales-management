package com.salesmanagement.reporting.internal.support;

import com.salesmanagement.shared.exception.BusinessException;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * The bucket size of a dashboard trend, and the only place that decides which calendar bucket a
 * business date belongs to.
 *
 * <p><strong>Bucketing is done here, not in SQL, on purpose.</strong> Every trend on the dashboard is
 * built from a single per-day aggregate query and then folded into DAY / WEEK / MONTH buckets in
 * memory. Pushing the grouping into the database instead would mean three variants of every aggregate
 * query (or database-specific date-truncation functions) in three different modules, all of which
 * would have to agree on where a week starts. Folding a handful of daily rows costs nothing and keeps
 * one definition of a bucket.</p>
 *
 * <p><strong>Week = ISO week, starting Monday.</strong> Deterministic and locale-independent: the same
 * date always lands in the same bucket regardless of the server's locale, which a
 * {@code WeekFields.of(Locale.getDefault())} would not guarantee. The bucket is labelled by its start
 * date — its Monday — and for MONTH by the first of the month.</p>
 *
 * <p><strong>A bucket may start before the requested window.</strong> Asking for a WEEK trend from a
 * Wednesday returns a first bucket labelled with that week's Monday, holding only the days from
 * Wednesday on. That is the honest reading: the bucket is named by the period it belongs to, while the
 * figures inside it only ever cover the range the caller asked for.</p>
 */
public enum Granularity {

    DAY,
    WEEK,
    MONTH;

    /**
     * Parses the request parameter, defaulting to {@link #DAY} when absent or blank.
     *
     * @throws BusinessException 400 for an unrecognised value — a silent fallback to DAY would hand
     *         back a chart at a different resolution than the one asked for, with nothing to say so
     */
    public static Granularity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DAY;
        }
        try {
            return Granularity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(
                    "Unknown granularity '" + raw + "'; expected one of DAY, WEEK, MONTH.",
                    "REPORT_GRANULARITY_INVALID");
        }
    }

    /** The start date of the bucket the given business date falls into. */
    public LocalDate bucketStart(LocalDate date) {
        return switch (this) {
            case DAY   -> date;
            case WEEK  -> date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    /** The start of the bucket following the one starting at {@code bucketStart}. */
    public LocalDate nextBucket(LocalDate bucketStart) {
        return switch (this) {
            case DAY   -> bucketStart.plusDays(1);
            case WEEK  -> bucketStart.plusWeeks(1);
            case MONTH -> bucketStart.plusMonths(1);
        };
    }

    /**
     * Every bucket start covering the half-open window {@code [from, to)}, ascending — the skeleton a
     * trend is zero-filled onto so a chart shows silent periods as zero rather than as a gap.
     *
     * <p>The first bucket is the one containing {@code from} (so it may start earlier than
     * {@code from}, see the class note), and buckets are emitted while their start is before
     * {@code to}. An empty or inverted window yields an empty list; the caller has already rejected
     * inverted ranges via {@link DateRangeResolver}.</p>
     */
    public List<LocalDate> buckets(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (from == null || to == null || !from.isBefore(to)) {
            return out;
        }
        for (LocalDate cursor = bucketStart(from); cursor.isBefore(to); cursor = nextBucket(cursor)) {
            out.add(cursor);
        }
        return out;
    }
}
