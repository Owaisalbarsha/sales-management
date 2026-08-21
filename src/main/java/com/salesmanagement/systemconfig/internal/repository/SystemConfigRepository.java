package com.salesmanagement.systemconfig.internal.repository;

import com.salesmanagement.systemconfig.internal.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link SystemConfig}. The table is small (one row per overridden key), so there is
 * no paging and no dynamic query surface here — every access is either "one key by name" or "all
 * overrides" for the admin list view.
 *
 * <p>{@code findByConfigKey} returns {@link Optional} because a missing key is the normal state (the
 * consumer then falls back to its code default); it is not an error and must not throw.</p>
 */
public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /**
     * The override row for a key, or empty if the key has never been overridden.
     *
     * <p>Empty is a valid, expected result — do not treat it as not-found-error at the call site;
     * the facade converts it to the caller's code default.</p>
     */
    Optional<SystemConfig> findByConfigKey(String configKey);

    /**
     * True if an override row already exists for this key. Used by the service to decide insert-new
     * versus update-existing on an upsert without loading the entity.
     */
    boolean existsByConfigKey(String configKey);

    /**
     * All overrides, key-ordered, for the admin management list. Deterministic ordering so the
     * dashboard table is stable between loads.
     */
    List<SystemConfig> findAllByOrderByConfigKeyAsc();
}
