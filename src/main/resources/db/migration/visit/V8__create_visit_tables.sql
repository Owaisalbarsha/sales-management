-- src/main/resources/db/migration/visit/V8__create_visit_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- VISIT: field visits to customers — check-in, check-out, and missed stops.
-- Owned by the `visit` module.
--
-- One table, applied atomically. Mirrors the ERD VISIT block: customer,
-- representative, route, check-in/out time and location, and status.
--
-- Cross-module FKs declared HERE because the DEPENDENT module owns them:
--   * visits.customer_id       → customer.customers(id)
--   * visits.representative_id → identity.users(id)   (always a SALES_REP)
--   * visits.route_id          → routing.routes(id)
-- The SALES_REP role condition is enforced in the application layer (JWT +
-- @PreAuthorize); a DB FK cannot express role.
--
-- Nullable columns are deliberate — which are populated depends on status:
--   IN_PROGRESS : check_in_* set, check_out_* null
--   COMPLETED   : all set
--   MISSED      : all null (stop was never visited)
-- The service enforces the per-status shape.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops · V7 routing · V8 visit (this file).
-- ─────────────────────────────────────────────────────────────────────────


CREATE TABLE visits (
                        id                 BIGSERIAL    PRIMARY KEY,
                        customer_id        BIGINT       NOT NULL,
                        representative_id  BIGINT       NOT NULL,
                        route_id           BIGINT       NOT NULL,

                        check_in_time      TIMESTAMPTZ,
                        check_in_location  VARCHAR(64),
                        check_out_time     TIMESTAMPTZ,
                        check_out_location VARCHAR(64),

                        status             VARCHAR(20)  NOT NULL,

                        created_at         TIMESTAMPTZ  NOT NULL,
                        updated_at         TIMESTAMPTZ  NOT NULL,

                        CONSTRAINT fk_visits_customer
                            FOREIGN KEY (customer_id)       REFERENCES customers (id),
                        CONSTRAINT fk_visits_representative
                            FOREIGN KEY (representative_id)  REFERENCES users (id),
                        CONSTRAINT fk_visits_route
                            FOREIGN KEY (route_id)           REFERENCES routes (id),

    -- One visit per stop. Backs the duplicate-check-in guard (409 in the service)
    -- and makes a second check-in for the same (route, customer) impossible at the DB.
                        CONSTRAINT uq_visits_route_customer
                            UNIQUE (route_id, customer_id),

                        CONSTRAINT chk_visits_status
                            CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'MISSED'))
);

-- route_id is the LEADING column of uq_visits_route_customer, so that unique index
-- already covers "all visits on a route" lookups and the route-side FK check.
-- customer_id and representative_id need their own indexes for their FK checks and
-- for the rep's "my visits" listing; status supports filtered reads.
CREATE INDEX idx_visits_customer       ON visits (customer_id);
CREATE INDEX idx_visits_representative ON visits (representative_id);
CREATE INDEX idx_visits_status         ON visits (status);

COMMENT ON TABLE  visits                    IS 'Field visits to customers: check-in, check-out, and missed stops. One row per (route, customer), created at check-in or materialised as MISSED at end-of-day/sweep.';
COMMENT ON COLUMN visits.customer_id        IS 'FK to customers.id — the customer visited.';
COMMENT ON COLUMN visits.representative_id  IS 'FK to users.id — the SALES_REP (enforced in the application layer).';
COMMENT ON COLUMN visits.route_id           IS 'FK to routes.id — the route this stop belongs to.';
COMMENT ON COLUMN visits.check_in_time      IS 'Client-supplied UTC instant of check-in. NULL for a MISSED visit.';
COMMENT ON COLUMN visits.check_in_location  IS 'GPS at check-in as "lat,lng". NULL for a MISSED visit.';
COMMENT ON COLUMN visits.check_out_time     IS 'Client-supplied UTC instant of check-out. NULL until checked out.';
COMMENT ON COLUMN visits.check_out_location IS 'GPS at check-out as "lat,lng". NULL until checked out.';
COMMENT ON COLUMN visits.status             IS 'Lifecycle: IN_PROGRESS (checked in), COMPLETED (checked out; terminal), MISSED (never visited; terminal).';
