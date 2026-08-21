-- src/main/resources/db/migration/inventory/V5__create_inventory_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- INVENTORY: the product catalog plus the two stock ledgers (central warehouse
-- and per-rep van). Owned by the `inventory` module.
--
-- One migration, three tables, applied atomically — the three tables either all
-- land or none do. Order matters: `products` is created first because the other
-- two reference it.
--
-- Cross-module FK: van_inventory_items.representative_id references the identity
-- module's USERS table. The constraint is declared HERE because the DEPENDENT
-- module owns it (identity never ALTERs this table). The ERD rule "only a
-- SALES_REP may own van inventory" is a role condition a DB FK cannot express;
-- it is enforced in the application layer (StockService via UserFacade).
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer · V5 inventory (this file).
--
-- Within-module relationships (warehouse/van -> product) are real JPA @ManyToOne
-- references, so product_id is a FK here (distinct from the cross-module rule,
-- where only IDs cross and there is no @ManyToOne).
-- ─────────────────────────────────────────────────────────────────────────


-- ===== PRODUCT (catalog) =================================================
CREATE TABLE products (
                          id              BIGSERIAL     PRIMARY KEY,
                          name            VARCHAR(150)  NOT NULL,
                          sku             VARCHAR(50)   NOT NULL,
                          barcode         VARCHAR(50),
                          price           NUMERIC(12, 2) NOT NULL,
                          unit_of_measure VARCHAR(30)   NOT NULL,
                          min_stock_level INTEGER       NOT NULL DEFAULT 0,
                          status          VARCHAR(30)   NOT NULL DEFAULT 'ACTIVE',

    -- BaseEntity audit columns (UTC instants, written by @PrePersist/@PreUpdate)
                          created_at      TIMESTAMPTZ   NOT NULL,
                          updated_at      TIMESTAMPTZ   NOT NULL,

    -- SKU is the primary business identifier → always unique.
                          CONSTRAINT uq_products_sku UNIQUE (sku),
    -- Barcode is optional but unique when present (PostgreSQL permits multiple NULLs).
                          CONSTRAINT uq_products_barcode UNIQUE (barcode),

    -- Status is a stable two-value domain → safe to enforce at the DB level too.
                          CONSTRAINT chk_products_status
                              CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
                          CONSTRAINT chk_products_price
                              CHECK (price >= 0),
                          CONSTRAINT chk_products_min_stock
                              CHECK (min_stock_level >= 0)
);

-- Supports the `?status=` list filter (e.g. listing only ACTIVE products).
CREATE INDEX idx_products_status ON products (status);

COMMENT ON TABLE  products                 IS 'Product catalog. Owned by the inventory module.';
COMMENT ON COLUMN products.sku             IS 'Stock Keeping Unit. Primary business identifier; unique.';
COMMENT ON COLUMN products.barcode         IS 'Optional scannable barcode; unique when present.';
COMMENT ON COLUMN products.price           IS 'Unit price (>= 0). Captured onto invoice line items at creation time (BR-9).';
COMMENT ON COLUMN products.unit_of_measure IS 'Free-text unit of measure (e.g. BOX, BOTTLE, KG).';
COMMENT ON COLUMN products.min_stock_level IS 'Reorder threshold (FR-32). Warehouse stock below this is flagged low-stock.';
COMMENT ON COLUMN products.status          IS 'Lifecycle status: ACTIVE or DISCONTINUED. DISCONTINUED products are excluded from new sales.';


-- ===== WAREHOUSE_STOCK_ITEM (central warehouse ledger) ===================
CREATE TABLE warehouse_stock_items (
                                       id         BIGSERIAL   PRIMARY KEY,
                                       product_id BIGINT      NOT NULL,
                                       quantity   INTEGER     NOT NULL DEFAULT 0,

                                       created_at TIMESTAMPTZ NOT NULL,
                                       updated_at TIMESTAMPTZ NOT NULL,

                                       CONSTRAINT fk_warehouse_stock_product
                                           FOREIGN KEY (product_id) REFERENCES products (id),

    -- One warehouse, so at most one stock row per product (decision B).
                                       CONSTRAINT uq_warehouse_stock_product
                                           UNIQUE (product_id),

    -- Stock can never be negative.
                                       CONSTRAINT chk_warehouse_stock_qty
                                           CHECK (quantity >= 0)
);

-- No separate index on product_id: uq_warehouse_stock_product already provides a
-- unique btree index on it, which also serves the FK integrity check and lookups.

COMMENT ON TABLE  warehouse_stock_items            IS 'On-hand quantity of each product in the single central warehouse.';
COMMENT ON COLUMN warehouse_stock_items.product_id IS 'FK to products.id. Unique — one warehouse row per product.';
COMMENT ON COLUMN warehouse_stock_items.quantity   IS 'On-hand quantity (>= 0).';
COMMENT ON COLUMN warehouse_stock_items.updated_at IS 'Maps to the ERD WAREHOUSE_STOCK_ITEM.LastUpdated field.';


-- ===== VAN_INVENTORY_ITEM (per-rep van ledger) ===========================
CREATE TABLE van_inventory_items (
                                     id                BIGSERIAL   PRIMARY KEY,
                                     representative_id BIGINT      NOT NULL,
                                     product_id        BIGINT      NOT NULL,
                                     quantity          INTEGER     NOT NULL DEFAULT 0,

                                     created_at        TIMESTAMPTZ NOT NULL,
                                     updated_at        TIMESTAMPTZ NOT NULL,

    -- Cross-module FK to the identity module's users table (dependent module owns it).
                                     CONSTRAINT fk_van_inventory_representative
                                         FOREIGN KEY (representative_id) REFERENCES users (id),
                                     CONSTRAINT fk_van_inventory_product
                                         FOREIGN KEY (product_id) REFERENCES products (id),

    -- One quantity per (rep, product) — makes the BR-4 deduction unambiguous (decision C).
                                     CONSTRAINT uq_van_inventory_rep_product
                                         UNIQUE (representative_id, product_id),

    -- BR-4 backstop: van stock can never be negative, even against a buggy UPDATE.
                                     CONSTRAINT chk_van_inventory_qty
                                         CHECK (quantity >= 0)
);

-- representative_id is the LEADING column of uq_van_inventory_rep_product, so that
-- unique index already covers rep lookups and the rep-side FK check — no separate
-- index needed. product_id is NOT the leading column, so it needs its own index for
-- the product-side FK integrity check and product lookups.
CREATE INDEX idx_van_inventory_product ON van_inventory_items (product_id);

COMMENT ON TABLE  van_inventory_items                   IS 'Quantity of each product currently loaded on a sales rep''s van.';
COMMENT ON COLUMN van_inventory_items.representative_id IS 'FK to users.id. Must reference a SALES_REP (enforced in the application layer).';
COMMENT ON COLUMN van_inventory_items.product_id        IS 'FK to products.id.';
COMMENT ON COLUMN van_inventory_items.quantity          IS 'Quantity on the van (>= 0, BR-4).';