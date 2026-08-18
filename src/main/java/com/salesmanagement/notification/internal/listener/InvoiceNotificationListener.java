package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.invoicing.api.InvoiceApprovedEvent;
import com.salesmanagement.invoicing.api.InvoiceRejectedEvent;
import com.salesmanagement.invoicing.api.InvoiceSubmittedEvent;
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
 * Raises notifications for invoice lifecycle events.
 *
 * <ul>
 *   <li>FR-103 — approved → the rep who raised it.</li>
 *   <li>FR-104 — rejected (with reason) → the rep who raised it.</li>
 *   <li>Submit → the approvers (SALES_MANAGER + ADMIN): an invoice is awaiting review.
 *       This is the counterpart of the rep-facing approve/reject alerts — without it,
 *       managers would have to poll the invoice list to discover pending work.</li>
 * </ul>
 *
 * <p>Each handler is an {@link ApplicationModuleListener}: async, post-commit, retried,
 * cycle-free. Each builds a deterministic {@code sourceRef} so a redelivered event
 * de-duplicates (FR-114) inside {@link NotificationService#create}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceNotificationListener {

    /** Roles that review invoices — the audience for a submitted invoice. */
    private static final List<UserRole> APPROVER_ROLES =
            List.of(UserRole.SALES_MANAGER, UserRole.ADMIN);

    private final NotificationService notificationService;
    private final UserFacade userFacade;

    /**
     * Notify the approvers that a rep submitted an invoice awaiting review.
     * Recipients are the active SALES_MANAGERs and ADMINs; the message names the rep.
     */
    @ApplicationModuleListener
    void on(InvoiceSubmittedEvent event) {
        Set<Long> recipients = new LinkedHashSet<>();
        for (UserRole role : APPROVER_ROLES) {
            recipients.addAll(userFacade.findActiveUserIdsByRole(role));
        }
        if (recipients.isEmpty()) {
            log.warn("Invoice {} submitted but no active SALES_MANAGER/ADMIN to notify.",
                    event.invoiceId());
            return;
        }

        String repName = safeName(event.representativeId());
        String title   = "Invoice awaiting review";
        String message = repName + " submitted invoice #" + event.invoiceId() + " for review.";

        for (Long userId : recipients) {
            String sourceRef = "invoice:" + event.invoiceId() + ":SUBMITTED:user:" + userId;
            notificationService.create(
                    userId,
                    NotificationType.INVOICE,
                    title,
                    message,
                    sourceRef,
                    event.invoiceId());
        }
    }

    /** FR-103: notify the rep their invoice was approved. */
    @ApplicationModuleListener
    void on(InvoiceApprovedEvent event) {
        String sourceRef = "invoice:" + event.invoiceId() + ":APPROVED";
        notificationService.create(
                event.representativeId(),
                NotificationType.INVOICE,
                "Invoice approved",
                "Your invoice #" + event.invoiceId() + " has been approved.",
                sourceRef,
                event.invoiceId());
    }

    /** FR-104: notify the rep their invoice was rejected, with the reason. */
    @ApplicationModuleListener
    void on(InvoiceRejectedEvent event) {
        String sourceRef = "invoice:" + event.invoiceId() + ":REJECTED";
        notificationService.create(
                event.representativeId(),
                NotificationType.INVOICE,
                "Invoice rejected",
                "Your invoice #" + event.invoiceId() + " was rejected. Reason: " + event.reason(),
                sourceRef,
                event.invoiceId());
    }

    /** Rep name for the message, or a neutral fallback if the lookup fails. */
    private String safeName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (Exception e) {
            return "A representative";
        }
    }
}
