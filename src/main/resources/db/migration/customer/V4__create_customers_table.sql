-- src/main/resources/db/migration/customer/V4__create_customers_table.sql

-- ─────────────────────────────────────────────────────────────────────────
-- CUSTOMER: operational data. An outlet that belongs to exactly one territory
-- and is visited and invoiced by sales representatives. Owned by the `customer`
-- module.
--
-- This migration declares the FK to TERRITORIES because the DEPENDENT module
-- owns the constraint — the territory module never ALTERs this table, and the
-- customer module never ALTERs territories. The territory module relies on this
-- FK to reject deletion of a territory that still has customers (it catches the
-- resulting integrity violation and returns a clean 409).
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer (this file).
--
-- Note: `territory_id` is a plain column with a FK, mirrored in JPA by a plain
-- `Long territoryId` field (no @ManyToOne) — IDs cross module boundaries,
-- object references do not.
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE customers (
                           id           BIGSERIAL     PRIMARY KEY,
                           territory_id BIGINT        NOT NULL,
                           name         VARCHAR(150)  NOT NULL,
                           address      VARCHAR(500),
                           phone        VARCHAR(30),
                           latitude     NUMERIC(9, 6),
                           longitude    NUMERIC(9, 6),
                           category     VARCHAR(30)   NOT NULL,
                           status       VARCHAR(30)   NOT NULL DEFAULT 'ACTIVE',

    -- BaseEntity audit columns (UTC instants, written by @PrePersist/@PreUpdate)
                           created_at   TIMESTAMPTZ   NOT NULL,
                           updated_at   TIMESTAMPTZ   NOT NULL,

                           CONSTRAINT fk_customers_territory
                               FOREIGN KEY (territory_id) REFERENCES territories (id),

    -- Status is a stable two-value domain → safe to enforce at the DB level too.
    -- Category is deliberately NOT constrained here so its enum can grow without
    -- a migration; it is validated at the application layer.
                           CONSTRAINT chk_customers_status
                               CHECK (status IN ('ACTIVE', 'INACTIVE')),

                           CONSTRAINT chk_customers_latitude
                               CHECK (latitude  IS NULL OR latitude  BETWEEN -90  AND 90),
                           CONSTRAINT chk_customers_longitude
                               CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

-- PostgreSQL does NOT auto-index FK columns. This index supports both the
-- territory-delete integrity check and the `?territoryId=` list filter.
CREATE INDEX idx_customers_territory_id ON customers (territory_id);

-- Supports the `?status=` list filter (e.g. listing only ACTIVE customers).
CREATE INDEX idx_customers_status ON customers (status);

COMMENT ON TABLE  customers              IS 'Customers (operational data). Each belongs to exactly one territory.';
COMMENT ON COLUMN customers.territory_id IS 'FK to territories.id. The owning territory; mandatory.';
COMMENT ON COLUMN customers.name         IS 'Customer / outlet name. Not unique (same name may exist in different territories).';
COMMENT ON COLUMN customers.latitude     IS 'GPS latitude (-90..90). Nullable until captured on first visit.';
COMMENT ON COLUMN customers.longitude    IS 'GPS longitude (-180..180). Nullable until captured on first visit.';
COMMENT ON COLUMN customers.category     IS 'Outlet classification enum (CustomerCategory), stored as text.';
COMMENT ON COLUMN customers.status       IS 'Lifecycle status: ACTIVE or INACTIVE. INACTIVE customers cannot be invoiced (FR-95).';