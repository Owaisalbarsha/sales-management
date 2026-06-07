-- src/main/resources/db/migration/territory/V3__create_territories_table.sql

-- ─────────────────────────────────────────────────────────────────────────
-- TERRITORY: reference data. A geographic sales area that customers belong to.
-- Owned by the `territory` module. CUSTOMER.territory_id references this table
-- (FK declared in the customer module's migration, NOT here — each module owns
-- only its own DDL, never ALTERs another module's table).
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE territories (
                             id          BIGSERIAL    PRIMARY KEY,
                             name        VARCHAR(100) NOT NULL,
                             description VARCHAR(500),

    -- BaseEntity audit columns (UTC instants, written by @PrePersist/@PreUpdate)
                             created_at  TIMESTAMPTZ  NOT NULL,
                             updated_at  TIMESTAMPTZ  NOT NULL,

                             CONSTRAINT uq_territories_name UNIQUE (name)
);

-- Territory names are looked up and listed frequently by customer/reporting.
-- The UNIQUE constraint already creates an index on name, so no extra index needed.

COMMENT ON TABLE  territories             IS 'Sales territories (reference data). Customers belong to exactly one.';
COMMENT ON COLUMN territories.name        IS 'Human-readable territory name. Unique across the system.';
COMMENT ON COLUMN territories.description IS 'Optional free-text description of the territory boundaries/notes.';