package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.invoicing.api.InvoiceApprovedEvent;
import com.salesmanagement.invoicing.api.InvoiceRejectedEvent;
import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Raises notifications for invoice review outcomes (FR-103, FR-104).
 *
 * <p>Consumes the events {@code invoicing} already publishes on approve/reject.
 * Each handler is an {@link ApplicationModuleListener}: it runs asynchronously
 * after the invoicing transaction commits, in its own transaction, and is backed
 * by Spring Modulith's event-publication log — so a failure here is retried
 * without affecting the invoice, and the notification is never silently lost.</p>
 *
 * <p><strong>FR-114 dedup.</strong> Each handler builds a deterministic
 * {@code sourceRef} ({@code "invoice:{id}:APPROVED"} / {@code ":REJECTED"}); a
 * retried delivery of the same event collides on the partial-unique index and is
 * a no-op inside {@link NotificationService#create}.</p>
 *
 * <p>The recipient is the invoice's {@code representativeId}, carried on the event
 * — the rep who raised it is the one told of its outcome. {@code referenceId} is
 * the invoice id, so the client can deep-link straight to it (FR-105).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceNotificationListener {

    private final NotificationService notificationService;

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
}
