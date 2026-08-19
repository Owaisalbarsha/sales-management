package com.salesmanagement.sync.internal.enums;

/**
 * The kinds of offline record a device may push, and the priority order in which
 * a batch is processed.
 *
 * <p>The set is fixed by the ERD ({@code SYNC_QUEUE_ITEM.RecordType}) and FR-91.
 * It is deliberately closed: sync is the single gateway for offline data (R-9),
 * and adding a fourth type is a design change, not a configuration one.</p>
 *
 * <p><strong>Processing order (Fork D, not FR-91 literally).</strong> FR-91 lists
 * INVOICE → VISIT → GPS as a <em>business</em> priority (get the money records up
 * first). But an offline invoice may reference an offline visit by that visit's
 * {@code clientUuid}, so the visit must exist server-side before the invoice can
 * resolve it. Referential dependency overrides business priority: the batch is
 * processed {@link #VISIT} → {@link #INVOICE} → {@link #GPS_LOG}. The
 * {@link #priority()} order below encodes that.</p>
 */
public enum RecordType {

    /**
     * A field visit (check-in / check-out). Processed first so an invoice raised
     * during the same offline visit can resolve its {@code visitId} from the
     * visit's freshly-assigned server id.
     */
    VISIT(0),

    /**
     * A completed offline sale (create + submit collapsed; arrives SENT).
     * Processed after visits so its optional visit reference resolves.
     */
    INVOICE(1),

    /**
     * A batch of GPS fixes. Processed last: nothing references it, and it is the
     * highest-volume, lowest-value-per-row type, so it must never delay the
     * revenue records ahead of it.
     */
    GPS_LOG(2);

    private final int priority;

    RecordType(int priority) {
        this.priority = priority;
    }

    /** Lower runs first. Encodes the VISIT → INVOICE → GPS_LOG dependency order (Fork D). */
    public int priority() {
        return priority;
    }
}
