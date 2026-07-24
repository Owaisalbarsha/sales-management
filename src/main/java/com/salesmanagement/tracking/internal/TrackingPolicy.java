package com.salesmanagement.tracking.internal;

import java.time.Duration;

/**
 * The two time thresholds the tracking module enforces, in one place.
 *
 * <p>Both are hard-coded constants today. When the {@code systemconfig} module lands they become
 * config keys read through {@code ConfigFacade}, and this class becomes the fallback-default
 * holder rather than the source of truth. Keeping them here now means that migration touches one
 * file instead of hunting literals through a service and a broadcaster.</p>
 *
 * <p>Note what is deliberately <em>absent</em>: a capture-interval constant. FR-55's 60-second
 * default is client behaviour. The server is a passive sink and never rejects a point for arriving
 * sooner than expected (D24), so it has no reason to know the interval. When {@code systemconfig}
 * exists it will serve that value <em>down</em> to the device; it will not gate ingestion.</p>
 */
public final class TrackingPolicy {

    /**
     * How recent a rep's last fix must be for the live map to call them active (D26).
     *
     * <p>Fifteen minutes is fifteen missed captures at the default interval. Shorter and reps flap
     * between states every time they walk into a building; longer and the map keeps showing
     * someone who went off shift an hour ago as live. FR-61 asks for the locations of <em>active</em>
     * reps, so a threshold is required, not optional.</p>
     *
     * <p>Stale reps are flagged, not filtered out. A marker that disappears tells a manager
     * nothing; a greyed marker says "this is where contact was lost".</p>
     */
    public static final Duration ACTIVE_WINDOW = Duration.ofMinutes(15);

    /**
     * How far into the future a capture instant may sit before it is rejected (D22).
     *
     * <p>Device clocks drift, and a phone that has been offline for hours may not have resynced
     * with network time. Five minutes absorbs ordinary skew while still refusing points that are
     * plainly wrong. Anything beyond this is a 400: accepting it would poison the dedup key
     * {@code UNIQUE(representative_id, recorded_at)} with a timestamp no genuine later fix can ever
     * follow, and would park a phantom marker at the top of the live map indefinitely.</p>
     */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private TrackingPolicy() {
        // constants only
    }
}
