package com.salesmanagement.visit.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.routing.api.RouteAssignmentInfo;
import com.salesmanagement.routing.api.RouteExecutionStarted;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.visit.internal.dto.CheckInRequest;
import com.salesmanagement.visit.internal.dto.CheckOutRequest;
import com.salesmanagement.visit.internal.dto.EndDayRequest;
import com.salesmanagement.visit.internal.dto.VisitResponse;
import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    @Mock
    private VisitRepository visitRepository;

    @Mock
    private RoutingFacade routingFacade;

    @Mock
    private CustomerFacade customerFacade;

    @Mock
    private UserFacade userFacade;

    @Mock
    private ApplicationEventPublisher events;

    private VisitService service;

    private static final Long REPRESENTATIVE_ID = 10L;
    private static final Long OTHER_REPRESENTATIVE_ID = 20L;

    private static final Long ROUTE_ID = 100L;

    private static final Long CUSTOMER_ID = 300L;
    private static final Long OTHER_CUSTOMER_ID = 400L;

    private static final Long TERRITORY_ID = 500L;

    private static final BigDecimal LATITUDE =
            new BigDecimal("33.500000");

    private static final BigDecimal LONGITUDE =
            new BigDecimal("36.300000");

    private static final Instant CHECK_IN_TIME =
            Instant.now().minusSeconds(120);

    private static final Instant CHECK_OUT_TIME =
            Instant.now().minusSeconds(60);

    @BeforeEach
    void setUp() {

        service = new VisitService(
                visitRepository,
                routingFacade,
                customerFacade,
                userFacade,
                events
        );
    }

    // ============================================================
    // HELPER METHODS & FACTORIES
    // ============================================================

    private RouteInfo plannedRoute() {

        return new RouteInfo(
                ROUTE_ID,
                REPRESENTATIVE_ID,
                TERRITORY_ID,
                "Route 1",
                LocalDate.now(),
                "PLANNED",
                false,
                List.of(
                        new RouteAssignmentInfo(
                                CUSTOMER_ID,
                                TERRITORY_ID,
                                1
                        )
                )
        );
    }

    private RouteInfo activeRoute() {

        return new RouteInfo(
                ROUTE_ID,
                REPRESENTATIVE_ID,
                TERRITORY_ID,
                "Route 1",
                LocalDate.now(),
                "ACTIVE",
                false,
                List.of(
                        new RouteAssignmentInfo(
                                CUSTOMER_ID,
                                TERRITORY_ID,
                                1
                        )
                )
        );
    }

    private RouteInfo activeRouteWithTwoStops() {

        return new RouteInfo(
                ROUTE_ID,
                REPRESENTATIVE_ID,
                TERRITORY_ID,
                "Route 1",
                LocalDate.now(),
                "ACTIVE",
                false,
                List.of(
                        new RouteAssignmentInfo(
                                CUSTOMER_ID,
                                TERRITORY_ID,
                                1
                        ),
                        new RouteAssignmentInfo(
                                OTHER_CUSTOMER_ID,
                                TERRITORY_ID,
                                2
                        )
                )
        );
    }

    private Visit inProgressVisit() {

        return Visit.checkIn(
                CUSTOMER_ID,
                REPRESENTATIVE_ID,
                ROUTE_ID,
                CHECK_IN_TIME,
                LATITUDE.toPlainString()
                        + ","
                        + LONGITUDE.toPlainString()
        );
    }

    private Visit completedVisit() {

        Visit visit = inProgressVisit();

        visit.completeCheckOut(
                CHECK_OUT_TIME,
                "33.501000,36.301000"
        );

        return visit;
    }

    private void stubSuccessfulCheckInDependencies(
            RouteInfo route
    ) {

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(customerFacade.exists(CUSTOMER_ID))
                .thenReturn(true);

        when(routingFacade.isCustomerOnRoute(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(true);

        when(visitRepository.findByRouteIdAndCustomerId(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(Optional.empty());

        when(visitRepository.countByRouteIdAndStatus(
                ROUTE_ID,
                VisitStatus.IN_PROGRESS
        )).thenReturn(0L);
    }

    // ============================================================
    // CHECK IN
    // ============================================================

    @Test
    @DisplayName("CheckIn - create visit successfully")
    void checkIn_shouldCreateVisitSuccessfully() {

        RouteInfo route = plannedRoute();

        stubSuccessfulCheckInDependencies(route);

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        LONGITUDE,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        assertEquals(
                ROUTE_ID,
                response.routeId()
        );

        assertEquals(
                CUSTOMER_ID,
                response.customerId()
        );

        assertEquals(
                REPRESENTATIVE_ID,
                response.representativeId()
        );

        assertEquals(
                VisitStatus.IN_PROGRESS,
                response.status()
        );

        assertEquals(
                CHECK_IN_TIME,
                response.checkInTime()
        );

        assertEquals(
                "33.500000,36.300000",
                response.checkInLocation()
        );

        verify(visitRepository)
                .save(any(Visit.class));

        verify(events)
                .publishEvent(
                        any(RouteExecutionStarted.class)
                );
    }

    @Test
    @DisplayName("CheckIn - should not publish route started when route is already active")
    void checkIn_shouldNotPublishRouteStarted_whenRouteAlreadyActive() {

        RouteInfo route = activeRoute();

        stubSuccessfulCheckInDependencies(route);

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        LONGITUDE,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        assertEquals(
                VisitStatus.IN_PROGRESS,
                response.status()
        );

        verify(visitRepository)
                .save(any(Visit.class));

        verify(
                events,
                never()
        ).publishEvent(
                any(RouteExecutionStarted.class)
        );
    }

    // ============================================================
    // AD-01
    // Latitude minimum boundary
    // ============================================================

    @Test
    @DisplayName("AD-01: CheckIn accepts latitude minimum boundary -90.0")
    void checkIn_shouldAcceptLatitudeMinimumBoundary() {

        RouteInfo route = plannedRoute();

        stubSuccessfulCheckInDependencies(route);

        BigDecimal latitude =
                new BigDecimal("-90.0");

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        latitude,
                        LONGITUDE,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        verify(visitRepository)
                .save(any(Visit.class));

        verify(events)
                .publishEvent(
                        any(RouteExecutionStarted.class)
                );
    }

    // ============================================================
    // AD-02
    // Latitude maximum boundary
    // ============================================================

    @Test
    @DisplayName("AD-02: CheckIn accepts latitude maximum boundary 90.0")
    void checkIn_shouldAcceptLatitudeMaximumBoundary() {

        RouteInfo route = plannedRoute();

        stubSuccessfulCheckInDependencies(route);

        BigDecimal latitude =
                new BigDecimal("90.0");

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        latitude,
                        LONGITUDE,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        verify(visitRepository)
                .save(any(Visit.class));

        verify(events)
                .publishEvent(
                        any(RouteExecutionStarted.class)
                );
    }

    // ============================================================
    // AD-03
    // Longitude minimum boundary
    // ============================================================

    @Test
    @DisplayName("AD-03: CheckIn accepts longitude minimum boundary -180.0")
    void checkIn_shouldAcceptLongitudeMinimumBoundary() {

        RouteInfo route = plannedRoute();

        stubSuccessfulCheckInDependencies(route);

        BigDecimal longitude =
                new BigDecimal("-180.0");

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        longitude,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        verify(visitRepository)
                .save(any(Visit.class));

        verify(events)
                .publishEvent(
                        any(RouteExecutionStarted.class)
                );
    }

    // ============================================================
    // AD-04
    // Longitude maximum boundary
    // ============================================================

    @Test
    @DisplayName("AD-04: CheckIn accepts longitude maximum boundary 180.0")
    void checkIn_shouldAcceptLongitudeMaximumBoundary() {

        RouteInfo route = plannedRoute();

        stubSuccessfulCheckInDependencies(route);

        BigDecimal longitude =
                new BigDecimal("180.0");

        when(visitRepository.save(any(Visit.class)))
                .thenReturn(inProgressVisit());

        VisitResponse response = service.checkIn(
                REPRESENTATIVE_ID,
                new CheckInRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        longitude,
                        CHECK_IN_TIME
                )
        );

        assertNotNull(response);

        verify(visitRepository)
                .save(any(Visit.class));

        verify(events)
                .publishEvent(
                        any(RouteExecutionStarted.class)
                );
    }

    // ============================================================
    // Existing CHECK IN validation
    // ============================================================

    @Test
    @DisplayName("CheckIn - fail when route does not belong to representative")
    void checkIn_shouldFail_whenRouteDoesNotBelongToRepresentative() {

        RouteInfo route = new RouteInfo(
                ROUTE_ID,
                OTHER_REPRESENTATIVE_ID,
                TERRITORY_ID,
                "Route 1",
                LocalDate.now(),
                "PLANNED",
                false,
                List.of()
        );

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                LATITUDE,
                                LONGITUDE,
                                CHECK_IN_TIME
                        )
                )
        );

        verify(customerFacade, never())
                .exists(anyLong());

        verify(visitRepository, never())
                .save(any(Visit.class));
    }

    @Test
    @DisplayName("CheckIn - fail when customer does not exist")
    void checkIn_shouldFail_whenCustomerDoesNotExist() {

        RouteInfo route = plannedRoute();

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(customerFacade.exists(CUSTOMER_ID))
                .thenReturn(false);

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                LATITUDE,
                                LONGITUDE,
                                CHECK_IN_TIME
                        )
                )
        );

        verify(visitRepository, never())
                .save(any(Visit.class));
    }

    @Test
    @DisplayName("CheckIn - fail when time is in future")
    void checkIn_shouldFail_whenTimeIsInFuture() {

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(plannedRoute());

        Instant futureTime =
                Instant.now().plusSeconds(3600);

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                LATITUDE,
                                LONGITUDE,
                                futureTime
                        )
                )
        );

        verify(visitRepository, never())
                .save(any(Visit.class));
    }

    @Test
    @DisplayName("CheckIn - fail when latitude is invalid")
    void checkIn_shouldFail_whenLatitudeIsInvalid() {

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(plannedRoute());

        BigDecimal invalidLatitude =
                new BigDecimal("95.000000");

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                invalidLatitude,
                                LONGITUDE,
                                CHECK_IN_TIME
                        )
                )
        );

        verify(visitRepository, never())
                .save(any(Visit.class));
    }

    @Test
    @DisplayName("CheckIn - fail when longitude is invalid")
    void checkIn_shouldFail_whenLongitudeIsInvalid() {

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(plannedRoute());

        BigDecimal invalidLongitude =
                new BigDecimal("185.000000");

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                LATITUDE,
                                invalidLongitude,
                                CHECK_IN_TIME
                        )
                )
        );

        verify(visitRepository, never())
                .save(any(Visit.class));
    }

    // ============================================================
    // AD-05
    // CheckOut same timestamp as CheckIn
    // ============================================================

    @Test
    @DisplayName("AD-05: CheckOut with same timestamp as CheckIn is explicitly verified")
    void checkOut_shouldAcceptSameTimestampAsCheckIn() {

        Instant sameTime =
                Instant.now().minusSeconds(120);

        RouteInfo route = activeRoute();

        Visit visit = Visit.checkIn(
                CUSTOMER_ID,
                REPRESENTATIVE_ID,
                ROUTE_ID,
                sameTime,
                LATITUDE.toPlainString()
                        + ","
                        + LONGITUDE.toPlainString()
        );

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(visitRepository.findByRouteIdAndCustomerId(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(Optional.of(visit));

        /*
         * The service only rejects checkout when:
         *
         * checkOutTime.isBefore(checkInTime)
         *
         * Equality is therefore accepted by the current implementation.
         */

        when(visitRepository.findByRouteId(ROUTE_ID))
                .thenReturn(List.of(visit));

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        VisitResponse response = service.checkOut(
                REPRESENTATIVE_ID,
                new CheckOutRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        LONGITUDE,
                        sameTime
                )
        );

        assertNotNull(response);

        assertEquals(
                VisitStatus.COMPLETED,
                response.status()
        );

        assertEquals(
                sameTime,
                response.checkOutTime()
        );

        verify(visitRepository)
                .save(visit);
    }

    // ============================================================
    // AD-06
    // Duplicate CheckIn
    // ============================================================

    @Test
    @DisplayName("AD-06: Duplicate CheckIn is rejected and only one visit exists")
    void checkIn_shouldRejectDuplicateRequest() {

        RouteInfo route = plannedRoute();

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(customerFacade.exists(CUSTOMER_ID))
                .thenReturn(true);

        when(routingFacade.isCustomerOnRoute(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(true);

        Visit existingVisit =
                inProgressVisit();

        when(visitRepository.findByRouteIdAndCustomerId(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(Optional.of(existingVisit));

        assertThrows(
                BusinessException.class,
                () -> service.checkIn(
                        REPRESENTATIVE_ID,
                        new CheckInRequest(
                                ROUTE_ID,
                                CUSTOMER_ID,
                                LATITUDE,
                                LONGITUDE,
                                CHECK_IN_TIME
                        )
                )
        );

        verify(visitRepository, never())
                .save(any(Visit.class));

        verify(events, never())
                .publishEvent(any(RouteExecutionStarted.class));
    }

    // ============================================================
    // CHECK OUT
    // ============================================================

    @Test
    @DisplayName("CheckOut - successfully completes visit")
    void checkOut_shouldCompleteVisitSuccessfully() {

        RouteInfo route = activeRoute();

        Visit visit = inProgressVisit();

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(visitRepository.findByRouteIdAndCustomerId(
                ROUTE_ID,
                CUSTOMER_ID
        )).thenReturn(Optional.of(visit));

        when(visitRepository.findByRouteId(ROUTE_ID))
                .thenReturn(List.of(visit));

        VisitResponse response = service.checkOut(
                REPRESENTATIVE_ID,
                new CheckOutRequest(
                        ROUTE_ID,
                        CUSTOMER_ID,
                        LATITUDE,
                        LONGITUDE,
                        CHECK_OUT_TIME
                )
        );

        assertNotNull(response);

        assertEquals(
                VisitStatus.COMPLETED,
                response.status()
        );

        assertEquals(
                CHECK_OUT_TIME,
                response.checkOutTime()
        );

        verify(visitRepository)
                .save(visit);
    }

    // ============================================================
    // END DAY
    // ============================================================

    @Test
    @DisplayName("EndDay - creates missed visits for unvisited stops")
    void endDay_shouldCreateMissedVisitsForUnvisitedStops() {

        RouteInfo route =
                activeRouteWithTwoStops();

        when(routingFacade.getRouteInfo(ROUTE_ID))
                .thenReturn(route);

        when(visitRepository.countByRouteIdAndStatus(
                ROUTE_ID,
                VisitStatus.IN_PROGRESS
        )).thenReturn(0L);

        when(visitRepository.findByRouteId(ROUTE_ID))
                .thenReturn(List.of());

        service.endDay(
                REPRESENTATIVE_ID,
                new EndDayRequest(ROUTE_ID)
        );

        ArgumentCaptor<Visit> captor =
                ArgumentCaptor.forClass(Visit.class);

        verify(
                visitRepository,
                times(2)
        ).save(captor.capture());

        List<Visit> savedVisits =
                captor.getAllValues();

        assertEquals(
                2,
                savedVisits.size()
        );
    }
}