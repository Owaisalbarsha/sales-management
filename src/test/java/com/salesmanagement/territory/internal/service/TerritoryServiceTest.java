package com.salesmanagement.territory.internal.service;

import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.territory.internal.dto.CreateTerritoryRequest;
import com.salesmanagement.territory.internal.dto.TerritoryResponse;
import com.salesmanagement.territory.internal.dto.UpdateTerritoryRequest;
import com.salesmanagement.territory.internal.entity.Territory;
import com.salesmanagement.territory.internal.repository.TerritoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TerritoryService.
 *
 * <p>
 * These tests focus on business logic inside the service layer.
 * Bean validation such as @NotBlank and @Size is intentionally tested
 * at the controller boundary, because CreateTerritoryRequest declares
 * that validation is enforced by @Valid before the service is called.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TerritoryServiceTest {

    @Mock
    private TerritoryRepository territoryRepository;

    @InjectMocks
    private TerritoryService territoryService;


    // ============================================================
    // CREATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Create Territory Tests")
    class CreateTests {

        /**
         * TR-01
         *
         * Positive:
         * Create territory with a valid unique name.
         */
        @Test
        @DisplayName("TR-01: Create territory successfully with valid unique name")
        void create_ValidUniqueName_ReturnsCreatedTerritory() {

            // Arrange
            String name = "North";
            String description = "North Region";

            CreateTerritoryRequest request =
                    new CreateTerritoryRequest(name, description);

            Territory savedTerritory =
                    new Territory(name, description);

            when(territoryRepository.existsByName(name))
                    .thenReturn(false);

            when(territoryRepository.save(any(Territory.class)))
                    .thenReturn(savedTerritory);

            // Act
            TerritoryResponse response =
                    territoryService.create(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.name()).isEqualTo(name);

            verify(territoryRepository)
                    .existsByName(name);

            verify(territoryRepository)
                    .save(any(Territory.class));
        }


        /**
         * TR-02
         *
         * Validation / business rule:
         * Duplicate territory name.
         */
        @Test
        @DisplayName("TR-02: Create territory with duplicate name throws conflict")
        void create_DuplicateName_ThrowsConflict() {

            // Arrange
            String name = "North";

            CreateTerritoryRequest request =
                    new CreateTerritoryRequest(
                            name,
                            "Another description"
                    );

            when(territoryRepository.existsByName(name))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.create(request)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists")
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NAME_EXISTS");

            // Important:
            // No second territory must be saved.
            verify(territoryRepository, never())
                    .save(any(Territory.class));
        }


        /**
         * AD-15
         *
         * Case-sensitive name rule as currently implemented
         * by the repository/service contract.
         */
        @Test
        @DisplayName("AD-15: Create 'North' then duplicate 'north' according to repository rule")
        void create_AD15_caseSensitivityRule() {

            // Arrange
            String firstName = "North";
            String secondName = "north";

            CreateTerritoryRequest firstRequest =
                    new CreateTerritoryRequest(
                            firstName,
                            "Description 1"
                    );

            CreateTerritoryRequest secondRequest =
                    new CreateTerritoryRequest(
                            secondName,
                            "Description 2"
                    );

            Territory territory1 =
                    new Territory(
                            firstName,
                            "Description 1"
                    );

            when(territoryRepository.existsByName(firstName))
                    .thenReturn(false);

            when(territoryRepository.save(any(Territory.class)))
                    .thenReturn(territory1);

            when(territoryRepository.existsByName(secondName))
                    .thenReturn(true);

            // Act
            TerritoryResponse response1 =
                    territoryService.create(firstRequest);

            // Assert first creation
            assertThat(response1).isNotNull();
            assertThat(response1.name())
                    .isEqualTo(firstName);

            // Act & Assert second creation
            assertThatThrownBy(() ->
                    territoryService.create(secondRequest)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists")
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NAME_EXISTS");

            verify(territoryRepository, times(1))
                    .existsByName(firstName);

            verify(territoryRepository, times(1))
                    .existsByName(secondName);

            verify(territoryRepository, times(1))
                    .save(any(Territory.class));
        }


        /**
         * Boundary:
         * Name with exactly 100 characters.
         *
         * NOTE:
         * @Size(max = 100) belongs to DTO/controller validation.
         * Here we only verify that the service can process the
         * maximum-length value when it has already passed validation.
         */
        @Test
        @DisplayName("TR-10: Service accepts a maximum-length validated name")
        void create_MaximumAllowedNameLength_Success() {

            // Arrange
            String name = "A".repeat(100);

            CreateTerritoryRequest request =
                    new CreateTerritoryRequest(
                            name,
                            "Maximum length name"
                    );

            Territory savedTerritory =
                    new Territory(
                            name,
                            "Maximum length name"
                    );

            when(territoryRepository.existsByName(name))
                    .thenReturn(false);

            when(territoryRepository.save(any(Territory.class)))
                    .thenReturn(savedTerritory);

            // Act
            TerritoryResponse response =
                    territoryService.create(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.name())
                    .isEqualTo(name);
            assertThat(response.name())
                    .hasSize(100);

            verify(territoryRepository)
                    .existsByName(name);

            verify(territoryRepository)
                    .save(any(Territory.class));
        }
    }


    // ============================================================
    // GET BY ID TESTS
    // ============================================================

    @Nested
    @DisplayName("Get By ID Tests")
    class GetByIdTests {

        /**
         * TR-03
         *
         * Positive:
         * Existing territory ID.
         */
        @Test
        @DisplayName("TR-03: Get existing territory by ID")
        void getById_ExistingTerritory_ReturnsTerritory() {

            // Arrange
            Territory territory =
                    new Territory(
                            "North",
                            "North region"
                    );

            when(territoryRepository.findById(1L))
                    .thenReturn(Optional.of(territory));

            // Act
            TerritoryResponse response =
                    territoryService.getById(1L);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.name())
                    .isEqualTo("North");

            verify(territoryRepository)
                    .findById(1L);
        }


        /**
         * TR-04
         *
         * Negative:
         * Territory does not exist.
         */
        @Test
        @DisplayName("TR-04: Get non-existing territory throws NOT_FOUND")
        void getById_NotFound_ThrowsNotFoundException() {

            // Arrange
            when(territoryRepository.findById(999L))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.getById(999L)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Territory not found: 999")
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NOT_FOUND");

            verify(territoryRepository)
                    .findById(999L);
        }
    }


    // ============================================================
    // LIST / PAGINATION TESTS
    // ============================================================

    @Nested
    @DisplayName("List & Pagination Tests")
    class ListPaginationTests {

        /**
         * TR-05
         *
         * Positive:
         * Multiple territories exist and all=true.
         */
        @Test
        @DisplayName("TR-05: List all territories when records exist")
        void listAll_RecordsExist_ReturnsAllTerritories() {

            // Arrange
            List<Territory> territories = List.of(
                    new Territory("North", "North region"),
                    new Territory("South", "South region"),
                    new Territory("East", "East region")
            );

            when(territoryRepository.findAll())
                    .thenReturn(territories);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            null,
                            true,
                            Pageable.unpaged()
                    );

            // Assert
            assertThat(response).isNotNull();

            assertThat(response.getContent())
                    .hasSize(3);

            assertThat(response.getContent())
                    .extracting(TerritoryResponse::name)
                    .containsExactly(
                            "North",
                            "South",
                            "East"
                    );

            assertThat(response.getTotalElements())
                    .isEqualTo(3);

            verify(territoryRepository)
                    .findAll();

            verify(territoryRepository, never())
                    .findAll(any(Pageable.class));
        }


        /**
         * TR-06
         *
         * Boundary:
         * No territories exist.
         */
        @Test
        @DisplayName("TR-06: List all territories when database is empty")
        void listAll_EmptyDatabase_ReturnsEmptyCollection() {

            // Arrange
            when(territoryRepository.findAll())
                    .thenReturn(Collections.emptyList());

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            null,
                            true,
                            Pageable.unpaged()
                    );

            // Assert
            assertThat(response).isNotNull();

            assertThat(response.getContent())
                    .isEmpty();

            assertThat(response.getTotalElements())
                    .isZero();

            verify(territoryRepository)
                    .findAll();
        }


        /**
         * Normal pagination.
         */
        @Test
        @DisplayName("List territories with normal pagination")
        void list_NormalPagination_Success() {

            // Arrange
            Pageable pageable =
                    PageRequest.of(0, 2);

            List<Territory> territories = List.of(
                    new Territory("North", "Desc 1"),
                    new Territory("South", "Desc 2")
            );

            Page<Territory> territoryPage =
                    new PageImpl<>(
                            territories,
                            pageable,
                            2
                    );

            when(territoryRepository.findAll(pageable))
                    .thenReturn(territoryPage);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            null,
                            false,
                            pageable
                    );

            // Assert
            assertThat(response).isNotNull();

            assertThat(response.getContent())
                    .hasSize(2);

            assertThat(response.getTotalElements())
                    .isEqualTo(2);

            assertThat(response.getPage())
                    .isEqualTo(0);

            assertThat(response.getSize())
                    .isEqualTo(2);

            verify(territoryRepository)
                    .findAll(pageable);
        }


        /**
         * AD-16
         *
         * Boundary:
         * Requested page is beyond the available pages.
         */
        @Test
        @DisplayName("AD-16: Page beyond available pages returns empty page")
        void list_AD16_PageBeyondLastPage_ReturnsEmptyPage() {

            // Arrange
            int requestedPage = 99;
            int pageSize = 10;

            Pageable pageable =
                    PageRequest.of(
                            requestedPage,
                            pageSize
                    );

            Page<Territory> emptyPage =
                    new PageImpl<>(
                            Collections.emptyList(),
                            pageable,
                            5
                    );

            when(territoryRepository.findAll(pageable))
                    .thenReturn(emptyPage);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            null,
                            false,
                            pageable
                    );

            // Assert
            assertThat(response).isNotNull();

            assertThat(response.getContent())
                    .isEmpty();

            assertThat(response.getPage())
                    .isEqualTo(99);

            assertThat(response.getTotalElements())
                    .isEqualTo(5);

            verify(territoryRepository)
                    .findAll(pageable);
        }


        /**
         * TR-12
         *
         * Functional:
         * Service preserves the ordering supplied by repository.
         *
         * Important:
         * The service itself does not define sorting.
         * It maps repository results without reordering them.
         */
        @Test
        @DisplayName("TR-12: List all territories preserves repository ordering")
        void listAll_PreservesRepositoryOrdering() {

            // Arrange
            Territory north =
                    new Territory(
                            "North",
                            "North region"
                    );

            Territory south =
                    new Territory(
                            "South",
                            "South region"
                    );

            Territory east =
                    new Territory(
                            "East",
                            "East region"
                    );

            List<Territory> territories =
                    List.of(
                            north,
                            south,
                            east
                    );

            when(territoryRepository.findAll())
                    .thenReturn(territories);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            null,
                            true,
                            Pageable.unpaged()
                    );

            // Assert
            assertThat(response.getContent())
                    .extracting(TerritoryResponse::name)
                    .containsExactly(
                            "North",
                            "South",
                            "East"
                    );

            verify(territoryRepository)
                    .findAll();
        }


        /**
         * Search by name.
         */
        @Test
        @DisplayName("Search territories by name")
        void list_SearchByName_ReturnsMatchingTerritories() {

            // Arrange
            String query = "north";

            Pageable pageable =
                    PageRequest.of(0, 10);

            List<Territory> territories =
                    List.of(
                            new Territory(
                                    "North",
                                    "North region"
                            ),
                            new Territory(
                                    "North East",
                                    "North East region"
                            )
                    );

            Page<Territory> page =
                    new PageImpl<>(
                            territories,
                            pageable,
                            2
                    );

            when(
                    territoryRepository
                            .findByNameContainingIgnoreCase(
                                    "north",
                                    pageable
                            )
            ).thenReturn(page);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            query,
                            false,
                            pageable
                    );

            // Assert
            assertThat(response.getContent())
                    .hasSize(2);

            assertThat(response.getContent())
                    .extracting(TerritoryResponse::name)
                    .containsExactly(
                            "North",
                            "North East"
                    );

            verify(territoryRepository)
                    .findByNameContainingIgnoreCase(
                            "north",
                            pageable
                    );

            verify(territoryRepository, never())
                    .findAll(any(Pageable.class));
        }


        /**
         * Search query containing whitespace is normalized.
         */
        @Test
        @DisplayName("Search query is trimmed before repository call")
        void list_SearchQuery_IsTrimmed() {

            // Arrange
            Pageable pageable =
                    PageRequest.of(0, 10);

            Page<Territory> page =
                    new PageImpl<>(
                            List.of(
                                    new Territory(
                                            "North",
                                            "North region"
                                    )
                            ),
                            pageable,
                            1
                    );

            when(
                    territoryRepository
                            .findByNameContainingIgnoreCase(
                                    "North",
                                    pageable
                            )
            ).thenReturn(page);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            "   North   ",
                            false,
                            pageable
                    );

            // Assert
            assertThat(response.getContent())
                    .hasSize(1);

            verify(territoryRepository)
                    .findByNameContainingIgnoreCase(
                            "North",
                            pageable
                    );
        }


        /**
         * Blank search query behaves like no search.
         */
        @Test
        @DisplayName("Blank search query behaves like no search")
        void list_BlankSearchQuery_UsesFindAll() {

            // Arrange
            Pageable pageable =
                    PageRequest.of(0, 10);

            Page<Territory> page =
                    new PageImpl<>(
                            List.of(
                                    new Territory(
                                            "North",
                                            "North region"
                                    )
                            ),
                            pageable,
                            1
                    );

            when(territoryRepository.findAll(pageable))
                    .thenReturn(page);

            // Act
            PageResponse<TerritoryResponse> response =
                    territoryService.list(
                            "     ",
                            false,
                            pageable
                    );

            // Assert
            assertThat(response.getContent())
                    .hasSize(1);

            verify(territoryRepository)
                    .findAll(pageable);

            verify(
                    territoryRepository,
                    never()
            ).findByNameContainingIgnoreCase(
                    anyString(),
                    any(Pageable.class)
            );
        }
    }


    // ============================================================
    // UPDATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Update Territory Tests")
    class UpdateTests {

        /**
         * Empty update.
         */
        @Test
        @DisplayName("Update territory with no fields throws BAD_REQUEST")
        void update_EmptyUpdate_ThrowsBadRequest() {

            // Arrange
            UpdateTerritoryRequest request =
                    new UpdateTerritoryRequest(
                            null,
                            null
                    );

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.update(
                            1L,
                            request
                    )
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(
                            "At least one field must be provided"
                    )
                    .extracting("errorCode")
                    .isEqualTo("EMPTY_UPDATE");

            verify(
                    territoryRepository,
                    never()
            ).findById(anyLong());
        }


        /**
         * Duplicate name during update.
         */
        @Test
        @DisplayName("Update territory with duplicate name throws CONFLICT")
        void update_DuplicateName_ThrowsConflict() {

            // Arrange
            UpdateTerritoryRequest request =
                    new UpdateTerritoryRequest(
                            "ExistingName",
                            null
                    );

            Territory territory =
                    new Territory(
                            "OldName",
                            "Desc"
                    );

            when(
                    territoryRepository.findById(1L)
            ).thenReturn(
                    Optional.of(territory)
            );

            when(
                    territoryRepository
                            .existsByNameAndIdNot(
                                    "ExistingName",
                                    1L
                            )
            ).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.update(
                            1L,
                            request
                    )
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists")
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NAME_EXISTS");

            assertThat(territory.getName())
                    .isEqualTo("OldName");
        }


        /**
         * Successful name update.
         */
        @Test
        @DisplayName("Update territory name successfully")
        void update_Name_Success() {

            // Arrange
            UpdateTerritoryRequest request =
                    new UpdateTerritoryRequest(
                            "NewName",
                            null
                    );

            Territory territory =
                    new Territory(
                            "OldName",
                            "Description"
                    );

            when(
                    territoryRepository.findById(1L)
            ).thenReturn(
                    Optional.of(territory)
            );

            when(
                    territoryRepository
                            .existsByNameAndIdNot(
                                    "NewName",
                                    1L
                            )
            ).thenReturn(false);

            // Act
            TerritoryResponse response =
                    territoryService.update(
                            1L,
                            request
                    );

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.name())
                    .isEqualTo("NewName");

            assertThat(territory.getName())
                    .isEqualTo("NewName");

            verify(
                    territoryRepository
            ).findById(1L);

            verify(
                    territoryRepository
            ).existsByNameAndIdNot(
                    "NewName",
                    1L
            );
        }


        /**
         * Successful description update.
         */
        @Test
        @DisplayName("Update territory description successfully")
        void update_Description_Success() {

            // Arrange
            UpdateTerritoryRequest request =
                    new UpdateTerritoryRequest(
                            null,
                            "New description"
                    );

            Territory territory =
                    new Territory(
                            "North",
                            "Old description"
                    );

            when(
                    territoryRepository.findById(1L)
            ).thenReturn(
                    Optional.of(territory)
            );

            // Act
            TerritoryResponse response =
                    territoryService.update(
                            1L,
                            request
                    );

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.name())
                    .isEqualTo("North");

            assertThat(territory.getDescription())
                    .isEqualTo("New description");

            verify(
                    territoryRepository
            ).findById(1L);

            verify(
                    territoryRepository,
                    never()
            ).existsByNameAndIdNot(
                    anyString(),
                    anyLong()
            );
        }


        /**
         * Update non-existing territory.
         */
        @Test
        @DisplayName("Update non-existing territory throws NOT_FOUND")
        void update_NotFound_ThrowsNotFound() {

            // Arrange
            UpdateTerritoryRequest request =
                    new UpdateTerritoryRequest(
                            "NewName",
                            null
                    );

            when(
                    territoryRepository.findById(999L)
            ).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.update(
                            999L,
                            request
                    )
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(
                            "Territory not found: 999"
                    )
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NOT_FOUND");
        }
    }


    // ============================================================
    // DELETE TESTS
    // ============================================================

    @Nested
    @DisplayName("Delete Territory Tests")
    class DeleteTests {

        /**
         * Successful deletion.
         */
        @Test
        @DisplayName("Delete territory successfully")
        void delete_Success() {

            // Arrange
            Territory territory =
                    new Territory(
                            "North",
                            "Desc"
                    );

            when(
                    territoryRepository.findById(1L)
            ).thenReturn(
                    Optional.of(territory)
            );

            // Act
            territoryService.delete(1L);

            // Assert
            verify(
                    territoryRepository
            ).findById(1L);

            verify(
                    territoryRepository
            ).delete(territory);

            verify(
                    territoryRepository
            ).flush();
        }


        /**
         * Territory has customers.
         *
         * Database FK violation is translated to BusinessException.
         */
        @Test
        @DisplayName("Delete territory with assigned customers throws CONFLICT")
        void delete_TerritoryHasCustomers_ThrowsConflict() {

            // Arrange
            Territory territory =
                    new Territory(
                            "North",
                            "Desc"
                    );

            when(
                    territoryRepository.findById(1L)
            ).thenReturn(
                    Optional.of(territory)
            );

            doThrow(
                    new DataIntegrityViolationException(
                            "FK constraint"
                    )
            ).when(
                    territoryRepository
            ).flush();

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.delete(1L)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(
                            "Cannot delete a territory that still has customers assigned to it"
                    )
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_HAS_CUSTOMERS");

            verify(
                    territoryRepository
            ).delete(territory);

            verify(
                    territoryRepository
            ).flush();
        }


        /**
         * Delete non-existing territory.
         */
        @Test
        @DisplayName("Delete non-existing territory throws NOT_FOUND")
        void delete_NotFound_ThrowsNotFound() {

            // Arrange
            when(
                    territoryRepository.findById(999L)
            ).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() ->
                    territoryService.delete(999L)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(
                            "Territory not found: 999"
                    )
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NOT_FOUND");

            verify(
                    territoryRepository,
                    never()
            ).delete(any(Territory.class));

            verify(
                    territoryRepository,
                    never()
            ).flush();
        }
    }
}