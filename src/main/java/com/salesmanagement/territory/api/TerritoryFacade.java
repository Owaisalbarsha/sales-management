package com.salesmanagement.territory.api;

import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.territory.internal.entity.Territory;
import com.salesmanagement.territory.internal.repository.TerritoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API surface of the territory module — the only type other modules may
 * import from territory. Mirrors {@code UserFacade}.
 *
 * <p>Deliberately read-only and minimal: the {@code customer} module needs to
 * validate that a territory exists when assigning a customer to it, and to read a
 * territory's name/description for display. It does not need write access, so the
 * facade exposes none. All mutation goes through the REST API.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TerritoryFacade {

    private final TerritoryRepository territoryRepository;

    /**
     * Returns the public projection of a territory.
     *
     * @param territoryId the territory id
     * @return the territory info
     * @throws BusinessException 404 if no territory has this id
     */
    public TerritoryInfo getTerritoryInfo(Long territoryId) {
        Territory t = territoryRepository.findById(territoryId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Territory not found: " + territoryId, "TERRITORY_NOT_FOUND"));
        return new TerritoryInfo(t.getId(), t.getName(), t.getDescription());
    }

    /**
     * Whether a territory with the given id exists. The {@code customer} module
     * calls this before assigning a customer to a territory, so it can reject a
     * bad {@code territoryId} with its own domain error rather than failing on a
     * foreign-key violation at insert time.
     *
     * @param territoryId the territory id to check
     * @return {@code true} if the territory exists
     */
    public boolean exists(Long territoryId) {
        return territoryRepository.existsById(territoryId);
    }
}