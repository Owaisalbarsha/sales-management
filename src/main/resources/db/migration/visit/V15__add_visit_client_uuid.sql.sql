-- src/main/resources/db/migration/visit/V15__add_visit_client_uuid.sql

-- ─────────────────────────────────────────────────────────────────────────
-- Adds the offline idempotency key to VISIT. Owned by the `visit` module.
--
-- WHY (Fork A/D, FR-99). Of the three offline entities, VISIT was the only one
-- without a per-record idempotency key: invoices already carry client_uuid
-- (V10) and gps_logs dedup on (representative_id, recorded_at) (V11). Without
-- one, a re-sent offline visit would create a second row (the existing
-- UNIQUE(route_id, customer_id) catches a duplicate *stop*, but not a genuine
-- resend of the same captured visit before its status settled). This column is
-- the key sync deduplicates on, and the target sync writes its server id back
-- against for the invoice→visit remap (Fork D).
--
-- NULLABLE + PARTIAL UNIQUE. Online visits (check-in via REST) have no
-- client_uuid — the column is null for them. Only offline-synced visits carry
-- one, and among those it must be unique. A partial unique index
-- (WHERE client_uuid IS NOT NULL) enforces exactly that while allowing many
-- null rows, the same shape notification uses for its source_ref dedup key.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   ... V13 notification · V14 sync · V15 visit client_uuid (this file).
-- ─────────────────────────────────────────────────────────────────────────

ALTER TABLE visits
    ADD COLUMN client_uuid VARCHAR(36);

CREATE UNIQUE INDEX uq_visits_client_uuid
    ON visits (client_uuid)
    WHERE client_uuid IS NOT NULL;

COMMENT ON COLUMN visits.client_uuid IS 'Per-record offline idempotency key (v4 UUID) for visits created via sync; NULL for online visits. Partial-unique WHERE NOT NULL. Sync deduplicates on it (FR-99) and remaps offline invoice->visit references through it (Fork D).';
