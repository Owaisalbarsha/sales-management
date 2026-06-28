-- src/main/resources/db/migration/routing/V7__create_routing_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- ROUTING: a rep's daily visit schedule (routes) and its ordered stops
-- (route_customer_assignments). Owned by the `routing` module.
--
-- One migration, two tables, applied atomically. The header (routes) is created
-- before its stop table.
--
-- Cross-module FKs declared HERE because the DEPENDENT module owns them:
--   * routes.representative_id              → identity.users(id)
--   * routes.territory_id                   → territory.territories(id)
--   * route_customer_assignments.customer_id → customer.customers(id)
-- The rep's SALES_REP role is enforced in the application layer (RouteService via
-- UserFacade.getRoleById); a DB FK cannot express role.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops · V7 routing (this file).
--
-- TWO DELIBERATE DEVIATIONS FROM THE ERD (both agreed):
--   1. route_customer_assignments uses a SURROGATE id (BIGSERIAL) + a
--      UNIQUE(route_id, customer_id) constraint, instead of the ERD's composite
--      PK (route_id, customer_id). Same integrity; keeps every entity on the
--      shared BaseEntity (id + audit columns) and lets `visit` reference a stop
--      by a single id.
--   2. routes carries a territory_id column, which is not in the ERD's ROUTE
--      block. It is the only way to enforce "all stops in one territory" — there
--      is no rep→territory link anywhere in the schema.
--
-- ONE ADDED CONSTRAINT (not in the ERD, flagged): UNIQUE(representative_id,
-- route_date) — one route per rep per day. Makes RoutingFacade.getRouteForToday
-- deterministic and matches the one-daily-route FMCG model. Drop this single
-- line if multiple routes per rep per day are ever needed.
-- ─────────────────────────────────────────────────────────────────────────


-- ===== ROUTES (header) ====================================================
CREATE TABLE routes (
                        id                BIGSERIAL    PRIMARY KEY,
                        representative_id BIGINT       NOT NULL,
                        territory_id      BIGINT       NOT NULL,
                        name              VARCHAR(120) NOT NULL,
                        route_date        DATE         NOT NULL,
                        status            VARCHAR(30)  NOT NULL,
                        is_optimized      BOOLEAN      NOT NULL DEFAULT FALSE,

                        created_at        TIMESTAMPTZ  NOT NULL,
                        updated_at        TIMESTAMPTZ  NOT NULL,

                        CONSTRAINT fk_routes_representative
                            FOREIGN KEY (representative_id) REFERENCES users (id),
                        CONSTRAINT fk_routes_territory
                            FOREIGN KEY (territory_id)      REFERENCES territories (id),

                        CONSTRAINT chk_routes_status
                            CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),

    -- One route per rep per day (see header note). Drop to allow multiple.
                        CONSTRAINT uq_routes_representative_date
                            UNIQUE (representative_id, route_date)
);

CREATE INDEX idx_routes_representative ON routes (representative_id);
CREATE INDEX idx_routes_territory      ON routes (territory_id);
CREATE INDEX idx_routes_route_date     ON routes (route_date);
CREATE INDEX idx_routes_status         ON routes (status);

COMMENT ON TABLE  routes                   IS 'A sales rep''s daily visit schedule: a sequenced list of customer stops.';
COMMENT ON COLUMN routes.representative_id IS 'FK to users.id. Must reference a SALES_REP (enforced in the application layer).';
COMMENT ON COLUMN routes.territory_id      IS 'FK to territories.id. Every stop on the route must belong to this territory.';
COMMENT ON COLUMN routes.route_date        IS 'Business date this route is for.';
COMMENT ON COLUMN routes.status            IS 'Lifecycle: PLANNED (created, editable), ACTIVE (rep executing it), COMPLETED (terminal).';
COMMENT ON COLUMN routes.is_optimized      IS 'TRUE when sequence numbers were auto-computed; reset to FALSE on any manual stop change.';


-- ===== ROUTE_CUSTOMER_ASSIGNMENTS (stops) =================================
CREATE TABLE route_customer_assignments (
                                            id              BIGSERIAL   PRIMARY KEY,
                                            route_id        BIGINT      NOT NULL,
                                            customer_id     BIGINT      NOT NULL,
                                            sequence_number INTEGER     NOT NULL,

                                            created_at      TIMESTAMPTZ NOT NULL,
                                            updated_at      TIMESTAMPTZ NOT NULL,

                                            CONSTRAINT fk_route_customer_assignments_route
                                                FOREIGN KEY (route_id)    REFERENCES routes (id)    ON DELETE CASCADE,
                                            CONSTRAINT fk_route_customer_assignments_customer
                                                FOREIGN KEY (customer_id) REFERENCES customers (id),

    -- One stop per (route, customer) — the surrogate-key replacement for the
    -- ERD's composite PK. Prevents the same customer appearing twice on a route.
                                            CONSTRAINT uq_route_customer_assignments_route_customer
                                                UNIQUE (route_id, customer_id),

    -- Visit order is a positive integer. NOT unique per route on purpose, so a
    -- bulk reorder can rewrite all rows without transient collisions.
                                            CONSTRAINT chk_route_customer_assignments_sequence
                                                CHECK (sequence_number > 0)
);

-- route_id is the LEADING column of uq_route_customer_assignments_route_customer,
-- so that unique index already covers FK integrity and "all stops of a route"
-- lookups. customer_id needs its own index for the customer-side FK check.
CREATE INDEX idx_route_customer_assignments_customer ON route_customer_assignments (customer_id);

COMMENT ON TABLE  route_customer_assignments                 IS 'Ordered stops of a route: which customers, in which visit order.';
COMMENT ON COLUMN route_customer_assignments.route_id        IS 'FK to routes.id. ON DELETE CASCADE: deleting a route deletes its stops.';
COMMENT ON COLUMN route_customer_assignments.customer_id     IS 'FK to customers.id.';
COMMENT ON COLUMN route_customer_assignments.sequence_number IS 'Visit order on the route (> 0). Rewritten by reorder / optimise.';
