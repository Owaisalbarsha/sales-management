package com.salesmanagement.systemconfig.api;

import com.salesmanagement.systemconfig.internal.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Public API surface of the {@code systemconfig} module — the only type other modules import to read
 * a runtime setting. Delegates to {@code SystemConfigService}; no logic lives here.
 *
 * <p><strong>Read-only.</strong> The other read facades ({@code UserFacade}, {@code RoutingFacade},
 * {@code VisitFacade}) are read-only and that is the right default; only {@code TrackingFacade}
 * writes, because {@code sync} needs a synchronous pass/fail from it. Nothing calls systemconfig to
 * change a value — admins do that over HTTP — so there is no write method to expose.</p>
 *
 * <p><strong>Override semantics.</strong> Every getter takes the caller's own code default and
 * returns it when the key has no override row. This is what makes the table an override store rather
 * than a source of truth: a fresh or wiped DB is a valid state, and each consumer keeps its safe
 * fallback compiled in (e.g. tracking's {@code TrackingPolicy.ACTIVE_WINDOW}). An admin edit takes
 * effect on the next call — there is no cache to invalidate and no restart (v1 does not cache; the
 * only consumers read on dashboard/map load, never on a hot path).</p>
 *
 * <p><strong>Fail-safe on bad data.</strong> If an override row exists but its stored value cannot be
 * parsed as an int, the typed getter returns the code default rather than throwing. A malformed value
 * should have been rejected at write time (the service validates against {@code value_type}); this is
 * defence in depth so a hand-edited row can never take down a consuming module's read path.</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigFacade {

    private final SystemConfigService systemConfigService;

    /**
     * The overridden int for a key, or empty if the key has no override row. Prefer
     * {@link #getInt(String, int)} in consumers — this raw form exists for the rare caller that must
     * distinguish "unset" from "set to the default value".
     *
     * @return the parsed override, or empty if unset or unparseable
     */
    public Optional<Integer> getInt(String key) {
        return systemConfigService.getInt(key);
    }

    /**
     * The overridden int for a key, or {@code codeDefault} if the key is unset or its stored value is
     * unparseable. This is the method consumers call.
     *
     * @param key         a {@link ConfigKey} constant
     * @param codeDefault the caller's compiled-in fallback (its safe default)
     */
    public int getInt(String key, int codeDefault) {
        return systemConfigService.getInt(key).orElse(codeDefault);
    }
}
