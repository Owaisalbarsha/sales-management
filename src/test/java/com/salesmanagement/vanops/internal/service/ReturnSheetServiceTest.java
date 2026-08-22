package com.salesmanagement.vanops.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.inventory.api.VanInventoryItemInfo;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.vanops.internal.dto.CreateReturnSheetRequest;
import com.salesmanagement.vanops.internal.dto.ReturnSheetResponse;
import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.entity.ReturnSheetLine;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import com.salesmanagement.vanops.internal.repository.ReturnSheetRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnSheetServiceTest {

    @Mock
    private ReturnSheetRepository returnSheetRepository;

    @Mock
    private InventoryFacade inventoryFacade;

    @Mock
    private UserFacade userFacade;

    @InjectMocks
    private ReturnSheetService returnSheetService;

    private Long repId;
    private Long productId;

    @BeforeEach
    void setUp() {
        repId = 1L;
        productId = 100L;
    }

    // ============================================================
    // CREATE RETURN SHEET
    // ============================================================

    @Nested
    @DisplayName("Create Return Sheet Tests")
    class CreateTests {

        @Test
        @DisplayName("RS-01: Create return sheet successfully")
        void create_Success() {

            CreateReturnSheetRequest.Line line =
                    new CreateReturnSheetRequest.Line(productId, 5);

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(repId, List.of(line));

            ReturnSheet savedSheet =
                    new ReturnSheet(repId, LocalDate.now());

            savedSheet.addLine(
                    new ReturnSheetLine(productId, 5)
            );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            when(inventoryFacade.productExists(productId))
                    .thenReturn(true);

            when(returnSheetRepository.save(any(ReturnSheet.class)))
                    .thenReturn(savedSheet);

            when(inventoryFacade.getProductInfo(productId))
                    .thenReturn(
                            new ProductInfo(
                                    productId,
                                    "Item A",
                                    "SKU1",
                                    BigDecimal.TEN,
                                    "General",
                                    true
                            )
                    );

            when(userFacade.getNameById(repId))
                    .thenReturn("John Doe");

            ReturnSheetResponse response =
                    returnSheetService.create(request);

            assertThat(response).isNotNull();
            assertThat(response.representativeId())
                    .isEqualTo(repId);

            verify(userFacade)
                    .getRoleById(repId);

            verify(inventoryFacade)
                    .productExists(productId);

            verify(returnSheetRepository)
                    .save(any(ReturnSheet.class));
        }


        @Test
        @DisplayName("RS-02: Create throws exception when user is not SALES_REP")
        void create_NotSalesRep_ThrowsException() {

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(
                            repId,
                            List.of()
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.WAREHOUSE_MANAGER);

            assertThatThrownBy(
                    () -> returnSheetService.create(request)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("is not a SALES_REP");

            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));
        }


        @Test
        @DisplayName("RS-03: Create throws exception when duplicate products are provided")
        void create_DuplicateProducts_ThrowsException() {

            CreateReturnSheetRequest.Line line1 =
                    new CreateReturnSheetRequest.Line(productId, 5);

            CreateReturnSheetRequest.Line line2 =
                    new CreateReturnSheetRequest.Line(productId, 10);

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(
                            repId,
                            List.of(line1, line2)
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(
                    () -> returnSheetService.create(request)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Duplicate product");

            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));

            verify(inventoryFacade, never())
                    .returnVanToWarehouse(
                            anyLong(),
                            anyLong(),
                            anyInt()
                    );
        }


        @Test
        @DisplayName("RS-04: Create throws exception when product does not exist")
        void create_ProductNotFound_ThrowsException() {

            CreateReturnSheetRequest.Line line =
                    new CreateReturnSheetRequest.Line(productId, 5);

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(
                            repId,
                            List.of(line)
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            when(inventoryFacade.productExists(productId))
                    .thenReturn(false);

            assertThatThrownBy(
                    () -> returnSheetService.create(request)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Product not found");

            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));
        }


        // ========================================================
        // RS-05: ZERO QUANTITY
        // ========================================================

        @Test
        @DisplayName("RS-05: Create rejects return quantity equal to zero")
        void create_ZeroReturnQuantity_ThrowsException() {

            int returnQty = 0;

            CreateReturnSheetRequest.Line line =
                    new CreateReturnSheetRequest.Line(
                            productId,
                            returnQty
                    );

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(
                            repId,
                            List.of(line)
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(
                    () -> returnSheetService.create(request)
            )
                    .isInstanceOf(BusinessException.class);

            // No ReturnSheet should be saved
            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));

            // No stock/warehouse operation should happen
            verify(inventoryFacade, never())
                    .returnVanToWarehouse(
                            anyLong(),
                            anyLong(),
                            anyInt()
                    );
        }


        // ========================================================
        // RS-06: NEGATIVE QUANTITY
        // ========================================================

        @Test
        @DisplayName("RS-06: Create rejects negative return quantity")
        void create_NegativeReturnQuantity_ThrowsException() {

            int returnQty = -1;

            CreateReturnSheetRequest.Line line =
                    new CreateReturnSheetRequest.Line(
                            productId,
                            returnQty
                    );

            CreateReturnSheetRequest request =
                    new CreateReturnSheetRequest(
                            repId,
                            List.of(line)
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            assertThatThrownBy(
                    () -> returnSheetService.create(request)
            )
                    .isInstanceOf(BusinessException.class);

            // No ReturnSheet should be saved
            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));

            // No stock/warehouse operation should happen
            verify(inventoryFacade, never())
                    .returnVanToWarehouse(
                            anyLong(),
                            anyLong(),
                            anyInt()
                    );
        }
    }


    // ============================================================
    // COMPLETE RETURN SHEET
    // ============================================================

    @Nested
    @DisplayName("Complete Return Sheet Tests")
    class CompleteTests {

        @Test
        @DisplayName("RS-07: Complete return sheet successfully")
        void complete_Success() {

            Long sheetId = 10L;

            ReturnSheet sheet =
                    new ReturnSheet(
                            repId,
                            LocalDate.now()
                    );

            sheet.addLine(
                    new ReturnSheetLine(
                            productId,
                            5
                    )
            );

            when(returnSheetRepository.findWithLinesById(sheetId))
                    .thenReturn(Optional.of(sheet));

            when(returnSheetRepository.save(any(ReturnSheet.class)))
                    .thenReturn(sheet);

            ReturnSheetResponse response =
                    returnSheetService.complete(sheetId);

            assertThat(response).isNotNull();

            assertThat(sheet.getStatus())
                    .isEqualTo(ReturnSheetStatus.COMPLETED);

            verify(inventoryFacade)
                    .returnVanToWarehouse(
                            repId,
                            productId,
                            5
                    );

            verify(returnSheetRepository)
                    .save(sheet);
        }


        @Test
        @DisplayName("RS-08: Complete throws exception if already completed")
        void complete_AlreadyCompleted_ThrowsException() {

            Long sheetId = 10L;

            ReturnSheet sheet =
                    new ReturnSheet(
                            repId,
                            LocalDate.now()
                    );

            sheet.setStatus(
                    ReturnSheetStatus.COMPLETED
            );

            when(returnSheetRepository.findWithLinesById(sheetId))
                    .thenReturn(Optional.of(sheet));

            assertThatThrownBy(
                    () -> returnSheetService.complete(sheetId)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already completed");

            verify(inventoryFacade, never())
                    .returnVanToWarehouse(
                            anyLong(),
                            anyLong(),
                            anyInt()
                    );

            verify(returnSheetRepository, never())
                    .save(any(ReturnSheet.class));
        }
    }


    // ============================================================
    // AUTO CREATE RETURN SHEET
    // ============================================================

    @Nested
    @DisplayName("Auto-Create Return Sheet Tests")
    class AutoCreateTests {

        @Test
        @DisplayName("RS-09: Auto create draft sheet from van inventory")
        void autoCreate_Success() {

            VanInventoryItemInfo item =
                    new VanInventoryItemInfo(
                            productId,
                            15
                    );

            when(userFacade.getRoleById(repId))
                    .thenReturn(UserRole.SALES_REP);

            when(inventoryFacade.getVanInventoryInfo(repId))
                    .thenReturn(List.of(item));

            ReturnSheet savedSheet =
                    new ReturnSheet(
                            repId,
                            LocalDate.now()
                    );

            savedSheet.addLine(
                    new ReturnSheetLine(
                            productId,
                            15
                    )
            );

            when(returnSheetRepository.save(any(ReturnSheet.class)))
                    .thenReturn(savedSheet);

            ReturnSheetResponse response =
                    returnSheetService.autoCreate(repId);

            assertThat(response).isNotNull();

            verify(userFacade)
                    .getRoleById(repId);

            verify(inventoryFacade)
                    .getVanInventoryInfo(repId);

            verify(returnSheetRepository)
                    .save(any(ReturnSheet.class));
        }
    }
}