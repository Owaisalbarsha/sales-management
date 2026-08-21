package com.salesmanagement.sync.internal.enums;

/**
 * The operation an offline record represents against the server (ERD
 * {@code SYNC_QUEUE_ITEM.OperationType}).
 *
 * <p><strong>v1 scope (Fork H).</strong> {@link #CREATE} and {@link #UPDATE} are
 * live; {@link #DELETE} is accepted by the schema but treated as a no-op by the
 * service. No current offline flow produces a server-side delete: an offline-only
 * DRAFT invoice deleted before it ever synced never reached the server, so there
 * is nothing to delete. DELETE is kept in the type only so the enum matches the
 * ERD and a future tombstone flow needs no migration.</p>
 */
public enum OperationType {

    /**
     * Insert a new record. The dominant case: a new offline invoice, a visit
     * check-in, a GPS batch.
     */
    CREATE,

    /**
     * Mutate an existing record. The one live use is a visit check-out that
     * arrives after its check-in was already synced in an earlier batch — the
     * check-in CREATEd the visit, the check-out UPDATEs it to COMPLETED.
     */
    UPDATE,

    /**
     * Reserved. Accepted by the schema for ERD parity but a no-op in v1 — no
     * offline flow deletes a server record. See the class note.
     */
    DELETE
}
