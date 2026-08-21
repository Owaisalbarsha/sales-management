package com.salesmanagement.systemconfig.api;

/**
 * The canonical names of every configurable key, in one place.
 *
 * <p>Lives in {@code api} because both sides of a config read reference it: the consuming module
 * (tracking passing {@code TRACKING_ACTIVE_WINDOW_MINUTES} to {@code ConfigFacade}) and the
 * systemconfig write path validating an incoming key. A literal string on either side is a typo
 * waiting to silently fall through to the code default forever, so the string exists exactly once,
 * here.</p>
 *
 * <p>Only keys that are actually read by a consumer belong here. Adding a constant for a setting
 * nothing reads recreates the dead-config trap this module was scoped to avoid — the key list grows
 * as real consumers land, not before.</p>
 */
public final class ConfigKey {

    /**
     * How recent (in minutes) a rep's last GPS fix must be for the live map to still call them
     * active. Read by tracking's live-map query via {@code ConfigFacade.getInt(..., 15)}; the code
     * default of 15 lives in {@code TrackingPolicy.ACTIVE_WINDOW} and is used whenever this key is
     * not overridden.
     */
    public static final String TRACKING_ACTIVE_WINDOW_MINUTES = "TRACKING_ACTIVE_WINDOW_MINUTES";

    private ConfigKey() {
        // constants only
    }
}
