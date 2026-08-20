-- src/main/resources/db/migration/inventory/V17__create_stock_count_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- INVENTORY: physical stock-count sessions — the WRITE half of FR-124
-- (Stock Variance Report). Owned by the `inventory` module.
--
-- One migration, two tables, applied atomically. Order matters: the header
-- (stock_counts) is created before its line table (stock_count_lines).
--
-- READ-ONLY / AUDIT BY DESIGN: this feature NEVER writes warehouse_stock_items.
-- A count records what a manager physically counted and, at finalize time, a
-- snapshot of what the system recorded — it flags the gap, it does not correct
-- stock. Reconciliation (applying a count back onto warehouse stock, via the
-- existing StockService.setWarehouseStock) is deliberate future work.
--
-- Cross-module FK declared HERE because inventory is the DEPENDENT side:
--   * stock_counts.counted_by_id → identity.users(id)
-- Mirrors how vanops (V6) declares its FKs to users(id). The role condition
-- (ADMIN or WAREHOUSE_MANAGER may count) is enforced in the application layer
-- (@PreAuthorize on the controller); a DB FK cannot express role.
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer · V5 inventory ·
--   V6 vanops · V7..V12 (existing) · V13 inventory stock-counts (this file).
-- ─────────────────────────────────────────────────────────────────────────


-- ===== STOCK_COUNTS (header) =============================================
CREATE TABLE stock_counts (
                              id             BIGSERIAL    PRIMARY KEY,
                              counted_by_id  BIGINT       NOT NULL,
                              count_date     DATE         NOT NULL,
                              status         VARCHAR(30)  NOT NULL,
                              finalized_at   TIMESTAMPTZ,

                              created_at     TIMESTAMPTZ  NOT NULL,
                              updated_at     TIMESTAMPTZ  NOT NULL,

                              CONSTRAINT fk_stock_counts_counted_by
                                  FOREIGN KEY (counted_by_id) REFERENCES users (id),

                              CONSTRAINT chk_stock_counts_status
                                  CHECK (status IN ('DRAFT', 'FINALIZED')),

    -- finalized_at is present exactly when the count is FINALIZED, and absent while DRAFT.
    -- Keeps the "as-of" instant of the recorded snapshot honest at the DB layer.
                              CONSTRAINT chk_stock_counts_finalized
                                  CHECK ((status = 'FINALIZED') = (finalized_at IS NOT NULL))
);

CREATE INDEX idx_stock_counts_counted_by ON stock_counts (counted_by_id);
CREATE INDEX idx_stock_counts_count_date ON stock_counts (count_date);
CREATE INDEX idx_stock_counts_status     ON stock_counts (status);

COMMENT ON TABLE  stock_counts               IS 'Physical stock-count sessions (FR-124). Audit only — never corrects warehouse stock.';
COMMENT ON COLUMN stock_counts.counted_by_id IS 'FK to users.id. The ADMIN/WAREHOUSE_MANAGER who owns the count (enforced in the application layer).';
COMMENT ON COLUMN stock_counts.count_date    IS 'Business date the physical count represents.';
COMMENT ON COLUMN stock_counts.status        IS 'Lifecycle: DRAFT (counts being entered, no recorded snapshot yet), FINALIZED (recorded snapshot taken; immutable; terminal).';
COMMENT ON COLUMN stock_counts.finalized_at  IS 'Instant the recorded-stock snapshot was taken (the variance as-of moment). NULL while DRAFT.';


-- ===== STOCK_COUNT_LINES =================================================
CREATE TABLE stock_count_lines (
                                   id                BIGSERIAL   PRIMARY KEY,
                                   stock_count_id    BIGINT      NOT NULL,
                                   product_id        BIGINT      NOT NULL,
                                   counted_quantity  INTEGER     NOT NULL,
                                   recorded_quantity INTEGER,

                                   created_at        TIMESTAMPTZ NOT NULL,
                                   updated_at        TIMESTAMPTZ NOT NULL,

                                   CONSTRAINT fk_stock_count_lines_count
                                       FOREIGN KEY (stock_count_id) REFERENCES stock_counts (id) ON DELETE CASCADE,
                                   CONSTRAINT fk_stock_count_lines_product
                                       FOREIGN KEY (product_id)     REFERENCES products (id),

    -- One row per (count, product) — a product is counted once per session.
                                   CONSTRAINT uq_stock_count_lines_count_product
                                       UNIQUE (stock_count_id, product_id),

    -- Counted may be 0 (counted, found none). Recorded is NULL until finalize, then >= 0.
                                   CONSTRAINT chk_stock_count_lines_counted
                                       CHECK (counted_quantity >= 0),
                                   CONSTRAINT chk_stock_count_lines_recorded
                                       CHECK (recorded_quantity IS NULL OR recorded_quantity >= 0)
);

-- stock_count_id is the LEADING column of uq_stock_count_lines_count_product,
-- so that unique index already covers FK integrity and "all lines of a count"
-- lookups. product_id needs its own index for the product-side FK check.
CREATE INDEX idx_stock_count_lines_product ON stock_count_lines (product_id);

COMMENT ON TABLE  stock_count_lines                   IS 'Line items of a stock count: physically counted quantity and, once finalized, the recorded snapshot.';
COMMENT ON COLUMN stock_count_lines.product_id        IS 'FK to products.id.';
COMMENT ON COLUMN stock_count_lines.counted_quantity  IS 'Quantity the manager physically counted (>= 0; 0 means counted and none present).';
COMMENT ON COLUMN stock_count_lines.recorded_quantity IS 'Snapshot of warehouse_stock_items.quantity captured at finalize (the system-recorded figure). NULL while DRAFT. Variance = counted - recorded, derived on read, never stored.';
