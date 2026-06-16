-- src/main/resources/db/migration/vanops/V6__create_vanops_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- VANOPS: the daily workflow documents — morning demand orders and end-of-day
-- return sheets. Owned by the `vanops` module.
--
-- One migration, four tables, applied atomically. Order matters: headers
-- (demand_orders, return_sheets) are created before their line tables.
--
-- Cross-module FKs declared HERE because the DEPENDENT module owns them:
--   * demand_orders.sales_manager_id, demand_orders.representative_id,
--     return_sheets.representative_id          → identity.users(id)
--   * demand_order_lines.product_id,
--     return_sheet_lines.product_id            → inventory.products(id)
-- The role conditions (SALES_MANAGER for the manager, SALES_REP for the rep)
-- are enforced in the application layer (DemandOrderService/ReturnSheetService
-- via UserFacade.getRoleById); a DB FK cannot express role.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops (this file).
-- ─────────────────────────────────────────────────────────────────────────


-- ===== DEMAND_ORDERS (header) ============================================
CREATE TABLE demand_orders (
                               id                BIGSERIAL    PRIMARY KEY,
                               sales_manager_id  BIGINT       NOT NULL,
                               representative_id BIGINT       NOT NULL,
                               order_date        DATE         NOT NULL,
                               status            VARCHAR(30)  NOT NULL,

                               created_at        TIMESTAMPTZ  NOT NULL,
                               updated_at        TIMESTAMPTZ  NOT NULL,

                               CONSTRAINT fk_demand_orders_sales_manager
                                   FOREIGN KEY (sales_manager_id)  REFERENCES users (id),
                               CONSTRAINT fk_demand_orders_representative
                                   FOREIGN KEY (representative_id) REFERENCES users (id),

                               CONSTRAINT chk_demand_orders_status
                                   CHECK (status IN ('SUBMITTED', 'ADJUSTED', 'LOADED'))
);

CREATE INDEX idx_demand_orders_representative ON demand_orders (representative_id);
CREATE INDEX idx_demand_orders_sales_manager  ON demand_orders (sales_manager_id);
CREATE INDEX idx_demand_orders_order_date     ON demand_orders (order_date);
CREATE INDEX idx_demand_orders_status         ON demand_orders (status);

COMMENT ON TABLE  demand_orders                   IS 'Morning demand orders. Sales manager submits products+quantities; system auto-adjusts to warehouse stock.';
COMMENT ON COLUMN demand_orders.sales_manager_id  IS 'FK to users.id. Must reference a SALES_MANAGER (enforced in the application layer).';
COMMENT ON COLUMN demand_orders.representative_id IS 'FK to users.id. Must reference a SALES_REP (enforced in the application layer).';
COMMENT ON COLUMN demand_orders.order_date        IS 'Business date the load is for.';
COMMENT ON COLUMN demand_orders.status            IS 'Lifecycle: SUBMITTED (no adjustment needed), ADJUSTED (≥1 line trimmed), LOADED (stock moved to van; terminal).';


-- ===== DEMAND_ORDER_LINES =================================================
CREATE TABLE demand_order_lines (
                                    id              BIGSERIAL   PRIMARY KEY,
                                    demand_order_id BIGINT      NOT NULL,
                                    product_id      BIGINT      NOT NULL,
                                    requested_qty   INTEGER     NOT NULL,
                                    fulfilled_qty   INTEGER     NOT NULL,

                                    created_at      TIMESTAMPTZ NOT NULL,
                                    updated_at      TIMESTAMPTZ NOT NULL,

                                    CONSTRAINT fk_demand_order_lines_order
                                        FOREIGN KEY (demand_order_id) REFERENCES demand_orders (id) ON DELETE CASCADE,
                                    CONSTRAINT fk_demand_order_lines_product
                                        FOREIGN KEY (product_id)      REFERENCES products (id),

    -- One row per (order, product) — prevents two "Pepsi" lines on one order.
                                    CONSTRAINT uq_demand_order_lines_order_product
                                        UNIQUE (demand_order_id, product_id),

    -- Quantities are positive integers; fulfilled cannot exceed requested.
                                    CONSTRAINT chk_demand_order_lines_requested_qty
                                        CHECK (requested_qty > 0),
                                    CONSTRAINT chk_demand_order_lines_fulfilled_qty
                                        CHECK (fulfilled_qty >= 0 AND fulfilled_qty <= requested_qty)
);

-- demand_order_id is the LEADING column of uq_demand_order_lines_order_product,
-- so that unique index already covers FK integrity and "all lines of an order"
-- lookups. product_id needs its own index for the product-side FK check.
CREATE INDEX idx_demand_order_lines_product ON demand_order_lines (product_id);

COMMENT ON TABLE  demand_order_lines               IS 'Line items of a demand order: which products, requested vs fulfilled quantities.';
COMMENT ON COLUMN demand_order_lines.product_id    IS 'FK to products.id.';
COMMENT ON COLUMN demand_order_lines.requested_qty IS 'Quantity asked for by the sales manager (> 0).';
COMMENT ON COLUMN demand_order_lines.fulfilled_qty IS 'Quantity actually fulfilled after stock check (0..requested_qty).';


-- ===== RETURN_SHEETS (header) =============================================
CREATE TABLE return_sheets (
                               id                BIGSERIAL    PRIMARY KEY,
                               representative_id BIGINT       NOT NULL,
                               return_date       DATE         NOT NULL,
                               status            VARCHAR(30)  NOT NULL,

                               created_at        TIMESTAMPTZ  NOT NULL,
                               updated_at        TIMESTAMPTZ  NOT NULL,

                               CONSTRAINT fk_return_sheets_representative
                                   FOREIGN KEY (representative_id) REFERENCES users (id),

                               CONSTRAINT chk_return_sheets_status
                                   CHECK (status IN ('DRAFT', 'COMPLETED'))
);

CREATE INDEX idx_return_sheets_representative ON return_sheets (representative_id);
CREATE INDEX idx_return_sheets_return_date    ON return_sheets (return_date);
CREATE INDEX idx_return_sheets_status         ON return_sheets (status);

COMMENT ON TABLE  return_sheets                   IS 'End-of-day return sheets. Records products coming back from the van to the warehouse.';
COMMENT ON COLUMN return_sheets.representative_id IS 'FK to users.id. Must reference a SALES_REP (enforced in the application layer).';
COMMENT ON COLUMN return_sheets.status            IS 'Lifecycle: DRAFT (lines listed, stock not moved), COMPLETED (stock moved van→warehouse; terminal).';


-- ===== RETURN_SHEET_LINES =================================================
CREATE TABLE return_sheet_lines (
                                    id              BIGSERIAL   PRIMARY KEY,
                                    return_sheet_id BIGINT      NOT NULL,
                                    product_id      BIGINT      NOT NULL,
                                    quantity        INTEGER     NOT NULL,

                                    created_at      TIMESTAMPTZ NOT NULL,
                                    updated_at      TIMESTAMPTZ NOT NULL,

                                    CONSTRAINT fk_return_sheet_lines_sheet
                                        FOREIGN KEY (return_sheet_id) REFERENCES return_sheets (id) ON DELETE CASCADE,
                                    CONSTRAINT fk_return_sheet_lines_product
                                        FOREIGN KEY (product_id)      REFERENCES products (id),

                                    CONSTRAINT uq_return_sheet_lines_sheet_product
                                        UNIQUE (return_sheet_id, product_id),

                                    CONSTRAINT chk_return_sheet_lines_quantity
                                        CHECK (quantity > 0)
);

CREATE INDEX idx_return_sheet_lines_product ON return_sheet_lines (product_id);

COMMENT ON TABLE  return_sheet_lines            IS 'Line items of a return sheet: which products, how much came back.';
COMMENT ON COLUMN return_sheet_lines.product_id IS 'FK to products.id.';
COMMENT ON COLUMN return_sheet_lines.quantity   IS 'Quantity returned (> 0).';
