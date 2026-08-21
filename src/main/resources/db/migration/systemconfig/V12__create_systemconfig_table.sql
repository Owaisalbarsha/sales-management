-- src/main/resources/db/migration/systemconfig/V12__create_systemconfig_table.sql

-- ─────────────────────────────────────────────────────────────────────────
-- SYSTEM_CONFIG: admin-editable runtime settings, owned by the `systemconfig`
-- module. Mirrors the ERD SYSTEM_CONFIG block (Key, Value, Description,
-- LastUpdated) with two deliberate deviations, both justified below.
--
-- THIS IS AN OVERRIDE TABLE, NOT A SOURCE OF TRUTH (D-B4/B5).
-- The table is created EMPTY. No seed rows. A key is present here ONLY when an
-- admin has overridden it. Consumers read through ConfigFacade.getInt(key,
-- codeDefault): a missing row returns the caller's own compiled-in default.
-- Consequences that make this the right design for a graduation demo:
--   * empty table is a valid, working state — nothing breaks on a fresh DB
--   * the code default is the safe fallback, so a wiped table degrades to
--     documented behaviour instead of failing
--   * an admin edit takes effect on the NEXT read (no restart) — this is the
--     live-update property the module exists to demonstrate
--
-- DEVIATION 1 — surrogate PK instead of the ERD's `Key PK` (D-B1).
-- The ERD models Key as the natural primary key. Every other entity in this
-- system extends BaseEntity, which mandates `@Id Long id` (BIGSERIAL). A
-- natural varchar PK cannot extend BaseEntity as written. The hard rule
-- "BaseEntity for all entities" wins over mirroring one ERD box, so:
--   id           BIGSERIAL PK   (from BaseEntity)
--   config_key   UNIQUE         (the real identity, what the ERD called Key)
-- Uniqueness of the key is preserved by the UNIQUE constraint; nothing is lost.
--
-- DEVIATION 2 — value_type column, not in the ERD (D-B2).
-- The ERD stores Value as a bare string. Every real setting has a type
-- (TRACKING_ACTIVE_WINDOW_MINUTES is an int). A single varchar with no declared
-- type pushes parsing to every consumer and turns a bad admin edit into a parse
-- crash deep in another module. value_type is validated on WRITE by the service,
-- so a malformed value is rejected at the config endpoint (400) rather than
-- surfacing as a NumberFormatException in tracking. Only INT exists today;
-- BOOLEAN / DURATION_ISO can be added additively when a consumer needs them.
--
-- RESERVED WORDS: columns are config_key / config_value, not key / value. Both
-- `key` and `value` are SQL/PostgreSQL reserved words
-- (https://www.postgresql.org/docs/current/sql-keywords-appendix.html) — same
-- reason V11 named its column recorded_at rather than "timestamp".
--
-- TIMESTAMPS: the ERD's LastUpdated is satisfied by BaseEntity.updated_at (set
-- on every @PreUpdate). No separate column, same decision as V11 dropping
-- GPS_LOG.LastUpdated in favour of the inherited column.
--
-- SECURITY: writes are ADMIN-only, reads are ADMIN/SALES_MANAGER over HTTP and
-- unguarded via the facade for cross-module calls. Enforced in the application
-- layer (@PreAuthorize on the controller); a DB table cannot express role.
--
-- INDEXES: none beyond the unique constraint. Its index on config_key serves
-- the only lookup this module has (fetch one key by name). The table is tiny
-- (one row per overridden setting, single digits in practice) — any extra index
-- is pure write cost with no read to serve.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops · V7,V7_1 routing · V8 visit ·
--   V9 event publication patch · V10 invoicing · V11 tracking ·
--   V12 systemconfig (this file).
-- ─────────────────────────────────────────────────────────────────────────


CREATE TABLE system_config (
                               id            BIGSERIAL      PRIMARY KEY,

    -- The setting identity — what the ERD called Key. UNIQUE, not PK, because
    -- BaseEntity owns the surrogate id. Application-layer keys are SCREAMING_SNAKE
    -- (e.g. TRACKING_ACTIVE_WINDOW_MINUTES); no DB constraint enforces the casing,
    -- it is a convention held in the ConfigKey constants class.
                               config_key    VARCHAR(100)   NOT NULL,

    -- Stored as text regardless of value_type; the service parses/validates on
    -- write against value_type and the facade parses on read. Wide enough for an
    -- INT today and an ISO-8601 duration or JSON scalar tomorrow.
                               config_value  VARCHAR(255)   NOT NULL,

    -- Declared type of config_value. Validated on write so a consumer never
    -- receives an unparseable value. INT is the only member today; the CHECK
    -- lists the currently-supported set explicitly so an unknown type cannot be
    -- inserted through any path (endpoint, seeder, manual SQL).
                               value_type    VARCHAR(20)    NOT NULL,

    -- Human-facing note on what this key controls (ERD Description). Optional.
                               description   VARCHAR(500),

    -- BaseEntity. created_at = first override written for this key. updated_at =
    -- last admin edit (the ERD's LastUpdated). Both maintained by BaseEntity's
    -- @PrePersist / @PreUpdate.
                               created_at    TIMESTAMPTZ    NOT NULL,
                               updated_at    TIMESTAMPTZ    NOT NULL,

    -- The real identity constraint. One row per key; upsert-by-key semantics in
    -- the service rely on this to distinguish insert-new from update-existing.
                               CONSTRAINT uq_system_config_key
                                   UNIQUE (config_key),

    -- Defence in depth. The authoritative validation is in the service (reject
    -- unknown type / unparseable value with a 400). This constraint guarantees
    -- no row with an unsupported type can enter through the seeder or manual SQL.
                               CONSTRAINT chk_system_config_value_type
                                   CHECK (value_type IN ('INT'))
);

COMMENT ON TABLE  system_config               IS 'Admin-editable runtime settings. OVERRIDE table: a key is present only when an admin has overridden its compiled-in default. Consumers fall back to a code default when a key is absent, so an empty table is a valid working state.';
COMMENT ON COLUMN system_config.config_key    IS 'The setting name (ERD Key). UNIQUE. SCREAMING_SNAKE by convention, e.g. TRACKING_ACTIVE_WINDOW_MINUTES. Not the PK — BaseEntity owns the surrogate id.';
COMMENT ON COLUMN system_config.config_value  IS 'The overridden value as text. Parsed against value_type on read. Validated on write.';
COMMENT ON COLUMN system_config.value_type    IS 'Declared type of config_value, validated on write. INT only today; extend the CHECK additively (BOOLEAN, DURATION_ISO) when a consumer needs it.';
COMMENT ON COLUMN system_config.description   IS 'Optional human-facing note on what this key controls (ERD Description).';
COMMENT ON COLUMN system_config.created_at    IS 'UTC instant this key was first overridden.';
COMMENT ON COLUMN system_config.updated_at    IS 'UTC instant of the last admin edit to this key (ERD LastUpdated).';
