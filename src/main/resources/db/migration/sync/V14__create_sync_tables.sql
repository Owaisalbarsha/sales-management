-- src/main/resources/db/migration/sync/V14__create_sync_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- SYNC_QUEUE_ITEMS: the server-side ledger of every offline record a device
-- has pushed. Owned by the `sync` module — the single gateway for all mobile
-- offline data (R-9). One table, applied atomically. Mirrors the ERD
-- SYNC_QUEUE_ITEM block, with the additions justified below.
--
-- WHAT THIS TABLE IS FOR (Fork: sync owns a table). It is NOT a work queue the
-- server drains on a schedule — processing is synchronous inside the batch
-- request (Fork A), so an item never rests in PENDING on the server. It exists
-- for three jobs that have no other home:
--   1. IDEMPOTENCY LEDGER (FR-99, E3). A retried batch that re-sends an item
--      already SYNCED is recognised by client_uuid and skipped as success.
--   2. client_uuid → server-id REMAP (Fork D). An offline invoice references
--      its offline visit by the visit's client_uuid. After the VISIT item is
--      processed, its new server id is stored here (server_record_id); the
--      INVOICE item, processed later in the same batch (VISIT→INVOICE→GPS),
--      resolves the visit id by looking up this row. The queue table IS the
--      remap table — no separate structure needed.
--   3. DEAD-LETTER for corrupt/rejected items (E4). An unparseable or
--      business-rejected payload has no target table to live in; it rests here
--      as FAILED with an error_code the device reads and a warehouse/admin can
--      audit.
--
-- CLIENT_UUID IS A PER-RECORD IDEMPOTENCY KEY, NOT A DEVICE ID. The ERD comment
-- ("identifies the originating mobile device") is an error: the batch protocol
-- gives every item its own uuid, and invoices.client_uuid is already a
-- per-record key (V10). If it were per-device, dedup by client_uuid (FR-99,
-- R-9) would be impossible — every item from one phone would collide. UNIQUE
-- is global: a v4 UUID collision across devices is not a real risk.
--
-- IDEMPOTENCY IS LAYERED. This table's UNIQUE(client_uuid) is the sync-layer
-- guard; the target modules keep their own natural keys underneath
-- (invoices.client_uuid UK, gps_logs UNIQUE(rep, recorded_at), and the new
-- visits.client_uuid). Belt and braces: a device that resends after its local
-- SYNCED flag was lost still cannot create a duplicate in the target table.
--
-- REPRESENTATIVE_ID is taken from the JWT principal, never the payload (a rep
-- must not sync records as another rep). Cross-module FK declared HERE because
-- the DEPENDENT module owns it, same rule as V8/V11:
--   * sync_queue_items.representative_id → identity.users(id) (always SALES_REP)
--
-- PAYLOAD is jsonb, not text: Postgres validates it is well-formed JSON on
-- insert, so a byte-level corrupt payload is caught at the boundary rather
-- than deep in a parser. sync itself never introspects it beyond the fields
-- the target facade needs; it stays an opaque, self-contained record.
--
-- TIMESTAMPS. client_created_at is the device instant the record was captured
-- offline (the ERD CreatedAt); created_at (BaseEntity) is the server receipt
-- instant. Their difference is the offline lag, exactly as gps_logs keeps both
-- recorded_at and created_at (D6/D21).
--
-- RETRY (Fork G). retry_count records how many times the DEVICE has re-sent a
-- still-unsettled item; the device owns the durable queue and the backoff
-- (FR-92, E2). There is no server-side retry scheduler — it would only
-- duplicate the device queue. The server is idempotent and stateless per
-- request.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer · V5 inventory ·
--   V6 vanops · V7,V7_1 routing · V8 visit · V9 event-publication patch ·
--   V10 invoicing · V11 tracking · V12,V13 notification · V14 sync (this file).
-- ─────────────────────────────────────────────────────────────────────────


CREATE TABLE sync_queue_items (
    id                 BIGSERIAL     PRIMARY KEY,

    -- Per-record idempotency key generated on the device (v4 UUID, 36 chars).
    client_uuid        VARCHAR(36)   NOT NULL,

    -- Owner, from the JWT principal — never trusted from the payload.
    representative_id  BIGINT        NOT NULL,

    record_type        VARCHAR(20)   NOT NULL,
    operation_type     VARCHAR(20)   NOT NULL,

    -- Self-contained record, validated well-formed by the jsonb type on insert.
    payload            JSONB         NOT NULL,

    sync_status        VARCHAR(20)   NOT NULL,

    -- How many times the DEVICE re-sent this item before it settled (Fork G).
    retry_count        INT           NOT NULL DEFAULT 0,

    -- Device capture instant (ERD CreatedAt). Offline lag = created_at - client_created_at.
    client_created_at  TIMESTAMPTZ   NOT NULL,

    -- Id of the record created in the target module (invoice id / visit id).
    -- NULL for GPS (no addressable id) and for items not yet SYNCED. Backs the
    -- client_uuid → server-id remap (Fork D).
    server_record_id   BIGINT,

    -- Terminal-failure surface for the device and the dead-letter (E4). NULL
    -- unless sync_status = FAILED.
    error_code         VARCHAR(64),
    error_detail       TEXT,

    -- BaseEntity. created_at = server receipt instant. updated_at = last
    -- settlement (SYNCED/FAILED). Both present because every entity extends BaseEntity.
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_sync_queue_items_representative
        FOREIGN KEY (representative_id) REFERENCES users (id),

    -- The idempotency key (FR-99, E3). Its unique index also serves the
    -- client_uuid → server-id remap lookup (Fork D), so no extra index is needed.
    CONSTRAINT uq_sync_queue_items_client_uuid
        UNIQUE (client_uuid),

    CONSTRAINT chk_sync_queue_items_record_type
        CHECK (record_type IN ('INVOICE', 'VISIT', 'GPS_LOG')),
    CONSTRAINT chk_sync_queue_items_operation_type
        CHECK (operation_type IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT chk_sync_queue_items_sync_status
        CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED')),

    -- A settled item's shape is enforced: SYNCED carries no error; FAILED must
    -- carry a code so the device and the dead-letter always have a reason.
    CONSTRAINT chk_sync_queue_items_terminal_shape
        CHECK (
            (sync_status = 'FAILED'  AND error_code IS NOT NULL)
         OR (sync_status <> 'FAILED' AND error_code IS NULL)
        )
);

-- Audit/scoping reads ("this rep's synced items") and the FK check.
CREATE INDEX idx_sync_queue_items_representative ON sync_queue_items (representative_id);

-- Dead-letter review: managers/admin list FAILED items to reconcile (E4).
-- Partial index keeps it tiny — only the exceptions, not the SYNCED majority.
CREATE INDEX idx_sync_queue_items_failed
    ON sync_queue_items (representative_id)
    WHERE sync_status = 'FAILED';

COMMENT ON TABLE  sync_queue_items                   IS 'Server-side ledger of offline records pushed by mobile devices. Idempotency ledger (FR-99), client_uuid->server-id remap (Fork D), and dead-letter for rejected items (E4). Not a server work queue: processing is synchronous within the batch request.';
COMMENT ON COLUMN sync_queue_items.client_uuid       IS 'Per-record idempotency key generated on the device (v4 UUID). The dedup key for FR-99/E3. NOTE: the ERD comment calling this a device id is an error.';
COMMENT ON COLUMN sync_queue_items.representative_id IS 'FK to users.id — the SALES_REP that owns the record, taken from the JWT principal, never from the payload.';
COMMENT ON COLUMN sync_queue_items.record_type       IS 'Polymorphic router: INVOICE | VISIT | GPS_LOG. Determines which write facade processes the payload.';
COMMENT ON COLUMN sync_queue_items.operation_type    IS 'CREATE | UPDATE | DELETE. v1 supports CREATE and UPDATE (visit check-out); DELETE is a no-op (Fork H).';
COMMENT ON COLUMN sync_queue_items.payload            IS 'Self-contained JSON of the offline record. jsonb so malformed JSON is rejected at insert. Opaque to sync beyond the fields the target facade parses.';
COMMENT ON COLUMN sync_queue_items.sync_status        IS 'PENDING (transient, in-flight) | SYNCED (processed; server_record_id set for INVOICE/VISIT) | FAILED (terminal rejection; error_code set).';
COMMENT ON COLUMN sync_queue_items.retry_count        IS 'How many times the DEVICE re-sent this still-unsettled item (Fork G: device owns retry/backoff). No server-side scheduler.';
COMMENT ON COLUMN sync_queue_items.client_created_at  IS 'UTC instant the device captured the record offline (ERD CreatedAt). Offline lag = created_at - client_created_at.';
COMMENT ON COLUMN sync_queue_items.server_record_id   IS 'Id of the record created in the target module (invoice/visit). NULL for GPS and unprocessed items. Backs the client_uuid->server-id remap (Fork D).';
COMMENT ON COLUMN sync_queue_items.error_code         IS 'Machine-readable terminal-failure code returned to the device and shown in the dead-letter (E4). NULL unless FAILED.';
COMMENT ON COLUMN sync_queue_items.error_detail       IS 'Human-readable failure detail for audit. NULL unless FAILED.';
