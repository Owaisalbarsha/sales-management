package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.LowStockDetectedEvent;
import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.service.NotificationService;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Raises low-stock notifications when a product crosses below its minimum
 * warehouse level (FR-106).
 *
 * <p>Consumes {@link LowStockDetectedEvent} (added to {@code inventory.api},
 * published by {@code StockService} <em>after</em> the atomic deduction commits).
 * {@link ApplicationModuleListener} semantics as elsewhere.</p>
 *
 * <p><strong>Recipients (a policy decision that lives here, not in inventory).</strong>
 * Low stock is actioned by the roles that manage the warehouse and the sales
 * operation: {@link UserRole#WAREHOUSE_MANAGER}, {@link UserRole#SALES_MANAGER},
 * and {@link UserRole#ADMIN}. Sales reps are deliberately excluded — they do not
 * replenish warehouse stock. The event carries product identity only; this
 * listener decides the audience via {@code UserFacade}, keeping inventory ignorant
 * of who cares.</p>
 *
 * <p><strong>FR-114 dedup.</strong> The {@code sourceRef} is
 * {@code "low-stock:product:{productId}:{quantity}"} — per recipient it is made
 * unique inside {@link NotificationService#create}. Including the crossing
 * quantity means a later, deeper crossing (e.g. min-1 then min-3 after a
 * restock-and-redrop) is a distinct alert, while a retried delivery of the same
 * event is a no-op. If you prefer at-most-one-open-alert-per-product semantics,
 * drop {@code quantity} from the key.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryNotificationListener {

    private static final List<UserRole> STOCK_ROLES =
            List.of(UserRole.WAREHOUSE_MANAGER, UserRole.SALES_MANAGER, UserRole.ADMIN);

    private final NotificationService notificationService;
    private final UserFacade userFacade;

    /** FR-106: alert stock-managing users that a product dropped below its minimum. */
    @ApplicationModuleListener
    void on(LowStockDetectedEvent event) {
        Set<Long> recipients = new LinkedHashSet<>();
        for (UserRole role : STOCK_ROLES) {
            recipients.addAll(userFacade.findActiveUserIdsByRole(role));
        }

        if (recipients.isEmpty()) {
            log.warn("Low stock on product {} but no active WAREHOUSE_MANAGER/SALES_MANAGER/ADMIN to notify.",
                    event.productId());
            return;
        }

        String title = "Low stock";
        String message = "Product \"" + event.productName() + "\" is low: "
                + event.quantity() + " left (minimum " + event.minStockLevel() + ").";

        for (Long userId : recipients) {
            String sourceRef = "low-stock:product:" + event.productId() + ":" + event.quantity()
                    + ":user:" + userId;
            notificationService.create(
                    userId,
                    NotificationType.INVENTORY,
                    title,
                    message,
                    sourceRef,
                    event.productId());
        }
    }
}
