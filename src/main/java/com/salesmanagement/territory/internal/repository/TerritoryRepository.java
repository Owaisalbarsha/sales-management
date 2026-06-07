package com.salesmanagement.territory.internal.repository;

import com.salesmanagement.territory.internal.Territory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Territory}.
 *
 * <p>Internal to the territory module. No other module may inject this — they go
 * through {@code TerritoryFacade} instead. {@code JpaRepository} provides the full
 * CRUD and pagination surface; the only addition is a uniqueness check used by the
 * service to give a clean 409 before hitting the database constraint.</p>
 */
public interface TerritoryRepository extends JpaRepository<Territory, Long> {

    /**
     * Whether a territory with the given name already exists. Used by the service
     * to reject duplicate names with a friendly {@code CONFLICT} before the
     * database's unique constraint would throw a less informative error.
     *
     * @param name the candidate name
     * @return {@code true} if a territory with this name exists
     */
    boolean existsByName(String name);

    /**
     * Whether a <em>different</em> territory (id ≠ the one being updated) already
     * uses the given name. Used during update so a territory keeping its own name
     * is not flagged as a duplicate of itself.
     *
     * @param name the candidate name
     * @param id   the id of the territory being updated (excluded from the check)
     * @return {@code true} if another territory already uses this name
     */
    boolean existsByNameAndIdNot(String name, Long id);
}