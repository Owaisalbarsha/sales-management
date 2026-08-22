package com.salesmanagement.vanops.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.vanops.internal.dto.CreateDemandOrderRequest;
import com.salesmanagement.vanops.internal.dto.DemandOrderResponse;
import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.entity.DemandOrderLine;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import com.salesmanagement.vanops.internal.repository.DemandOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandOrderServiceTest {

    @Mock
    private DemandOrderRepository demandOrderRepository;

    @Mock
    private InventoryFacade inventoryFacade;

    @Mock
    private UserFacade userFacade;

    @InjectMocks
    private DemandOrderService demandOrderService;

    private Long salesManagerId;
    private Long representativeId;
    private Long productId;

    @BeforeEach
    void setUp() {
        salesManagerId = 1L;
        representativeId = 2L;
        productId = 100L;
    }

    @Nested
    @DisplayName("Submit Demand Order Tests")
    class SubmitTests {

        @Test
        @DisplayName("DS-01: Submit valid demand order with full stock available")
        void submit_ValidRequest_Success() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            DemandOrder savedOrder = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine orderLine = new DemandOrderLine(productId, 10);
            orderLine.setFulfilledQty(10);
            savedOrder.addLine(orderLine);
            savedOrder.setStatus(DemandOrderStatus.SUBMITTED);

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(15);
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(savedOrder);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));
            when(userFacade.getNameById(anyLong())).thenReturn("User Name");

            DemandOrderResponse response = demandOrderService.submit(salesManagerId, request);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(DemandOrderStatus.SUBMITTED);
            verify(demandOrderRepository).save(any(DemandOrder.class));
        }

        @Test
        @DisplayName("DS-02: Submit adjusts fulfilled quantity when stock is insufficient")
        void submit_InsufficientStock_AdjustsFulfilledQty() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            DemandOrder savedOrder = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine orderLine = new DemandOrderLine(productId, 10);
            orderLine.setFulfilledQty(4);
            savedOrder.addLine(orderLine);
            savedOrder.setStatus(DemandOrderStatus.ADJUSTED);

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(4);
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(savedOrder);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));

            DemandOrderResponse response = demandOrderService.submit(salesManagerId, request);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(DemandOrderStatus.ADJUSTED);
        }

        @Test
        @DisplayName("DS-03: Submit handles zero warehouse stock (fulfilledQty=0)")
        void submit_ZeroWarehouseStock_FulfilledQtyZero() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            DemandOrder savedOrder = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine orderLine = new DemandOrderLine(productId, 10);
            orderLine.setFulfilledQty(0);
            savedOrder.addLine(orderLine);
            savedOrder.setStatus(DemandOrderStatus.ADJUSTED);

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(0);
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(savedOrder);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));

            DemandOrderResponse response = demandOrderService.submit(salesManagerId, request);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(DemandOrderStatus.ADJUSTED);
        }

        @Test
        @DisplayName("DS-04: Submit throws exception when requester is not SALES_MANAGER")
        void submit_InvalidSubmitterRole_ThrowsException() {
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of());
            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.WAREHOUSE_MANAGER);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("is not a SALES_MANAGER");

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-05: Submit throws exception when representative is not SALES_REP")
        void submit_InvalidRepresentativeRole_ThrowsException() {
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of());
            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.WAREHOUSE_MANAGER);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("is not a SALES_REP");

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-06: Submit throws exception on duplicate products")
        void submit_DuplicateProducts_ThrowsException() {
            CreateDemandOrderRequest.Line line1 = new CreateDemandOrderRequest.Line(productId, 5);
            CreateDemandOrderRequest.Line line2 = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line1, line2));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Duplicate product");

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-07: Submit throws exception when product is not found")
        void submit_ProductNotFound_ThrowsException() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(999L, 5);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(999L)).thenReturn(false);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Product not found");

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-08: Submit throws exception when product is inactive")
        void submit_InactiveProduct_ThrowsException() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 5);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(false);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not ACTIVE");

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-09: Submit throws exception when requested quantity is zero")
        void submit_ZeroRequestedQuantity_ThrowsException() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 0);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class);

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-10: Submit throws exception when requested quantity is negative")
        void submit_NegativeRequestedQuantity_ThrowsException() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, -5);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(BusinessException.class);

            verify(demandOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("DS-11: Fulfilled quantity capped at requested quantity")
        void submit_FulfilledQtyDoesNotExceedRequestedQty() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            DemandOrder savedOrder = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine orderLine = new DemandOrderLine(productId, 10);
            orderLine.setFulfilledQty(10);
            savedOrder.addLine(orderLine);
            savedOrder.setStatus(DemandOrderStatus.SUBMITTED);

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(20);
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(savedOrder);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));

            DemandOrderResponse response = demandOrderService.submit(salesManagerId, request);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(DemandOrderStatus.SUBMITTED);
        }
    }

    @Nested
    @DisplayName("Load Demand Order Tests")
    class LoadTests {

        @Test
        @DisplayName("DS-12: Load throws exception when order is already loaded")
        void load_AlreadyLoadedOrder_ThrowsException() {
            Long orderId = 50L;
            DemandOrder order = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            order.setStatus(DemandOrderStatus.LOADED);

            when(demandOrderRepository.findWithLinesById(orderId)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> demandOrderService.load(orderId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already loaded");

            verify(inventoryFacade, never()).transferWarehouseToVan(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("Load demand order successfully transfers inventory")
        void load_SubmittedOrder_Success() {
            Long orderId = 50L;
            DemandOrder order = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine line = new DemandOrderLine(productId, 10);
            line.setFulfilledQty(8);
            order.addLine(line);
            order.setStatus(DemandOrderStatus.SUBMITTED);

            when(demandOrderRepository.findWithLinesById(orderId)).thenReturn(Optional.of(order));
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(order);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));

            DemandOrderResponse response = demandOrderService.load(orderId);

            assertThat(response).isNotNull();
            assertThat(order.getStatus()).isEqualTo(DemandOrderStatus.LOADED);
            verify(inventoryFacade).transferWarehouseToVan(representativeId, productId, 8);
            verify(demandOrderRepository).save(order);
        }
        @Test
        @DisplayName("AD-11: Submit accepts request when fulfilledQty equals requestedQty")
        void submit_FulfilledQtyEqualsRequestedQty_Success() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            DemandOrder savedOrder = new DemandOrder(salesManagerId, representativeId, LocalDate.now());
            DemandOrderLine orderLine = new DemandOrderLine(productId, 10);
            orderLine.setFulfilledQty(10); // Exact match
            savedOrder.addLine(orderLine);
            savedOrder.setStatus(DemandOrderStatus.SUBMITTED);

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(10); // Exact stock match
            when(demandOrderRepository.save(any(DemandOrder.class))).thenReturn(savedOrder);
            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(new ProductInfo(productId, "Product A", "SKU1", BigDecimal.TEN, "General", true));

            DemandOrderResponse response = demandOrderService.submit(salesManagerId, request);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(DemandOrderStatus.SUBMITTED);
            verify(demandOrderRepository).save(any(DemandOrder.class));
        }

        @Test
        @DisplayName("AD-12: Submit rolls back transaction on persistence failure")
        void submit_PersistenceFailure_RollsBackTransaction() {
            CreateDemandOrderRequest.Line line = new CreateDemandOrderRequest.Line(productId, 10);
            CreateDemandOrderRequest request = new CreateDemandOrderRequest(representativeId, List.of(line));

            when(userFacade.getRoleById(salesManagerId)).thenReturn(UserRole.SALES_MANAGER);
            when(userFacade.getRoleById(representativeId)).thenReturn(UserRole.SALES_REP);
            when(inventoryFacade.productExists(productId)).thenReturn(true);
            when(inventoryFacade.isProductActive(productId)).thenReturn(true);
            when(inventoryFacade.getWarehouseQuantity(productId)).thenReturn(10);

            // Simulating DB write failure
            when(demandOrderRepository.save(any(DemandOrder.class)))
                    .thenThrow(new RuntimeException("Database error during persistence"));

            assertThatThrownBy(() -> demandOrderService.submit(salesManagerId, request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Database error during persistence");

            verify(demandOrderRepository).save(any(DemandOrder.class));
        }
    }
}