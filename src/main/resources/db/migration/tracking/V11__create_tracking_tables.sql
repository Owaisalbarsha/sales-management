-- src/main/resources/db/migration/tracking/V11__create_tracking_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- GPS_LOGS: periodic location fixes captured by the rep's device.
-- Owned by the `tracking` module.
--
-- One table, applied atomically. Mirrors the ERD GPS_LOG block exactly:
-- representative, latitude, longitude, timestamp. No columns were added
-- beyond the ERD (D11) — no accuracy, speed, source, visit_id or client_uuid.
--
-- Cross-module FK declared HERE because the DEPENDENT module owns it:
--   * gps_logs.representative_id → identity.users(id)   (always a SALES_REP)
-- The SALES_REP role condition is enforced in the application layer (JWT +
-- @PreAuthorize); a DB FK cannot express role. Same rule as V8 (visits).
--
-- WRITE PATH (D4, D15): points arrive at POST /api/tracking/gps as a batch,
-- always. Once the `sync` module exists it will call TrackingFacade.ingest()
-- rather than reimplementing this path, so there is exactly one writer.
--
-- DEDUPLICATION (D10, FR-60): UNIQUE (representative_id, recorded_at) is the
-- natural idempotency key. No client_uuid column, because a rep cannot be in
-- two places at the same instant. ASSUMPTION: one active device per rep,
-- guaranteed today by single-session-per-user enforcement in `identity`. If
-- multi-device is ever allowed, this constraint must be revisited.
--
-- TIMESTAMPS (D6, D21): `recorded_at` is the device capture instant (client
-- sends ISO 8601 with an explicit offset); `created_at` (from BaseEntity) is
-- the server receipt instant. The difference is the offline lag of the point,
-- which is why both are kept. `recorded_at` is truncated to MILLISECONDS on
-- ingest so the Java-side dedup pre-filter compares the same precision the
-- database stores (PostgreSQL timestamps hold microseconds; java.time.Instant
-- holds nanoseconds).
--
-- Column is named recorded_at, not "timestamp": TIMESTAMP is an SQL reserved
-- word (https://www.postgresql.org/docs/current/sql-keywords-appendix.html).
--
-- INDEXES (D12): none beyond the unique constraint. Its index leads on
-- representative_id, which serves both reads this module has:
--   * trail        : WHERE representative_id = ? AND recorded_at >= ? AND < ?
--   * live map     : DISTINCT ON (representative_id) ORDER BY representative_id,
--                    recorded_at DESC
-- Extra indexes on an append-heavy table with no query to serve are pure
-- insert cost.
--
-- RETENTION (D13): none. ~24k rows/day at 50 reps and a 60s capture interval
-- (~9M rows/yr, well under 1GB including the index). No purge job in v1. If a
-- retention policy is ever introduced, the migration path is RANGE
-- partitioning on recorded_at and DROP of old partitions, not DELETE
-- (https://www.postgresql.org/docs/current/ddl-partitioning.html).
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops · V7,V7_1 routing · V8 visit ·
--   V9 event publication patch · V10 invoicing · V11 tracking (this file).
-- ─────────────────────────────────────────────────────────────────────────


CREATE TABLE gps_logs (
                          id                 BIGSERIAL      PRIMARY KEY,
                          representative_id  BIGINT         NOT NULL,

    -- NUMERIC, not float: exact decimal, no binary rounding drift.
    -- 6 decimal places ≈ 0.11 m at the equator, far finer than the 10 m CEP
    -- the SRS requires. Same type as epod_artifacts (V10).
                          latitude           NUMERIC(9,6)   NOT NULL,
                          longitude          NUMERIC(9,6)   NOT NULL,

    -- Device capture instant. Truncated to milliseconds by the service.
                          recorded_at        TIMESTAMPTZ    NOT NULL,

    -- BaseEntity. created_at is the server receipt instant (offline lag =
    -- created_at - recorded_at). updated_at is unused: rows are append-only
    -- and never mutated. Kept for consistency with every other entity.
                          created_at         TIMESTAMPTZ    NOT NULL,
                          updated_at         TIMESTAMPTZ    NOT NULL,

                          CONSTRAINT fk_gps_logs_representative
                              FOREIGN KEY (representative_id) REFERENCES users (id),

    -- FR-60: a rep cannot be in two places at the same instant. This is the
    -- idempotency key for offline resends; the service pre-filters against it
    -- and also catches the violation to cover the concurrent-retry race.
                          CONSTRAINT uq_gps_logs_rep_recorded_at
                              UNIQUE (representative_id, recorded_at),

    -- Defence in depth only. The authoritative validation is @DecimalMin /
    -- @DecimalMax on the request DTO, which returns a 400 with a usable
    -- message; these constraints exist so a bad row cannot enter through any
    -- other path (seeder, manual SQL, a future importer).
                          CONSTRAINT chk_gps_logs_latitude
                              CHECK (latitude  BETWEEN -90  AND 90),
                          CONSTRAINT chk_gps_logs_longitude
                              CHECK (longitude BETWEEN -180 AND 180)
);

COMMENT ON TABLE  gps_logs                    IS 'Periodic GPS fixes captured by a sales rep''s device during field work. Append-only; rows are never updated or deleted. Ingested in batches (online or on reconnect) via POST /api/tracking/gps.';
COMMENT ON COLUMN gps_logs.representative_id  IS 'FK to users.id — the SALES_REP who produced this fix (taken from the JWT principal, never from the request body).';
COMMENT ON COLUMN gps_logs.latitude           IS 'WGS84 latitude, NUMERIC(9,6) (~0.11 m resolution). Range enforced by chk_gps_logs_latitude.';
COMMENT ON COLUMN gps_logs.longitude          IS 'WGS84 longitude, NUMERIC(9,6). Range enforced by chk_gps_logs_longitude.';
COMMENT ON COLUMN gps_logs.recorded_at        IS 'UTC instant the device captured the fix. Client sends ISO 8601 with an explicit offset; truncated to milliseconds on ingest. Part of the dedup key.';
COMMENT ON COLUMN gps_logs.created_at         IS 'UTC instant the server persisted the row. created_at - recorded_at is the offline lag of this point.';
COMMENT ON COLUMN gps_logs.updated_at         IS 'Unused — gps_logs rows are immutable. Present because every entity extends BaseEntity.';
