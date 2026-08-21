package com.salesmanagement.territory.internal.service;

import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.territory.internal.entity.Territory;
import com.salesmanagement.territory.internal.dto.CreateTerritoryRequest;
import com.salesmanagement.territory.internal.dto.TerritoryResponse;
import com.salesmanagement.territory.internal.dto.UpdateTerritoryRequest;
import com.salesmanagement.territory.internal.repository.TerritoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for the territory module.
 *
 * <p>Owns all territory operations. Controllers delegate here; this class is the
 * only writer of the {@link Territory} entity. All mutating methods are
 * transactional; reads are marked {@code readOnly} for clarity and a minor
 * Hibernate flush-mode optimisation.</p>
 *
 * <p>Error contract (all via {@link BusinessException}, mapped to HTTP by
 * {@code GlobalExceptionHandler}):</p>
 * <ul>
 *   <li>409 CONFLICT — duplicate territory name</li>
 *   <li>404 NOT_FOUND — territory id does not exist</li>
 *   <li>409 CONFLICT — attempt to delete a territory that still has customers</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TerritoryService {

    private final TerritoryRepository territoryRepository;

    /**
     * Creates a new territory after verifying the name is unique.
     *
     * @param request validated creation request
     * @return the created territory as a response DTO
     * @throws BusinessException 409 if a territory with the same name exists
     */
    @Transactional
    public TerritoryResponse create(CreateTerritoryRequest request) {
        if (territoryRepository.existsByName(request.name())) {
            throw BusinessException.conflict(
                    "A territory named '" + request.name() + "' already exists",
                    "TERRITORY_NAME_EXISTS");
        }
        Territory saved = territoryRepository.save(
                new Territory(request.name(), request.description()));
        log.info("Created territory id={} name='{}'", saved.getId(), saved.getName());
        return TerritoryResponse.from(saved);
    }

    /**
     * Returns one territory by id.
     *
     * @param id the territory id
     * @return the territory as a response DTO
     * @throws BusinessException 404 if no territory has this id
     */
    public TerritoryResponse getById(Long id) {
        return TerritoryResponse.from(findOrThrow(id));
    }

    /**
     * Returns a page of territories.
     *
     * @param pageable pagination/sort, built from the request's {@code PageRequest}
     * @return a page of territory response DTOs in the unified page envelope
     */
    public PageResponse<TerritoryResponse> list(String q,
                                                boolean all,
                                                Pageable pageable) {

        String normalized = (q == null || q.isBlank()) ? null : q.trim();

        Page<Territory> page;

        if (all) {
            // Return all territories in a synthetic Page
            List<TerritoryResponse> allItems = territoryRepository.findAll()
                    .stream()
                    .map(TerritoryResponse::from)
                    .toList();

            Page<TerritoryResponse> synthetic = new PageImpl<>(
                    allItems,
                    Pageable.unpaged(),
                    allItems.size()
            );

            return PageResponse.of(synthetic);
        }

        // Normal paginated search
        page = (normalized == null)
                ? territoryRepository.findAll(pageable)
                : territoryRepository.findByNameContainingIgnoreCase(normalized, pageable);

        return PageResponse.of(page.map(TerritoryResponse::from));
    }


    /**
     * Fully updates an existing territory.
     *
     * @param id      the territory id to update
     * @param request validated update request
     * @return the updated territory as a response DTO
     * @throws BusinessException 404 if the id does not exist;
     *                           409 if the new name collides with another territory
     */
    @Transactional
    public TerritoryResponse update(Long id, UpdateTerritoryRequest request) {
        if (request.name() == null && request.description() == null) {
            throw BusinessException.badRequest(
                    "At least one field must be provided", "EMPTY_UPDATE");
        }

        Territory territory = findOrThrow(id);

        if (request.name() != null) {
            if (territoryRepository.existsByNameAndIdNot(request.name(), id)) {
                throw BusinessException.conflict(
                        "A territory named '" + request.name() + "' already exists",
                        "TERRITORY_NAME_EXISTS");
            }
            territory.setName(request.name());
        }

        if (request.description() != null) {
            territory.setDescription(request.description());
        }

        log.info("Updated territory id={}", id);
        return TerritoryResponse.from(territory);
    }

    /**
     * Deletes a territory.
     *
     * <p>A territory that still has customers cannot be deleted: the
     * {@code CUSTOMER.territory_id} foreign key rejects it at the database level.
     * We deliberately do <em>not</em> ask the customer module how many customers
     * exist — that would invert the module dependency. Instead we let the DB
     * enforce integrity and translate the resulting violation into a clean 409.</p>
     *
     * @param id the territory id to delete
     * @throws BusinessException 404 if the id does not exist;
     *                           409 if the territory still has customers assigned
     */
    @Transactional
    public void delete(Long id) {
        Territory territory = findOrThrow(id);
        try {
            territoryRepository.delete(territory);
            territoryRepository.flush(); // force the FK check now, inside this try
        } catch (DataIntegrityViolationException ex) {
            log.warn("Refused to delete territory id={} — still referenced by customers", id);
            throw BusinessException.conflict(
                    "Cannot delete a territory that still has customers assigned to it",
                    "TERRITORY_HAS_CUSTOMERS");
        }
        log.info("Deleted territory id={}", id);
    }

    /**
     * Loads a territory or throws a 404. Single source of the not-found message
     * so every operation reports it identically.
     */
    private Territory findOrThrow(Long id) {
        return territoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Territory not found: " + id, "TERRITORY_NOT_FOUND"));
    }
}