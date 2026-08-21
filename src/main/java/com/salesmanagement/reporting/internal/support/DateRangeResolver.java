package com.salesmanagement.reporting.internal.support;

import com.salesmanagement.reporting.internal.config.ReportingConfig;
import com.salesmanagement.shared.exception.BusinessException;

import java.time.LocalDate;

/**
 * Normalises and validates the {@code (from, to)} date window every ranged report takes, and returns
 * it as a half-open {@code [from, to)} pair (D9).
 *
 * <p><strong>Why dates, not instants.</strong> Every column these reports filter on
 * ({@code invoice_date}, {@code order_date}, {@code return_date}, {@code route_date}) is a
 * {@code LocalDate} — a business calendar date, not a timestamp. So the endpoints take calendar dates
 * directly and no {@code Instant}/offset conversion is needed on the query path; the business zone
 * ({@link ReportingConfig#BUSINESS_ZONE}) is used only to resolve "today" when defaulting an open-ended
 * window, so "today" means today in Damascus, not on the server's clock.</p>
 *
 * <p><strong>Half-open contract.</strong> The returned {@code to} is treated as <em>exclusive</em> by
 * every repository query ({@code date >= from and date < to}). A caller asking for "January" passes
 * {@code from = Jan 1, to = Feb 1}; no invoice dated Feb 1 leaks in, and no month-edge double counting
 * occurs when windows are chained. This resolver does NOT shift {@code to} — the exclusivity lives in
 * the SQL — it only validates and defaults.</p>
 */
public final class DateRangeResolver {

    /** Default window length when the caller supplies neither bound: the last {@value} days. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private DateRangeResolver() {}

    /**
     * A validated half-open date window.
     *
     * @param from inclusive start
     * @param to   exclusive end ({@code from < to} guaranteed)
     */
    public record DateRange(LocalDate from, LocalDate to) {}

    /**
     * Resolves the raw request bounds into a valid half-open window, applying defaults for missing
     * bounds and rejecting an inverted range.
     *
     * <p>Defaulting rules:
     * <ul>
     *   <li>both null → {@code [today - 30d, today + 1d)} (the last 30 days, inclusive of today)</li>
     *   <li>only {@code from} null → {@code [to - 30d, to)}</li>
     *   <li>only {@code to} null → {@code [from, today + 1d)} (from the given start through today)</li>
     * </ul>
     * "Today" is resolved in the business zone. {@code to} is exclusive throughout, which is why the
     * open-ended upper default is {@code today + 1d} — so today's own rows are included.</p>
     *
     * @throws BusinessException 400 if {@code from} is not strictly before the resolved {@code to}
     */
    public static DateRange resolve(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);

        LocalDate resolvedTo = (to != null)
                ? to
                : (from != null ? today.plusDays(1) : today.plusDays(1));

        LocalDate resolvedFrom = (from != null)
                ? from
                : resolvedTo.minusDays(DEFAULT_WINDOW_DAYS);

        if (!resolvedFrom.isBefore(resolvedTo)) {
            throw BusinessException.badRequest(
                    "Report window start (" + resolvedFrom + ") must be before end (" + resolvedTo
                            + "); note the end date is exclusive.",
                    "REPORT_DATE_RANGE_INVALID");
        }
        return new DateRange(resolvedFrom, resolvedTo);
    }
}
