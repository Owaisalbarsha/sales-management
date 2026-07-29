package com.salesmanagement.reporting.internal.config;

import java.time.ZoneId;

/**
 * Compiled-in configuration for the reporting module: the fixed business zone, and the keys +
 * safe defaults for the two runtime-tunable report parameters.
 *
 * <p><strong>Two tiers, on purpose (locked decision).</strong> A value that is fixed at deployment
 * and effectively never changes is a constant here; a value a manager might reasonably want to tune
 * without a redeploy is a {@code systemconfig} key read through {@code ConfigFacade}. The business
 * zone is the former — a distributor's timezone is set once — so it is a constant, not a config key,
 * and {@code systemconfig} stays {@code INT}-only (no {@code STRING} value-type was added just to
 * house a value that never moves). The top-N and aging-days thresholds are the latter.</p>
 *
 * <p><strong>The keys are not seeded.</strong> Matching the systemconfig override pattern, these keys
 * have no row until an admin creates one; until then {@code ConfigFacade.getInt(key, default)} returns
 * the default below. They are documented here so they are discoverable without reading the source.</p>
 */
public final class ReportingConfig {

    private ReportingConfig() {}

    /**
     * The business timezone, fixed at deployment. All reporting date-window conversions
     * ({@code Instant}/offset param → business {@code LocalDate}) resolve through this zone, so every
     * report agrees on "what calendar day is this". Damascus, UTC+3 — matching the system's
     * {@code +03:00} client offsets.
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Damascus");

    /**
     * systemconfig key: how many products count as "fast-moving" (top-N) and "slow-moving" (bottom-N)
     * in the FR-123 classification. Ranked by units sold. Tunable because a manager might reasonably
     * want top-15 instead of top-10.
     */
    public static final String KEY_FAST_SLOW_TOP_N = "REPORTING_FAST_SLOW_TOP_N";

    /** Compiled-in fallback for {@link #KEY_FAST_SLOW_TOP_N}. */
    public static final int DEFAULT_FAST_SLOW_TOP_N = 10;

    /**
     * systemconfig key: the number of days with no outbound movement after which a product is flagged
     * "aging" / dead-stock in the aging report. Tunable per how a warehouse defines staleness.
     */
    public static final String KEY_STOCK_AGING_DAYS = "REPORTING_STOCK_AGING_DAYS";

    /** Compiled-in fallback for {@link #KEY_STOCK_AGING_DAYS}. */
    public static final int DEFAULT_STOCK_AGING_DAYS = 30;
}
