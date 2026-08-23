package com.salesmanagement.routing.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.customer.api.CustomerInfo;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.routing.api.RouteAssignedEvent;
import com.salesmanagement.routing.internal.dto.AssignCustomersRequest;
import com.salesmanagement.routing.internal.dto.CreateRouteRequest;
import com.salesmanagement.routing.internal.dto.ReorderRouteRequest;
import com.salesmanagement.routing.internal.dto.RouteResponse;
import com.salesmanagement.routing.internal.dto.UpdateRouteRequest;
import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.territory.api.TerritoryFacade;
import com.salesmanagement.territory.api.TerritoryInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RouteService.
 *
 * <p>
 * These tests focus on business logic inside the service layer, mirroring the style used
 * in TerritoryServiceTest and UserServiceTest. Bean validation on the request DTOs is
 * intentionally not exercised here — that is enforced by @Valid at the controller boundary.
 * Cross-module reads (UserFacade, TerritoryFacade, CustomerFacade) are mocked since the
 * service only ever talks to their facades, never internal types.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteOptimizationService optimizationService;

    @Mock
    private UserFacade userFacade;

    @Mock
    private TerritoryFacade territoryFacade;

    @Mock
    private CustomerFacade customerFacade;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private RouteService routeService;

    private static final Long REP_ID = 10L;
    private static final Long TERRITORY_ID = 20L;

    private static CustomerInfo activeCustomer(Long id, Long territoryId) {
        return new CustomerInfo(id, "Customer " + id, territoryId, "Address " + id,
                "0999" + id, new BigDecimal("33.5"), new BigDecimal("36.3"), true);
    }

    private static Route plannedRoute(Long id, Long territoryId) {
        Route route = new Route(REP_ID, territoryId, "North loop", LocalDate.of(2026, 8, 24));
        route.setId(id);
        route.setStatus(RouteStatus.PLANNED);
        return route;
    }

    /** Stubs the enrichment calls made by RouteService.toResponse for the given route. */
    private void stubEnrichment(Route route) {
        lenient().when(userFacade.getNameById(REP_ID)).thenReturn("Jane Rep");
        lenient().when(territoryFacade.getTerritoryInfo(route.getTerritoryId()))
                .thenReturn(new TerritoryInfo(route.getTerritoryId(), "North", "North region"));
        for (RouteCustomerAssignment a : route.getAssignments()) {
            lenient().when(customerFacade.getCustomerInfo(a.getCustomerId()))
                    .thenReturn(activeCustomer(a.getCustomerId(), route.getTerritoryId()));
        }
    }


    // ============================================================
    // CREATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Create Route Tests")
    class CreateTests {

        @Test
        @DisplayName("Create route with no initial customers succeeds")
        void create_NoInitialCustomers_Success() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", LocalDate.of(2026, 8, 24), null);

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    eq(REP_ID), eq(LocalDate.of(2026, 8, 24)), anyList()))
                    .thenReturn(false);
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userFacade.getNameById(REP_ID)).thenReturn("Jane Rep");
            when(territoryFacade.getTerritoryInfo(TERRITORY_ID))
                    .thenReturn(new TerritoryInfo(TERRITORY_ID, "North", "North region"));

            RouteResponse response = routeService.create(request);

            assertThat(response.representativeId()).isEqualTo(REP_ID);
            assertThat(response.territoryId()).isEqualTo(TERRITORY_ID);
            assertThat(response.status()).isEqualTo(RouteStatus.PLANNED);
            assertThat(response.isOptimized()).isFalse();
            assertThat(response.stops()).isEmpty();

            verify(events).publishEvent(any(RouteAssignedEvent.class));
        }

        @Test
        @DisplayName("Create route with initial customers sequences stops 1..n")
        void create_WithInitialCustomers_Success() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", LocalDate.of(2026, 8, 24),
                    List.of(100L, 200L));

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    eq(REP_ID), any(LocalDate.class), anyList()))
                    .thenReturn(false);
            when(customerFacade.getCustomerInfo(100L)).thenReturn(activeCustomer(100L, TERRITORY_ID));
            when(customerFacade.getCustomerInfo(200L)).thenReturn(activeCustomer(200L, TERRITORY_ID));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userFacade.getNameById(REP_ID)).thenReturn("Jane Rep");
            when(territoryFacade.getTerritoryInfo(TERRITORY_ID))
                    .thenReturn(new TerritoryInfo(TERRITORY_ID, "North", "North region"));

            RouteResponse response = routeService.create(request);

            assertThat(response.stops()).hasSize(2);
            assertThat(response.stops())
                    .extracting(RouteResponse.Stop::customerId, RouteResponse.Stop::sequenceNumber)
                    .containsExactly(tuple(100L, 1), tuple(200L, 2));
        }

        @Test
        @DisplayName("Create route with duplicate customer in request throws BAD_REQUEST")
        void create_DuplicateCustomerInRequest_ThrowsBadRequest() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", LocalDate.of(2026, 8, 24),
                    List.of(100L, 100L));

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    eq(REP_ID), any(LocalDate.class), anyList()))
                    .thenReturn(false);
            when(customerFacade.getCustomerInfo(100L)).thenReturn(activeCustomer(100L, TERRITORY_ID));

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Duplicate customer")
                    .extracting("errorCode")
                    .isEqualTo("DUPLICATE_CUSTOMER_ON_ROUTE");

            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Create route for a non-SALES_REP user throws UNPROCESSABLE")
        void create_RepNotSalesRep_ThrowsUnprocessable() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", null, null);

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_MANAGER);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not a SALES_REP")
                    .extracting("errorCode")
                    .isEqualTo("NOT_A_SALES_REP");

            verify(territoryFacade, never()).exists(anyLong());
            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Create route for an unknown territory throws NOT_FOUND")
        void create_TerritoryNotFound_ThrowsNotFound() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", null, null);

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(false);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("TERRITORY_NOT_FOUND");

            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Create route when rep already has an open route that day throws CONFLICT")
        void create_ExistingOpenRouteForDay_ThrowsConflict() {
            LocalDate date = LocalDate.of(2026, 8, 24);
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", date, null);

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    REP_ID, date, List.of(RouteStatus.PLANNED, RouteStatus.ACTIVE)))
                    .thenReturn(true);

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already has a PLANNED or ACTIVE route")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_ALREADY_EXISTS_FOR_DAY");

            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Create route with an inactive customer throws UNPROCESSABLE")
        void create_CustomerNotActive_ThrowsUnprocessable() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", LocalDate.of(2026, 8, 24), List.of(100L));

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    eq(REP_ID), any(LocalDate.class), anyList()))
                    .thenReturn(false);
            when(customerFacade.getCustomerInfo(100L)).thenReturn(
                    new CustomerInfo(100L, "Inactive Co", TERRITORY_ID, null, null, null, null, false));

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not ACTIVE")
                    .extracting("errorCode")
                    .isEqualTo("CUSTOMER_NOT_ACTIVE");

            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Create route with a customer outside the route's territory throws UNPROCESSABLE")
        void create_CustomerOutsideTerritory_ThrowsUnprocessable() {
            CreateRouteRequest request = new CreateRouteRequest(
                    REP_ID, TERRITORY_ID, "North loop", LocalDate.of(2026, 8, 24), List.of(100L));

            when(userFacade.getRoleById(REP_ID)).thenReturn(UserRole.SALES_REP);
            when(territoryFacade.exists(TERRITORY_ID)).thenReturn(true);
            when(routeRepository.existsByRepresentativeIdAndRouteDateAndStatusIn(
                    eq(REP_ID), any(LocalDate.class), anyList()))
                    .thenReturn(false);
            when(customerFacade.getCustomerInfo(100L)).thenReturn(activeCustomer(100L, 999L));

            assertThatThrownBy(() -> routeService.create(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in the route's territory")
                    .extracting("errorCode")
                    .isEqualTo("CUSTOMER_NOT_IN_ROUTE_TERRITORY");
        }
    }


    // ============================================================
    // UPDATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Update Route Tests")
    class UpdateTests {

        @Test
        @DisplayName("Update route name succeeds")
        void update_Name_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.update(1L, new UpdateRouteRequest("South loop", null));

            assertThat(response.name()).isEqualTo("South loop");
            assertThat(route.getName()).isEqualTo("South loop");
        }

        @Test
        @DisplayName("Update route date colliding with another route throws CONFLICT")
        void update_DateCollision_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            LocalDate newDate = LocalDate.of(2026, 9, 1);

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.existsByRepresentativeIdAndRouteDate(REP_ID, newDate)).thenReturn(true);

            assertThatThrownBy(() -> routeService.update(1L, new UpdateRouteRequest(null, newDate)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already has a route on")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_ALREADY_EXISTS_FOR_DAY");

            verify(routeRepository, never()).save(any(Route.class));
        }

        @Test
        @DisplayName("Update a COMPLETED route throws CONFLICT")
        void update_CompletedRoute_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.setStatus(RouteStatus.COMPLETED);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.update(1L, new UpdateRouteRequest("New name", null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("COMPLETED and cannot be modified")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_EDITABLE");
        }

        @Test
        @DisplayName("Update a non-existing route throws NOT_FOUND")
        void update_NotFound_ThrowsNotFound() {
            when(routeRepository.findWithAssignmentsById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> routeService.update(999L, new UpdateRouteRequest("New name", null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Route not found: 999")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_FOUND");
        }
    }


    // ============================================================
    // ASSIGN CUSTOMERS TESTS
    // ============================================================

    @Nested
    @DisplayName("Assign Customers Tests")
    class AssignCustomersTests {

        @Test
        @DisplayName("Assign customers appends after the last sequence and clears isOptimized")
        void assignCustomers_Success_AppendsAfterLastSequence() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            route.setOptimized(true);

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(customerFacade.getCustomerInfo(200L)).thenReturn(activeCustomer(200L, TERRITORY_ID));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);
            when(customerFacade.getCustomerInfo(100L)).thenReturn(activeCustomer(100L, TERRITORY_ID));

            RouteResponse response = routeService.assignCustomers(1L, new AssignCustomersRequest(List.of(200L)));

            assertThat(response.isOptimized()).isFalse();
            assertThat(response.stops())
                    .extracting(RouteResponse.Stop::customerId, RouteResponse.Stop::sequenceNumber)
                    .containsExactly(tuple(100L, 1), tuple(200L, 2));
        }

        @Test
        @DisplayName("Assign customers with a duplicate in the request throws BAD_REQUEST")
        void assignCustomers_DuplicateInRequest_ThrowsBadRequest() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(customerFacade.getCustomerInfo(200L)).thenReturn(activeCustomer(200L, TERRITORY_ID));

            assertThatThrownBy(() -> routeService.assignCustomers(
                    1L, new AssignCustomersRequest(List.of(200L, 200L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("DUPLICATE_CUSTOMER_ON_ROUTE");
        }

        @Test
        @DisplayName("Assign a customer already on the route throws CONFLICT")
        void assignCustomers_AlreadyOnRoute_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.assignCustomers(
                    1L, new AssignCustomersRequest(List.of(100L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already on route")
                    .extracting("errorCode")
                    .isEqualTo("CUSTOMER_ALREADY_ON_ROUTE");
        }

        @Test
        @DisplayName("Assign customers to a COMPLETED route throws CONFLICT")
        void assignCustomers_CompletedRoute_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.setStatus(RouteStatus.COMPLETED);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.assignCustomers(
                    1L, new AssignCustomersRequest(List.of(200L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_EDITABLE");
        }
    }


    // ============================================================
    // REMOVE CUSTOMER TESTS
    // ============================================================

    @Nested
    @DisplayName("Remove Customer Tests")
    class RemoveCustomerTests {

        @Test
        @DisplayName("Remove a customer recompacts the remaining sequence to 1..n")
        void removeCustomer_Success_RecompactsSequence() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            route.addAssignment(new RouteCustomerAssignment(200L, 2));
            route.addAssignment(new RouteCustomerAssignment(300L, 3));
            route.setOptimized(true);

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.removeCustomer(1L, 200L);

            assertThat(response.isOptimized()).isFalse();
            assertThat(response.stops())
                    .extracting(RouteResponse.Stop::customerId, RouteResponse.Stop::sequenceNumber)
                    .containsExactly(tuple(100L, 1), tuple(300L, 2));
        }

        @Test
        @DisplayName("Remove a customer not on the route throws NOT_FOUND")
        void removeCustomer_NotOnRoute_ThrowsNotFound() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.removeCustomer(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("is not on route")
                    .extracting("errorCode")
                    .isEqualTo("ASSIGNMENT_NOT_FOUND");
        }
    }


    // ============================================================
    // REORDER TESTS
    // ============================================================

    @Nested
    @DisplayName("Reorder Tests")
    class ReorderTests {

        @Test
        @DisplayName("Reorder applies the requested sequence and clears isOptimized")
        void reorder_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            route.addAssignment(new RouteCustomerAssignment(200L, 2));
            route.setOptimized(true);

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.reorder(1L, new ReorderRouteRequest(List.of(200L, 100L)));

            assertThat(response.isOptimized()).isFalse();
            assertThat(response.stops())
                    .extracting(RouteResponse.Stop::customerId, RouteResponse.Stop::sequenceNumber)
                    .containsExactly(tuple(200L, 1), tuple(100L, 2));
        }

        @Test
        @DisplayName("Reorder with a duplicate customer throws BAD_REQUEST")
        void reorder_DuplicateInRequest_ThrowsBadRequest() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.reorder(
                    1L, new ReorderRouteRequest(List.of(100L, 100L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("DUPLICATE_CUSTOMER_ON_ROUTE");
        }

        @Test
        @DisplayName("Reorder that does not match the current stop set throws BAD_REQUEST")
        void reorder_MismatchedSet_ThrowsBadRequest() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            route.addAssignment(new RouteCustomerAssignment(200L, 2));
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.reorder(
                    1L, new ReorderRouteRequest(List.of(100L, 300L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must list exactly the customers")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_ORDERING_MISMATCH");
        }
    }


    // ============================================================
    // OPTIMIZE TESTS
    // ============================================================

    @Nested
    @DisplayName("Optimize Tests")
    class OptimizeTests {

        @Test
        @DisplayName("Optimize applies the optimizer's order and sets isOptimized true")
        void optimize_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));
            route.addAssignment(new RouteCustomerAssignment(200L, 2));

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(customerFacade.getCustomerInfo(100L)).thenReturn(activeCustomer(100L, TERRITORY_ID));
            when(customerFacade.getCustomerInfo(200L)).thenReturn(activeCustomer(200L, TERRITORY_ID));
            when(optimizationService.optimizeOrder(anyList())).thenReturn(List.of(200L, 100L));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.optimize(1L);

            assertThat(response.isOptimized()).isTrue();
            assertThat(response.stops())
                    .extracting(RouteResponse.Stop::customerId, RouteResponse.Stop::sequenceNumber)
                    .containsExactly(tuple(200L, 1), tuple(100L, 2));
        }

        @Test
        @DisplayName("Optimize an empty route throws UNPROCESSABLE")
        void optimize_EmptyRoute_ThrowsUnprocessable() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.optimize(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("has no customers to optimise")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_EMPTY");

            verify(optimizationService, never()).optimizeOrder(anyList());
        }

        @Test
        @DisplayName("Optimize a route with a customer missing coordinates throws UNPROCESSABLE")
        void optimize_MissingCoordinates_ThrowsUnprocessable() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.addAssignment(new RouteCustomerAssignment(100L, 1));

            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(customerFacade.getCustomerInfo(100L)).thenReturn(
                    new CustomerInfo(100L, "No Coords Co", TERRITORY_ID, null, null, null, null, true));

            assertThatThrownBy(() -> routeService.optimize(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no coordinates")
                    .extracting("errorCode")
                    .isEqualTo("CUSTOMER_MISSING_COORDINATES");

            verify(routeRepository, never()).save(any(Route.class));
        }
    }


    // ============================================================
    // UPDATE STATUS TESTS
    // ============================================================

    @Nested
    @DisplayName("Update Status Tests")
    class UpdateStatusTests {

        @Test
        @DisplayName("PLANNED to ACTIVE is a legal transition")
        void updateStatus_PlannedToActive_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.updateStatus(1L, RouteStatus.ACTIVE);

            assertThat(response.status()).isEqualTo(RouteStatus.ACTIVE);
        }

        @Test
        @DisplayName("ACTIVE to COMPLETED is a legal transition")
        void updateStatus_ActiveToCompleted_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.setStatus(RouteStatus.ACTIVE);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.updateStatus(1L, RouteStatus.COMPLETED);

            assertThat(response.status()).isEqualTo(RouteStatus.COMPLETED);
        }

        @Test
        @DisplayName("PLANNED to COMPLETED (skipping ACTIVE) throws CONFLICT")
        void updateStatus_IllegalTransition_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.updateStatus(1L, RouteStatus.COMPLETED))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Illegal route status transition")
                    .extracting("errorCode")
                    .isEqualTo("ILLEGAL_ROUTE_STATUS_TRANSITION");

            verify(routeRepository, never()).save(any(Route.class));
        }
    }


    // ============================================================
    // DELETE TESTS
    // ============================================================

    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("Delete a PLANNED route succeeds")
        void delete_PlannedRoute_Success() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            routeService.delete(1L);

            verify(routeRepository).delete(route);
        }

        @Test
        @DisplayName("Delete a non-PLANNED route throws CONFLICT")
        void delete_NonPlannedRoute_ThrowsConflict() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            route.setStatus(RouteStatus.ACTIVE);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));

            assertThatThrownBy(() -> routeService.delete(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only PLANNED routes can be deleted")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_DELETABLE");

            verify(routeRepository, never()).delete(any(Route.class));
        }
    }


    // ============================================================
    // GET / LIST / REP-DATE TESTS
    // ============================================================

    @Nested
    @DisplayName("Get, List and Rep-Date Tests")
    class GetListRepDateTests {

        @Test
        @DisplayName("Get existing route by id")
        void getById_Found() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            when(routeRepository.findWithAssignmentsById(1L)).thenReturn(Optional.of(route));
            stubEnrichment(route);

            RouteResponse response = routeService.getById(1L);

            assertThat(response.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Get non-existing route by id throws NOT_FOUND")
        void getById_NotFound() {
            when(routeRepository.findWithAssignmentsById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> routeService.getById(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_FOUND");
        }

        @Test
        @DisplayName("List delegates to repository search and wraps the page")
        void list_DelegatesToRepository() {
            Route route = plannedRoute(1L, TERRITORY_ID);
            Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
            Page<Route> page = new PageImpl<>(List.of(route), pageable, 1);

            when(routeRepository.search(REP_ID, RouteStatus.PLANNED, null, pageable)).thenReturn(page);
            stubEnrichment(route);

            PageResponse<RouteResponse> response =
                    routeService.list(REP_ID, RouteStatus.PLANNED, null, pageable);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Rep's route for a day auto-activates a PLANNED route on first fetch")
        void getRouteForRepOnDate_PlannedAutoActivates() {
            LocalDate date = LocalDate.of(2026, 8, 24);
            Route route = plannedRoute(1L, TERRITORY_ID);

            when(routeRepository.findAllWithAssignmentsByRepresentativeIdAndRouteDate(REP_ID, date))
                    .thenReturn(List.of(route));
            when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));
            stubEnrichment(route);

            RouteResponse response = routeService.getRouteForRepOnDate(REP_ID, date);

            assertThat(response.status()).isEqualTo(RouteStatus.ACTIVE);
            assertThat(route.getStatus()).isEqualTo(RouteStatus.ACTIVE);
        }

        @Test
        @DisplayName("Rep's route for a day with no route that day throws NOT_FOUND")
        void getRouteForRepOnDate_NotFound() {
            LocalDate date = LocalDate.of(2026, 8, 24);
            when(routeRepository.findAllWithAssignmentsByRepresentativeIdAndRouteDate(REP_ID, date))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> routeService.getRouteForRepOnDate(REP_ID, date))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("No route for representative")
                    .extracting("errorCode")
                    .isEqualTo("ROUTE_NOT_FOUND");
        }
    }
}
