-- ============================================================================
-- REPEATABLE FLYWAY SEED — Demo data for Sales Management System
-- ============================================================================
-- File:     db/seed/R__seed_demo_data.sql
-- Purpose:  Referentially consistent demo data across all completed modules.
--           Runs AFTER all versioned migrations (V0–V6+).
--           Re-runs automatically whenever this file is edited (Flyway checksums it).
--
-- Activation:
--   In application-dev.properties (or application-local.properties):
--     spring.flyway.locations=classpath:db/migration,classpath:db/seed
--
--   In application.properties (production):
--     spring.flyway.locations=classpath:db/migration
--     (seed directory is never scanned)
--
-- Password for ALL users: Owais@1234
-- BCrypt hash: $2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi
--
-- To re-seed: edit this file (even a comment change triggers re-run).
-- To full-reset: DROP SCHEMA public CASCADE; CREATE SCHEMA public; then restart.
-- ============================================================================

-- Guard: clean all seeded tables in reverse dependency order so re-runs work.
-- TRUNCATE with CASCADE handles FK chains in one shot.
TRUNCATE TABLE epod_artifacts,
               invoice_line_items,
               invoices,
               visits,
               route_customer_assignments,
               routes,
               return_sheet_lines,
               return_sheets,
               demand_order_lines,
               demand_orders,
               van_inventory_items,
               warehouse_stock_items,
               customers,
               products,
               users,
               territories
    CASCADE;

-- Reset sequences so IDs are predictable across re-seeds.
ALTER SEQUENCE territories_id_seq RESTART WITH 1;
ALTER SEQUENCE users_id_seq       RESTART WITH 1;
ALTER SEQUENCE products_id_seq    RESTART WITH 1;
ALTER SEQUENCE customers_id_seq   RESTART WITH 1;
ALTER SEQUENCE warehouse_stock_items_id_seq  RESTART WITH 1;
ALTER SEQUENCE van_inventory_items_id_seq    RESTART WITH 1;
ALTER SEQUENCE demand_orders_id_seq          RESTART WITH 1;
ALTER SEQUENCE demand_order_lines_id_seq     RESTART WITH 1;
ALTER SEQUENCE return_sheets_id_seq          RESTART WITH 1;
ALTER SEQUENCE return_sheet_lines_id_seq     RESTART WITH 1;
ALTER SEQUENCE routes_id_seq                 RESTART WITH 1;
ALTER SEQUENCE route_customer_assignments_id_seq RESTART WITH 1;
ALTER SEQUENCE visits_id_seq                 RESTART WITH 1;
ALTER SEQUENCE invoices_id_seq               RESTART WITH 1;
ALTER SEQUENCE invoice_line_items_id_seq     RESTART WITH 1;
ALTER SEQUENCE epod_artifacts_id_seq         RESTART WITH 1;


-- ============================================================================
-- 1. TERRITORIES  (6 rows — id 1–6)
-- ============================================================================
-- Syrian cities used as territory names for FMCG distribution context.
INSERT INTO territories (name, description, created_at, updated_at) VALUES
                                                                        ('Damascus Central',   'Downtown Damascus and surrounding commercial district',    NOW(), NOW()),
                                                                        ('Damascus East',      'Eastern suburbs including Jaramana and Mleiha',            NOW(), NOW()),
                                                                        ('Aleppo North',       'Northern Aleppo industrial and commercial zones',          NOW(), NOW()),
                                                                        ('Aleppo South',       'Southern Aleppo residential and retail areas',             NOW(), NOW()),
                                                                        ('Homs City',          'Central Homs urban area',                                  NOW(), NOW()),
                                                                        ('Latakia Coast',      'Latakia coastal commercial strip',                         NOW(), NOW());


-- ============================================================================
-- 2. USERS  (11 rows — id 1–11)
-- ============================================================================
-- Password: Owais@1234 for everyone.
-- ID  1     = ADMIN
-- ID  2–3   = SALES_MANAGER
-- ID  4–9   = SALES_REP
-- ID  10–11 = WAREHOUSE_MANAGER

INSERT INTO users (name, email, password_hash, role, status, created_at, updated_at) VALUES
                                                                                         -- ADMIN (1)
                                                                                         ('Admin User',             'admin@sm.com',       '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'ADMIN',             'ACTIVE', NOW(), NOW()),

                                                                                         -- SALES_MANAGER (2–3)
                                                                                         ('Ahmad Hassan',           'ahmad.mgr@sm.com',   '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_MANAGER',     'ACTIVE', NOW(), NOW()),
                                                                                         ('Hussam Ruqaih',          'hussam.mgr@sm.com',  '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_MANAGER',     'ACTIVE', NOW(), NOW()),

                                                                                         -- SALES_REP (4–9)
                                                                                         ('Khaled Haj Othman',      'khaled.rep@sm.com',  '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),
                                                                                         ('Shadi Hamzeh',           'shadi.rep@sm.com',   '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),
                                                                                         ('Omar Bakri',             'omar.rep@sm.com',    '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),
                                                                                         ('Rami Saleh',             'rami.rep@sm.com',    '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),
                                                                                         ('Fadi Nasser',            'fadi.rep@sm.com',    '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),
                                                                                         ('Mazen Khoury',           'mazen.rep@sm.com',   '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),

                                                                                         -- WAREHOUSE_MANAGER (10–11)
                                                                                         ('Bilal Warehouse',        'bilal.wh@sm.com',    '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'WAREHOUSE_MANAGER', 'ACTIVE', NOW(), NOW()),
                                                                                         ('Nour Warehouse',         'nour.wh@sm.com',     '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'WAREHOUSE_MANAGER', 'ACTIVE', NOW(), NOW());


-- ============================================================================
-- 3. PRODUCTS  (15 rows — id 1–15)
-- ============================================================================
-- Realistic FMCG products. All ACTIVE. Prices in SYP-scale (kept simple as round numbers).
INSERT INTO products (name, sku, barcode, price, unit_of_measure, min_stock_level, status, created_at, updated_at) VALUES
                                                                                                                       ('Coca-Cola 330ml',        'BEV-001', '6281036000019', 1500.00,  'PIECE', 100, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Pepsi 330ml',            'BEV-002', '6281036000026', 1400.00,  'PIECE', 100, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Lays Classic 160g',      'SNK-001', '6281036000033', 2000.00,  'PIECE',  80, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Nescafe 3in1 Sachet',    'BEV-003', '6281036000040', 500.00,   'PIECE', 200, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Tide Detergent 3kg',     'CLN-001', '6281036000057', 12000.00, 'PIECE',  40, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Fairy Dish Soap 750ml',  'CLN-002', '6281036000064', 4500.00,  'PIECE',  60, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Al-Durra Tahini 900g',   'FOD-001', '6281036000071', 8000.00,  'PIECE',  50, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Sunflower Oil 1.5L',     'FOD-002', '6281036000088', 15000.00, 'PIECE',  30, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Rani Juice 1L Mango',    'BEV-004', '6281036000095', 3500.00,  'PIECE',  70, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Galaxy Chocolate 90g',   'SNK-002', '6281036000101', 3000.00,  'PIECE',  90, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Puck Cheese Jar 500g',   'DAI-001', '6281036000118', 6000.00,  'PIECE',  40, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Nido Milk Powder 900g',  'DAI-002', '6281036000125', 18000.00, 'PIECE',  25, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Indomie Noodles 5-pack', 'FOD-003', '6281036000132', 2500.00,  'PIECE', 120, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Colgate Toothpaste 100ml','PER-001','6281036000149', 3000.00,  'PIECE',  60, 'ACTIVE', NOW(), NOW()),
                                                                                                                       ('Head & Shoulders 400ml', 'PER-002', '6281036000156', 7500.00,  'PIECE',  35, 'ACTIVE', NOW(), NOW());


-- ============================================================================
-- 4. CUSTOMERS  (15 rows — id 1–15)
-- ============================================================================
-- Spread across 6 territories (~2-3 per territory). GPS coords are realistic Damascus/Aleppo/Homs/Latakia.
INSERT INTO customers (territory_id, name, address, phone, latitude, longitude, category, status, created_at, updated_at) VALUES
                                                                                                                              -- Damascus Central (territory 1) — 3 customers
                                                                                                                              (1, 'Al-Hamidiyah Market',     'Hamidiyah Souq, Old Damascus',     '+963111234567', 33.5117, 36.3067, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),
                                                                                                                              (1, 'Sham Grocery',            'Bab Touma, Damascus',              '+963111234568', 33.5130, 36.3200, 'RETAIL',      'ACTIVE', NOW(), NOW()),
                                                                                                                              (1, 'Damascus Mini Market',    'Mazzeh, Damascus',                 '+963111234569', 33.5050, 36.2750, 'RETAIL',      'ACTIVE', NOW(), NOW()),

                                                                                                                              -- Damascus East (territory 2) — 3 customers
                                                                                                                              (2, 'Jaramana Superstore',     'Main St, Jaramana',                '+963112345670', 33.4830, 36.3400, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),
                                                                                                                              (2, 'Mleiha Corner Shop',      'Mleiha Rd, East Damascus',         '+963112345671', 33.4700, 36.3600, 'RETAIL',      'ACTIVE', NOW(), NOW()),
                                                                                                                              (2, 'Al-Quds Minimarket',      'Sayyida Zeinab, Damascus',         '+963112345672', 33.4450, 36.3350, 'RETAIL',      'ACTIVE', NOW(), NOW()),

                                                                                                                              -- Aleppo North (territory 3) — 2 customers
                                                                                                                              (3, 'Aleppo Trade Center',     'Al-Jdeideh, Aleppo',               '+963213456780', 36.2010, 37.1660, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),
                                                                                                                              (3, 'Northern Souk Retailer',  'Bab al-Hadid, Aleppo',             '+963213456781', 36.2070, 37.1500, 'RETAIL',      'ACTIVE', NOW(), NOW()),

                                                                                                                              -- Aleppo South (territory 4) — 2 customers
                                                                                                                              (4, 'Salaheddine Market',      'Salaheddine District, Aleppo',     '+963214567890', 36.1800, 37.1580, 'RETAIL',      'ACTIVE', NOW(), NOW()),
                                                                                                                              (4, 'Shahba Wholesaler',       'Al-Shahba, Aleppo',                '+963214567891', 36.1700, 37.1750, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),

                                                                                                                              -- Homs City (territory 5) — 3 customers
                                                                                                                              (5, 'Homs Central Grocery',    'Clock Tower Area, Homs',           '+963315678900', 34.7350, 36.7140, 'RETAIL',      'ACTIVE', NOW(), NOW()),
                                                                                                                              (5, 'Al-Waer Minimarket',      'Al-Waer District, Homs',           '+963315678901', 34.7500, 36.6800, 'PHARMACY',    'ACTIVE', NOW(), NOW()),
                                                                                                                              (5, 'Old Homs Retailer',       'Bab al-Sebaa, Homs',               '+963315678902', 34.7300, 36.7200, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),

                                                                                                                              -- Latakia Coast (territory 6) — 2 customers
                                                                                                                              (6, 'Latakia Port Store',      'Corniche, Latakia',                '+963416789010', 35.5197, 35.7920, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),
                                                                                                                              (6, 'Coastal Mini Market',     'Al-Zira''a District, Latakia',     '+963416789011', 35.5100, 35.7800, 'RESTAURANT',  'ACTIVE', NOW(), NOW());


-- ============================================================================
-- 5. WAREHOUSE STOCK  (15 rows — one per product, id 1–15)
-- ============================================================================
-- Healthy stock levels to support demand orders without running to zero.
INSERT INTO warehouse_stock_items (product_id, quantity, created_at, updated_at) VALUES
                                                                                     (1,  500, NOW(), NOW()),   -- Coca-Cola
                                                                                     (2,  450, NOW(), NOW()),   -- Pepsi
                                                                                     (3,  300, NOW(), NOW()),   -- Lays
                                                                                     (4,  800, NOW(), NOW()),   -- Nescafe
                                                                                     (5,  150, NOW(), NOW()),   -- Tide
                                                                                     (6,  200, NOW(), NOW()),   -- Fairy
                                                                                     (7,  180, NOW(), NOW()),   -- Tahini
                                                                                     (8,  120, NOW(), NOW()),   -- Sunflower Oil
                                                                                     (9,  350, NOW(), NOW()),   -- Rani Juice
                                                                                     (10, 400, NOW(), NOW()),   -- Galaxy
                                                                                     (11, 160, NOW(), NOW()),   -- Puck Cheese
                                                                                     (12, 100, NOW(), NOW()),   -- Nido
                                                                                     (13, 600, NOW(), NOW()),   -- Indomie
                                                                                     (14, 250, NOW(), NOW()),   -- Colgate
                                                                                     (15, 140, NOW(), NOW());   -- Head & Shoulders


-- ============================================================================
-- 6. DEMAND ORDERS  (12 rows — id 1–12)
-- ============================================================================
-- 6 LOADED (reps 4–9, one each, submitted by manager 2 or 3)
-- 3 SUBMITTED, 3 ADJUSTED (reps 4–9 second orders, mixed managers)
--
-- Manager assignments:
--   Manager 2 (Ahmad)  → reps 4, 5, 6
--   Manager 3 (Hussam) → reps 7, 8, 9

INSERT INTO demand_orders (sales_manager_id, representative_id, order_date, status, created_at, updated_at) VALUES
                                                                                                                -- LOADED orders (id 1–6) — these have been fulfilled and loaded into vans
                                                                                                                (2, 4,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 1: Ahmad→Khaled
                                                                                                                (2, 5,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 2: Ahmad→Shadi
                                                                                                                (2, 6,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 3: Ahmad→Omar
                                                                                                                (3, 7,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 4: Hussam→Rami
                                                                                                                (3, 8,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 5: Hussam→Fadi
                                                                                                                (3, 9,  CURRENT_DATE - INTERVAL '2 days', 'LOADED',    NOW(), NOW()),  -- id 6: Hussam→Mazen

                                                                                                                -- SUBMITTED orders (id 7–9) — all lines fulfilled at requested qty
                                                                                                                (2, 4,  CURRENT_DATE, 'SUBMITTED', NOW(), NOW()),  -- id 7
                                                                                                                (2, 5,  CURRENT_DATE, 'SUBMITTED', NOW(), NOW()),  -- id 8
                                                                                                                (3, 7,  CURRENT_DATE, 'SUBMITTED', NOW(), NOW()),  -- id 9

                                                                                                                -- ADJUSTED orders (id 10–12) — at least one line was trimmed
                                                                                                                (2, 6,  CURRENT_DATE, 'ADJUSTED',  NOW(), NOW()),  -- id 10
                                                                                                                (3, 8,  CURRENT_DATE, 'ADJUSTED',  NOW(), NOW()),  -- id 11
                                                                                                                (3, 9,  CURRENT_DATE, 'ADJUSTED',  NOW(), NOW());  -- id 12


-- ============================================================================
-- 7. DEMAND ORDER LINES
-- ============================================================================
-- Each order gets 3-4 product lines. LOADED orders: FulfilledQty = RequestedQty.
-- SUBMITTED orders: FulfilledQty = RequestedQty (all available).
-- ADJUSTED orders: at least one line has FulfilledQty < RequestedQty.

INSERT INTO demand_order_lines (demand_order_id, product_id, requested_qty, fulfilled_qty, created_at, updated_at) VALUES
                                                                                                                       -- Order 1 (LOADED, rep 4 Khaled): beverages + snacks
                                                                                                                       (1, 1,  30, 30, NOW(), NOW()),   -- Coca-Cola
                                                                                                                       (1, 3,  20, 20, NOW(), NOW()),   -- Lays
                                                                                                                       (1, 9,  25, 25, NOW(), NOW()),   -- Rani Juice

                                                                                                                       -- Order 2 (LOADED, rep 5 Shadi): cleaning + dairy
                                                                                                                       (2, 5,  10, 10, NOW(), NOW()),   -- Tide
                                                                                                                       (2, 6,  15, 15, NOW(), NOW()),   -- Fairy
                                                                                                                       (2, 11, 12, 12, NOW(), NOW()),   -- Puck Cheese
                                                                                                                       (2, 14, 20, 20, NOW(), NOW()),   -- Colgate

                                                                                                                       -- Order 3 (LOADED, rep 6 Omar): food + beverages
                                                                                                                       (3, 7,  15, 15, NOW(), NOW()),   -- Tahini
                                                                                                                       (3, 8,  10, 10, NOW(), NOW()),   -- Sunflower Oil
                                                                                                                       (3, 4,  40, 40, NOW(), NOW()),   -- Nescafe

                                                                                                                       -- Order 4 (LOADED, rep 7 Rami): mixed
                                                                                                                       (4, 2,  35, 35, NOW(), NOW()),   -- Pepsi
                                                                                                                       (4, 10, 25, 25, NOW(), NOW()),   -- Galaxy
                                                                                                                       (4, 13, 30, 30, NOW(), NOW()),   -- Indomie
                                                                                                                       (4, 15, 10, 10, NOW(), NOW()),   -- Head & Shoulders

                                                                                                                       -- Order 5 (LOADED, rep 8 Fadi): beverages + personal care
                                                                                                                       (5, 1,  20, 20, NOW(), NOW()),   -- Coca-Cola
                                                                                                                       (5, 2,  20, 20, NOW(), NOW()),   -- Pepsi
                                                                                                                       (5, 14, 15, 15, NOW(), NOW()),   -- Colgate

                                                                                                                       -- Order 6 (LOADED, rep 9 Mazen): food + dairy
                                                                                                                       (6, 12, 8,  8,  NOW(), NOW()),   -- Nido
                                                                                                                       (6, 11, 10, 10, NOW(), NOW()),   -- Puck Cheese
                                                                                                                       (6, 7,  12, 12, NOW(), NOW()),   -- Tahini
                                                                                                                       (6, 13, 25, 25, NOW(), NOW()),   -- Indomie

                                                                                                                       -- Order 7 (SUBMITTED, rep 4): all fulfilled
                                                                                                                       (7, 2,  25, 25, NOW(), NOW()),   -- Pepsi
                                                                                                                       (7, 10, 20, 20, NOW(), NOW()),   -- Galaxy
                                                                                                                       (7, 4,  30, 30, NOW(), NOW()),   -- Nescafe

                                                                                                                       -- Order 8 (SUBMITTED, rep 5): all fulfilled
                                                                                                                       (8, 1,  15, 15, NOW(), NOW()),   -- Coca-Cola
                                                                                                                       (8, 13, 20, 20, NOW(), NOW()),   -- Indomie
                                                                                                                       (8, 9,  15, 15, NOW(), NOW()),   -- Rani Juice

                                                                                                                       -- Order 9 (SUBMITTED, rep 7): all fulfilled
                                                                                                                       (9, 3,  18, 18, NOW(), NOW()),   -- Lays
                                                                                                                       (9, 6,  12, 12, NOW(), NOW()),   -- Fairy
                                                                                                                       (9, 15, 8,  8,  NOW(), NOW()),   -- Head & Shoulders

                                                                                                                       -- Order 10 (ADJUSTED, rep 6): Sunflower Oil trimmed (requested 20, only 10 available after prior orders)
                                                                                                                       (10, 8,  20, 10, NOW(), NOW()),  -- Sunflower Oil  ← TRIMMED
                                                                                                                       (10, 4,  25, 25, NOW(), NOW()),  -- Nescafe
                                                                                                                       (10, 1,  15, 15, NOW(), NOW()),  -- Coca-Cola

                                                                                                                       -- Order 11 (ADJUSTED, rep 8): Nido trimmed (low stock)
                                                                                                                       (11, 12, 15, 7,  NOW(), NOW()),  -- Nido           ← TRIMMED
                                                                                                                       (11, 3,  20, 20, NOW(), NOW()),  -- Lays
                                                                                                                       (11, 10, 15, 15, NOW(), NOW()),  -- Galaxy

                                                                                                                       -- Order 12 (ADJUSTED, rep 9): Head & Shoulders trimmed
                                                                                                                       (12, 15, 12, 5,  NOW(), NOW()),  -- Head&Shoulders ← TRIMMED
                                                                                                                       (12, 2,  20, 20, NOW(), NOW()),  -- Pepsi
                                                                                                                       (12, 7,  10, 10, NOW(), NOW());  -- Tahini


-- ============================================================================
-- 8. VAN INVENTORY  (for all 6 reps — post-invoice-deduction state)
-- ============================================================================
-- Starting point: FulfilledQty from LOADED orders (1–6).
-- Then MINUS every quantity sold on an invoice in SENT / APPROVED / REJECTED
-- state (section 13) — because stock deducts atomically at SENT and stays
-- deducted even on rejection (D1).
-- DRAFT invoices (11, 12) deduct nothing, so their lines are NOT subtracted.
--
-- Worked example, rep 4 Coca-Cola (product 1):
--   loaded 30  −  invoice 1 sold 10  =  20   ← the value below
--
-- No row reaches zero, so no row is deleted (matches the "delete on full
-- return, not on partial sale" rule).

INSERT INTO van_inventory_items (representative_id, product_id, quantity, created_at, updated_at) VALUES
                                                                                                      -- Rep 4 (Khaled) — order 1 loaded {1:30, 3:20, 9:25}; invoices 1,2 sold {1:10, 3:5, 9:8}
                                                                                                      (4, 1,  20, NOW(), NOW()),   -- Coca-Cola      30 − 10
                                                                                                      (4, 3,  15, NOW(), NOW()),   -- Lays           20 − 5
                                                                                                      (4, 9,  17, NOW(), NOW()),   -- Rani Juice     25 − 8

                                                                                                      -- Rep 5 (Shadi) — order 2 loaded {5:10, 6:15, 11:12, 14:20}; invoices 3,6 sold {5:3, 14:6, 6:4, 11:3}
                                                                                                      (5, 5,  7,  NOW(), NOW()),   -- Tide           10 − 3
                                                                                                      (5, 6,  11, NOW(), NOW()),   -- Fairy          15 − 4
                                                                                                      (5, 11, 9,  NOW(), NOW()),   -- Puck Cheese    12 − 3
                                                                                                      (5, 14, 14, NOW(), NOW()),   -- Colgate        20 − 6

                                                                                                      -- Rep 6 (Omar) — order 3 loaded {7:15, 8:10, 4:40}; invoices 4,9 sold {4:15, 7:4, 8:2}
                                                                                                      (6, 7,  11, NOW(), NOW()),   -- Tahini         15 − 4
                                                                                                      (6, 8,  8,  NOW(), NOW()),   -- Sunflower Oil  10 − 2
                                                                                                      (6, 4,  25, NOW(), NOW()),   -- Nescafe        40 − 15

                                                                                                      -- Rep 7 (Rami) — order 4 loaded {2:35, 10:25, 13:30, 15:10}; invoices 5,7 sold {2:12, 13:10, 10:6, 15:2}
                                                                                                      (7, 2,  23, NOW(), NOW()),   -- Pepsi          35 − 12
                                                                                                      (7, 10, 19, NOW(), NOW()),   -- Galaxy         25 − 6
                                                                                                      (7, 13, 20, NOW(), NOW()),   -- Indomie        30 − 10
                                                                                                      (7, 15, 8,  NOW(), NOW()),   -- Head&Shoulders 10 − 2

                                                                                                      -- Rep 8 (Fadi) — order 5 loaded {1:20, 2:20, 14:15}; invoice 8 sold {1:7}
                                                                                                      -- (invoice 11 is DRAFT — its 5 Pepsi + 3 Colgate are NOT deducted)
                                                                                                      (8, 1,  13, NOW(), NOW()),   -- Coca-Cola      20 − 7
                                                                                                      (8, 2,  20, NOW(), NOW()),   -- Pepsi          20 − 0
                                                                                                      (8, 14, 15, NOW(), NOW()),   -- Colgate        15 − 0

                                                                                                      -- Rep 9 (Mazen) — order 6 loaded {12:8, 11:10, 7:12, 13:25}; invoice 10 sold {12:2, 13:5}
                                                                                                      -- (invoice 12 is DRAFT — its 4 Puck Cheese + 3 Tahini are NOT deducted)
                                                                                                      (9, 12, 6,  NOW(), NOW()),   -- Nido            8 − 2
                                                                                                      (9, 11, 10, NOW(), NOW()),   -- Puck Cheese    10 − 0
                                                                                                      (9, 7,  12, NOW(), NOW()),   -- Tahini         12 − 0
                                                                                                      (9, 13, 20, NOW(), NOW());   -- Indomie        25 − 5


-- ============================================================================
-- 9. RETURN SHEETS  (8 rows — id 1–8)
-- ============================================================================
-- 4 DRAFT (stock not yet moved back)
-- 4 COMPLETED (stock moved back to warehouse, van rows would be deleted in app logic)
--
-- For COMPLETED returns: in production the app deletes the van_inventory_items rows
-- and adds quantities back to warehouse_stock_items. The seed data represents a
-- snapshot where LOADED orders created van inventory, and COMPLETED returns have
-- already been processed (but we keep van rows intact for demo visibility).

INSERT INTO return_sheets (representative_id, return_date, status, created_at, updated_at) VALUES
                                                                                               -- DRAFT (id 1–4) — reps 4, 5, 6, 7 have pending returns
                                                                                               (4, CURRENT_DATE, 'DRAFT',     NOW(), NOW()),  -- id 1: Khaled
                                                                                               (5, CURRENT_DATE, 'DRAFT',     NOW(), NOW()),  -- id 2: Shadi
                                                                                               (6, CURRENT_DATE, 'DRAFT',     NOW(), NOW()),  -- id 3: Omar
                                                                                               (7, CURRENT_DATE, 'DRAFT',     NOW(), NOW()),  -- id 4: Rami

                                                                                               -- COMPLETED (id 5–8) — reps 4, 5, 8, 9 returned stock yesterday
                                                                                               (4, CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', NOW(), NOW()),  -- id 5: Khaled (yesterday)
                                                                                               (5, CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', NOW(), NOW()),  -- id 6: Shadi  (yesterday)
                                                                                               (8, CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', NOW(), NOW()),  -- id 7: Fadi   (yesterday)
                                                                                               (9, CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', NOW(), NOW());  -- id 8: Mazen  (yesterday)


-- ============================================================================
-- 10. RETURN SHEET LINES
-- ============================================================================
-- DRAFT returns: partial returns (rep still has some stock on van)
-- COMPLETED returns: full returns from a hypothetical previous day's load

INSERT INTO return_sheet_lines (return_sheet_id, product_id, quantity, created_at, updated_at) VALUES
                                                                                                   -- Return 1 (DRAFT, rep 4 Khaled) — returning unsold Coca-Cola and Lays
                                                                                                   (1, 1,  8,  NOW(), NOW()),   -- 8 Coca-Cola left
                                                                                                   (1, 3,  5,  NOW(), NOW()),   -- 5 Lays left

                                                                                                   -- Return 2 (DRAFT, rep 5 Shadi) — returning some Fairy and Colgate
                                                                                                   (2, 6,  4,  NOW(), NOW()),   -- 4 Fairy left
                                                                                                   (2, 14, 7,  NOW(), NOW()),   -- 7 Colgate left

                                                                                                   -- Return 3 (DRAFT, rep 6 Omar) — returning leftover Nescafe
                                                                                                   (3, 4,  12, NOW(), NOW()),   -- 12 Nescafe left

                                                                                                   -- Return 4 (DRAFT, rep 7 Rami) — returning Galaxy and Indomie
                                                                                                   (4, 10, 6,  NOW(), NOW()),   -- 6 Galaxy left
                                                                                                   (4, 13, 10, NOW(), NOW()),   -- 10 Indomie left

                                                                                                   -- Return 5 (COMPLETED, rep 4 Khaled yesterday) — returned everything from a prior load
                                                                                                   (5, 2,  5,  NOW(), NOW()),   -- 5 Pepsi
                                                                                                   (5, 10, 3,  NOW(), NOW()),   -- 3 Galaxy

                                                                                                   -- Return 6 (COMPLETED, rep 5 Shadi yesterday)
                                                                                                   (6, 1,  4,  NOW(), NOW()),   -- 4 Coca-Cola
                                                                                                   (6, 9,  6,  NOW(), NOW()),   -- 6 Rani Juice

                                                                                                   -- Return 7 (COMPLETED, rep 8 Fadi yesterday)
                                                                                                   (7, 1,  3,  NOW(), NOW()),   -- 3 Coca-Cola
                                                                                                   (7, 14, 5,  NOW(), NOW()),   -- 5 Colgate

                                                                                                   -- Return 8 (COMPLETED, rep 9 Mazen yesterday)
                                                                                                   (8, 12, 2,  NOW(), NOW()),   -- 2 Nido
                                                                                                   (8, 13, 8,  NOW(), NOW());   -- 8 Indomie



-- ============================================================================
-- 11. ROUTES  (10 rows — id 1–10)
-- ============================================================================
-- A route is a rep's daily visit schedule within ONE territory.
-- Every stop on a route must be a customer of that route's territory —
-- routes.territory_id exists precisely to enforce this.
--
-- Territory → customer id map (from section 4):
--   T1 Damascus Central : 1, 2, 3
--   T2 Damascus East    : 4, 5, 6
--   T3 Aleppo North     : 7, 8
--   T4 Aleppo South     : 9, 10
--   T5 Homs City        : 11, 12, 13
--   T6 Latakia Coast    : 14, 15
--
-- Rep → territory assignment (stable across the seed):
--   rep 4 Khaled → T1 | rep 5 Shadi → T2 | rep 6 Omar  → T3
--   rep 7 Rami   → T4 | rep 8 Fadi  → T5 | rep 9 Mazen → T6
--
-- Status mix: 6 COMPLETED (yesterday, fully visited), 3 ACTIVE (today,
-- in progress), 1 PLANNED (tomorrow, not started).
-- Multiple routes per rep per day are legal — V7_1 dropped
-- uq_routes_representative_date.

INSERT INTO routes (representative_id, territory_id, name, route_date, status, is_optimized, created_at, updated_at) VALUES
                                                                                                                         -- COMPLETED — yesterday's finished routes (id 1–6)
                                                                                                                         (4, 1, 'Damascus Central - Mon',  CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', TRUE,  NOW(), NOW()),  -- id 1
                                                                                                                         (5, 2, 'Damascus East - Mon',     CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', TRUE,  NOW(), NOW()),  -- id 2
                                                                                                                         (6, 3, 'Aleppo North - Mon',      CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', FALSE, NOW(), NOW()),  -- id 3
                                                                                                                         (7, 4, 'Aleppo South - Mon',      CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', TRUE,  NOW(), NOW()),  -- id 4
                                                                                                                         (8, 5, 'Homs City - Mon',         CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', FALSE, NOW(), NOW()),  -- id 5
                                                                                                                         (9, 6, 'Latakia Coast - Mon',     CURRENT_DATE - INTERVAL '1 day', 'COMPLETED', TRUE,  NOW(), NOW()),  -- id 6

                                                                                                                         -- ACTIVE — today, rep is out executing them (id 7–9)
                                                                                                                         (4, 1, 'Damascus Central - Today', CURRENT_DATE, 'ACTIVE', TRUE,  NOW(), NOW()),  -- id 7
                                                                                                                         (5, 2, 'Damascus East - Today',    CURRENT_DATE, 'ACTIVE', TRUE,  NOW(), NOW()),  -- id 8
                                                                                                                         (6, 3, 'Aleppo North - Today',     CURRENT_DATE, 'ACTIVE', FALSE, NOW(), NOW()),  -- id 9

                                                                                                                         -- PLANNED — tomorrow, not started (id 10)
                                                                                                                         (7, 4, 'Aleppo South - Tomorrow',  CURRENT_DATE + INTERVAL '1 day', 'PLANNED', FALSE, NOW(), NOW());   -- id 10


-- ============================================================================
-- 12. ROUTE CUSTOMER ASSIGNMENTS  (stops — id 1–23)
-- ============================================================================
-- sequence_number is the visit order, strictly > 0, unique per (route, customer).
-- Every customer_id below belongs to its route's territory (see the map above).

INSERT INTO route_customer_assignments (route_id, customer_id, sequence_number, created_at, updated_at) VALUES
                                                                                                            -- Route 1 (rep 4, T1 Damascus Central) — all 3 T1 customers
                                                                                                            (1, 1,  1, NOW(), NOW()),
                                                                                                            (1, 2,  2, NOW(), NOW()),
                                                                                                            (1, 3,  3, NOW(), NOW()),

                                                                                                            -- Route 2 (rep 5, T2 Damascus East) — all 3 T2 customers
                                                                                                            (2, 4,  1, NOW(), NOW()),
                                                                                                            (2, 5,  2, NOW(), NOW()),
                                                                                                            (2, 6,  3, NOW(), NOW()),

                                                                                                            -- Route 3 (rep 6, T3 Aleppo North) — both T3 customers
                                                                                                            (3, 7,  1, NOW(), NOW()),
                                                                                                            (3, 8,  2, NOW(), NOW()),

                                                                                                            -- Route 4 (rep 7, T4 Aleppo South) — both T4 customers
                                                                                                            (4, 9,  1, NOW(), NOW()),
                                                                                                            (4, 10, 2, NOW(), NOW()),

                                                                                                            -- Route 5 (rep 8, T5 Homs City) — all 3 T5 customers
                                                                                                            (5, 11, 1, NOW(), NOW()),
                                                                                                            (5, 12, 2, NOW(), NOW()),
                                                                                                            (5, 13, 3, NOW(), NOW()),

                                                                                                            -- Route 6 (rep 9, T6 Latakia Coast) — both T6 customers
                                                                                                            (6, 14, 1, NOW(), NOW()),
                                                                                                            (6, 15, 2, NOW(), NOW()),

                                                                                                            -- Route 7 (rep 4, T1, today, ACTIVE)
                                                                                                            (7, 1,  1, NOW(), NOW()),
                                                                                                            (7, 3,  2, NOW(), NOW()),

                                                                                                            -- Route 8 (rep 5, T2, today, ACTIVE)
                                                                                                            (8, 4,  1, NOW(), NOW()),
                                                                                                            (8, 6,  2, NOW(), NOW()),

                                                                                                            -- Route 9 (rep 6, T3, today, ACTIVE)
                                                                                                            (9, 7,  1, NOW(), NOW()),
                                                                                                            (9, 8,  2, NOW(), NOW()),

                                                                                                            -- Route 10 (rep 7, T4, tomorrow, PLANNED)
                                                                                                            (10, 9,  1, NOW(), NOW()),
                                                                                                            (10, 10, 2, NOW(), NOW());


-- ============================================================================
-- 13. VISITS  (16 rows — id 1–16)
-- ============================================================================
-- Per-status column shape (enforced by the service, mirrored here):
--   COMPLETED   : check_in_* AND check_out_* all set
--   IN_PROGRESS : check_in_* set, check_out_* NULL
--   MISSED      : all four NULL
--
-- UNIQUE(route_id, customer_id) — one visit per stop, so each row below maps to
-- exactly one assignment from section 12.
--
-- Times use explicit +03:00 (Syria local) rather than bare Z, matching the
-- project rule that clients send ISO 8601 with a real offset.
-- Locations are "lat,lng" strings copied from the customer's coordinates.

INSERT INTO visits (customer_id, representative_id, route_id, check_in_time, check_in_location, check_out_time, check_out_location, status, created_at, updated_at) VALUES
                                                                                                                                                                        -- Route 1 (rep 4, yesterday, COMPLETED) — 3 stops, all visited
                                                                                                                                                                        (1, 4, 1, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:05:00' AT TIME ZONE '+03:00', '33.5117,36.3067',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:40:00' AT TIME ZONE '+03:00', '33.5117,36.3067', 'COMPLETED', NOW(), NOW()),  -- id 1
                                                                                                                                                                        (2, 4, 1, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:15:00' AT TIME ZONE '+03:00', '33.5130,36.3200',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '10:45:00' AT TIME ZONE '+03:00', '33.5130,36.3200', 'COMPLETED', NOW(), NOW()),  -- id 2
                                                                                                                                                                        (3, 4, 1, (CURRENT_DATE - INTERVAL '1 day') + TIME '11:20:00' AT TIME ZONE '+03:00', '33.5050,36.2750',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '11:50:00' AT TIME ZONE '+03:00', '33.5050,36.2750', 'COMPLETED', NOW(), NOW()),  -- id 3

                                                                                                                                                                        -- Route 2 (rep 5, yesterday, COMPLETED) — 2 visited, 1 MISSED
                                                                                                                                                                        (4, 5, 2, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:10:00' AT TIME ZONE '+03:00', '33.4830,36.3400',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:55:00' AT TIME ZONE '+03:00', '33.4830,36.3400', 'COMPLETED', NOW(), NOW()),  -- id 4
                                                                                                                                                                        (5, 5, 2, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:30:00' AT TIME ZONE '+03:00', '33.4700,36.3600',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '11:00:00' AT TIME ZONE '+03:00', '33.4700,36.3600', 'COMPLETED', NOW(), NOW()),  -- id 5
                                                                                                                                                                        (6, 5, 2, NULL, NULL, NULL, NULL, 'MISSED', NOW(), NOW()),                                                                           -- id 6  (shop closed)

                                                                                                                                                                        -- Route 3 (rep 6, yesterday, COMPLETED) — both visited
                                                                                                                                                                        (7, 6, 3, (CURRENT_DATE - INTERVAL '1 day') + TIME '08:50:00' AT TIME ZONE '+03:00', '36.2010,37.1660',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:35:00' AT TIME ZONE '+03:00', '36.2010,37.1660', 'COMPLETED', NOW(), NOW()),  -- id 7
                                                                                                                                                                        (8, 6, 3, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:05:00' AT TIME ZONE '+03:00', '36.2070,37.1500',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '10:35:00' AT TIME ZONE '+03:00', '36.2070,37.1500', 'COMPLETED', NOW(), NOW()),  -- id 8

                                                                                                                                                                        -- Route 4 (rep 7, yesterday, COMPLETED) — both visited
                                                                                                                                                                        (9,  7, 4, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:20:00' AT TIME ZONE '+03:00', '36.1800,37.1580',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:50:00' AT TIME ZONE '+03:00', '36.1800,37.1580', 'COMPLETED', NOW(), NOW()), -- id 9
                                                                                                                                                                        (10, 7, 4, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:40:00' AT TIME ZONE '+03:00', '36.1700,37.1750',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '11:25:00' AT TIME ZONE '+03:00', '36.1700,37.1750', 'COMPLETED', NOW(), NOW()), -- id 10

                                                                                                                                                                        -- Route 5 (rep 8, yesterday, COMPLETED) — 2 visited, 1 MISSED
                                                                                                                                                                        (11, 8, 5, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:00:00' AT TIME ZONE '+03:00', '34.7350,36.7140',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:30:00' AT TIME ZONE '+03:00', '34.7350,36.7140', 'COMPLETED', NOW(), NOW()), -- id 11
                                                                                                                                                                        (12, 8, 5, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:10:00' AT TIME ZONE '+03:00', '34.7500,36.6800',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '10:45:00' AT TIME ZONE '+03:00', '34.7500,36.6800', 'COMPLETED', NOW(), NOW()), -- id 12
                                                                                                                                                                        (13, 8, 5, NULL, NULL, NULL, NULL, 'MISSED', NOW(), NOW()),                                                                          -- id 13 (owner away)

                                                                                                                                                                        -- Route 6 (rep 9, yesterday, COMPLETED) — both visited
                                                                                                                                                                        (14, 9, 6, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:15:00' AT TIME ZONE '+03:00', '35.5197,35.7920',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '09:55:00' AT TIME ZONE '+03:00', '35.5197,35.7920', 'COMPLETED', NOW(), NOW()), -- id 14
                                                                                                                                                                        (15, 9, 6, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:25:00' AT TIME ZONE '+03:00', '35.5100,35.7800',
                                                                                                                                                                         (CURRENT_DATE - INTERVAL '1 day') + TIME '10:55:00' AT TIME ZONE '+03:00', '35.5100,35.7800', 'COMPLETED', NOW(), NOW()), -- id 15

                                                                                                                                                                        -- Route 7 (rep 4, TODAY, ACTIVE) — rep is inside customer 1 right now
                                                                                                                                                                        (1, 4, 7, CURRENT_DATE + TIME '09:05:00' AT TIME ZONE '+03:00', '33.5117,36.3067',
                                                                                                                                                                         NULL, NULL, 'IN_PROGRESS', NOW(), NOW());                                                                                  -- id 16


-- ============================================================================
-- 14. INVOICES  (12 rows — id 1–12)
-- ============================================================================
-- Full lifecycle coverage:
--   5 APPROVED (id 1–5)  — reviewed_by_id set, rejection_reason NULL
--   3 SENT     (id 6–8)  — awaiting review, both review columns NULL
--   2 REJECTED (id 9–10) — reviewed_by_id set, non-blank rejection_reason
--   2 DRAFT    (id 11–12)— editable, no review, no stock moved
--
-- STOCK INVARIANT: every invoice in SENT / APPROVED / REJECTED has already had
-- its quantities deducted from the rep's van in section 8. DRAFT invoices have
-- not. This is decision D1 (deduct at SENT, no auto-credit on rejection).
--
-- Reviewer must be SALES_MANAGER or ADMIN — using manager 2 (Ahmad) for reps
-- 4/5/6 and manager 3 (Hussam) for reps 7/8/9, matching the demand-order split.
--
-- visit_id is nullable (D13). Invoices 1–10 are bound to the COMPLETED visits
-- from section 13; invoice 11 is deliberately left NULL to exercise the
-- ad-hoc / off-route sale path (SRS alt-flow 4A).
--
-- client_uuid is NULL for all rows: these represent online-created invoices,
-- where there is no retry-duplication risk (D22).
--
-- total_amount = sum(line subtotals), where subtotal = quantity*price - discount.
-- Every figure below is arithmetically checked against section 15.

INSERT INTO invoices (customer_id, representative_id, visit_id, invoice_date, total_amount, status, rejection_reason, reviewed_by_id, client_uuid, created_at, updated_at) VALUES
                                                                                                                                                                               -- APPROVED (id 1–5) — clean revenue, manager signed off
                                                                                                                                                                               (1,  4, 1,  CURRENT_DATE - INTERVAL '1 day', 24000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- id 1
                                                                                                                                                                               (2,  4, 2,  CURRENT_DATE - INTERVAL '1 day', 28000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- id 2
                                                                                                                                                                               (4,  5, 4,  CURRENT_DATE - INTERVAL '1 day', 52000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- id 3
                                                                                                                                                                               (7,  6, 7,  CURRENT_DATE - INTERVAL '1 day', 39500.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- id 4
                                                                                                                                                                               (9,  7, 9,  CURRENT_DATE - INTERVAL '1 day', 40400.00, 'APPROVED', NULL, 3, NULL, NOW(), NOW()),  -- id 5

                                                                                                                                                                               -- SENT (id 6–8) — submitted, stock deducted, not yet reviewed
                                                                                                                                                                               (5,  5, 5,  CURRENT_DATE - INTERVAL '1 day', 36000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- id 6
                                                                                                                                                                               (10, 7, 10, CURRENT_DATE - INTERVAL '1 day', 33000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- id 7
                                                                                                                                                                               (11, 8, 11, CURRENT_DATE - INTERVAL '1 day', 10000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- id 8

                                                                                                                                                                               -- REJECTED (id 9–10) — flagged by manager, reason mandatory and non-blank (BR-3).
                                                                                                                                                                               -- Stock stays deducted: the sale physically happened. A correction is a NEW invoice.
                                                                                                                                                                               (8,  6, 8,  CURRENT_DATE - INTERVAL '1 day', 30000.00, 'REJECTED',
                                                                                                                                                                                'Customer disputed the delivered quantity; goods returned to van without a return sheet.', 2, NULL, NOW(), NOW()),  -- id 9
                                                                                                                                                                               (14, 9, 14, CURRENT_DATE - INTERVAL '1 day', 48500.00, 'REJECTED',
                                                                                                                                                                                'Price applied does not match the approved price list for this outlet category.',          3, NULL, NOW(), NOW()),  -- id 10

                                                                                                                                                                               -- DRAFT (id 11–12) — editable, nothing deducted, no review columns.
                                                                                                                                                                               -- id 11 has visit_id NULL on purpose: ad-hoc sale with no visit (D13).
                                                                                                                                                                               (12, 8, NULL, CURRENT_DATE, 16000.00, 'DRAFT', NULL, NULL, NULL, NOW(), NOW()),  -- id 11
                                                                                                                                                                               (15, 9, 15,   CURRENT_DATE, 47500.00, 'DRAFT', NULL, NULL, NULL, NOW(), NOW());  -- id 12


-- ============================================================================
-- 15. INVOICE LINE ITEMS  (21 rows)
-- ============================================================================
-- subtotal = quantity * price - discount, all NUMERIC(12,2).
-- price is the frozen capture from products (section 3) at submit time (BR-9).
-- UNIQUE(invoice_id, product_id) — never two lines for the same product.
-- Every product sold here is present on the selling rep's van (section 8).

INSERT INTO invoice_line_items (invoice_id, product_id, quantity, price, discount, subtotal, created_at, updated_at) VALUES
                                                                                                                         -- Invoice 1 (rep 4, APPROVED, total 24,000.00)
                                                                                                                         (1,  1,  10, 1500.00,    0.00, 15000.00, NOW(), NOW()),   -- Coca-Cola   10 x 1500
                                                                                                                         (1,  3,   5, 2000.00, 1000.00,  9000.00, NOW(), NOW()),   -- Lays         5 x 2000 − 1000

                                                                                                                         -- Invoice 2 (rep 4, APPROVED, total 28,000.00)
                                                                                                                         (2,  9,   8, 3500.00,    0.00, 28000.00, NOW(), NOW()),   -- Rani Juice   8 x 3500

                                                                                                                         -- Invoice 3 (rep 5, APPROVED, total 52,000.00)
                                                                                                                         (3,  5,   3, 12000.00,   0.00, 36000.00, NOW(), NOW()),   -- Tide         3 x 12000
                                                                                                                         (3, 14,   6, 3000.00, 2000.00, 16000.00, NOW(), NOW()),   -- Colgate      6 x 3000 − 2000

                                                                                                                         -- Invoice 4 (rep 6, APPROVED, total 39,500.00)
                                                                                                                         (4,  4,  15, 500.00,     0.00,  7500.00, NOW(), NOW()),   -- Nescafe     15 x 500
                                                                                                                         (4,  7,   4, 8000.00,    0.00, 32000.00, NOW(), NOW()),   -- Tahini       4 x 8000

                                                                                                                         -- Invoice 5 (rep 7, APPROVED, total 40,400.00)
                                                                                                                         (5,  2,  12, 1400.00, 1400.00, 15400.00, NOW(), NOW()),   -- Pepsi       12 x 1400 − 1400
                                                                                                                         (5, 13,  10, 2500.00,    0.00, 25000.00, NOW(), NOW()),   -- Indomie     10 x 2500

                                                                                                                         -- Invoice 6 (rep 5, SENT, total 36,000.00)
                                                                                                                         (6,  6,   4, 4500.00,    0.00, 18000.00, NOW(), NOW()),   -- Fairy        4 x 4500
                                                                                                                         (6, 11,   3, 6000.00,    0.00, 18000.00, NOW(), NOW()),   -- Puck Cheese  3 x 6000

                                                                                                                         -- Invoice 7 (rep 7, SENT, total 33,000.00)
                                                                                                                         (7, 10,   6, 3000.00,    0.00, 18000.00, NOW(), NOW()),   -- Galaxy       6 x 3000
                                                                                                                         (7, 15,   2, 7500.00,    0.00, 15000.00, NOW(), NOW()),   -- Head&Should  2 x 7500

                                                                                                                         -- Invoice 8 (rep 8, SENT, total 10,000.00)
                                                                                                                         (8,  1,   7, 1500.00,  500.00, 10000.00, NOW(), NOW()),   -- Coca-Cola    7 x 1500 − 500

                                                                                                                         -- Invoice 9 (rep 6, REJECTED, total 30,000.00)
                                                                                                                         (9,  8,   2, 15000.00,   0.00, 30000.00, NOW(), NOW()),   -- Sunflower    2 x 15000

                                                                                                                         -- Invoice 10 (rep 9, REJECTED, total 48,500.00)
                                                                                                                         (10, 12,  2, 18000.00,   0.00, 36000.00, NOW(), NOW()),   -- Nido         2 x 18000
                                                                                                                         (10, 13,  5, 2500.00,    0.00, 12500.00, NOW(), NOW()),   -- Indomie      5 x 2500

                                                                                                                         -- Invoice 11 (rep 8, DRAFT, total 16,000.00) — NOT deducted from van
                                                                                                                         (11,  2,  5, 1400.00,    0.00,  7000.00, NOW(), NOW()),   -- Pepsi        5 x 1400
                                                                                                                         (11, 14,  3, 3000.00,    0.00,  9000.00, NOW(), NOW()),   -- Colgate      3 x 3000

                                                                                                                         -- Invoice 12 (rep 9, DRAFT, total 47,500.00) — NOT deducted from van
                                                                                                                         (12, 11,  4, 6000.00,    0.00, 24000.00, NOW(), NOW()),   -- Puck Cheese  4 x 6000
                                                                                                                         (12,  7,  3, 8000.00,  500.00, 23500.00, NOW(), NOW());   -- Tahini       3 x 8000 − 500


-- ============================================================================
-- 16. EPOD ARTIFACTS  (16 rows)
-- ============================================================================
-- Electronic Proof of Delivery — captured and frozen at submit, never mutated.
-- Only invoices that reached SENT have artifacts; DRAFT invoices (11, 12) have
-- none, because artifacts are captured at the submit transition.
--
-- Each submitted invoice gets one SIGNATURE and one DELIVERY_PHOTO
-- (UNIQUE(invoice_id, type) allows at most one of each).
--
-- hash is a placeholder 64-char SHA-256-shaped hex string. In production it is
-- SHA-256(invoiceId + customerId + total + file bytes), computed server-side.
-- GPS matches the customer's coordinates (capture happened at the outlet).

INSERT INTO epod_artifacts (invoice_id, type, url, hash, latitude, longitude, captured_at, created_at, updated_at) VALUES
                                                                                                                       -- Invoice 1 — customer 1, Al-Hamidiyah Market
                                                                                                                       (1, 'SIGNATURE',      'signatures/invoice_1.png', 'a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801', 33.5117, 36.3067, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:38:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (1, 'DELIVERY_PHOTO', 'photos/invoice_1.jpg',     'b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2', 33.5117, 36.3067, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:39:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 2 — customer 2, Sham Grocery
                                                                                                                       (2, 'SIGNATURE',      'signatures/invoice_2.png', 'c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3', 33.5130, 36.3200, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:43:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (2, 'DELIVERY_PHOTO', 'photos/invoice_2.jpg',     'd4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4', 33.5130, 36.3200, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:44:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 3 — customer 4, Jaramana Superstore
                                                                                                                       (3, 'SIGNATURE',      'signatures/invoice_3.png', 'e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5', 33.4830, 36.3400, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:52:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (3, 'DELIVERY_PHOTO', 'photos/invoice_3.jpg',     'f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6', 33.4830, 36.3400, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:53:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 4 — customer 7, Aleppo Trade Center
                                                                                                                       (4, 'SIGNATURE',      'signatures/invoice_4.png', '0718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f7', 36.2010, 37.1660, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:32:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (4, 'DELIVERY_PHOTO', 'photos/invoice_4.jpg',     '18293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708', 36.2010, 37.1660, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:33:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 5 — customer 9, Salaheddine Market
                                                                                                                       (5, 'SIGNATURE',      'signatures/invoice_5.png', '8293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f7081', 36.1800, 37.1580, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:47:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (5, 'DELIVERY_PHOTO', 'photos/invoice_5.jpg',     '93a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192', 36.1800, 37.1580, (CURRENT_DATE - INTERVAL '1 day') + TIME '09:48:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 6 — customer 5, Mleiha Corner Shop (SENT)
                                                                                                                       (6, 'SIGNATURE',      'signatures/invoice_6.png', 'a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3', 33.4700, 36.3600, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:58:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (6, 'DELIVERY_PHOTO', 'photos/invoice_6.jpg',     'b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4', 33.4700, 36.3600, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:59:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 7 — customer 10, Shahba Wholesaler (SENT)
                                                                                                                       (7, 'SIGNATURE',      'signatures/invoice_7.png', 'c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5', 36.1700, 37.1750, (CURRENT_DATE - INTERVAL '1 day') + TIME '11:22:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (7, 'DELIVERY_PHOTO', 'photos/invoice_7.jpg',     'd7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6', 36.1700, 37.1750, (CURRENT_DATE - INTERVAL '1 day') + TIME '11:23:00' AT TIME ZONE '+03:00', NOW(), NOW()),

                                                                                                                       -- Invoice 9 — customer 8, Northern Souk Retailer (REJECTED — artifacts still exist,
                                                                                                                       -- they were captured at submit; rejection does not erase the proof of delivery)
                                                                                                                       (9, 'SIGNATURE',      'signatures/invoice_9.png', 'e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6e7', 36.2070, 37.1500, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:33:00' AT TIME ZONE '+03:00', NOW(), NOW()),
                                                                                                                       (9, 'DELIVERY_PHOTO', 'photos/invoice_9.jpg',     'f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6e7f8', 36.2070, 37.1500, (CURRENT_DATE - INTERVAL '1 day') + TIME '10:34:00' AT TIME ZONE '+03:00', NOW(), NOW());
-- Invoice 8 and 10 deliberately have no artifacts: exercises the "submitted
-- without ePOD" edge case the dashboard should be able to surface.


-- ============================================================================
-- SEED COMPLETE
-- ============================================================================
-- Summary:
--   6  territories
--   11 users (1 admin, 2 managers, 6 reps, 2 warehouse)
--   15 products (all ACTIVE)
--   15 customers (spread across 6 territories)
--   15 warehouse stock items
--   21 van inventory items (post-invoice-deduction)
--   12 demand orders (6 LOADED, 3 SUBMITTED, 3 ADJUSTED)
--   39 demand order lines
--    8 return sheets (4 DRAFT, 4 COMPLETED)
--   15 return sheet lines
--   10 routes (6 COMPLETED, 3 ACTIVE, 1 PLANNED)
--   23 route customer assignments
--   16 visits (13 COMPLETED, 2 MISSED, 1 IN_PROGRESS)
--   12 invoices (5 APPROVED, 3 SENT, 2 REJECTED, 2 DRAFT)
--   21 invoice line items
--   16 ePOD artifacts (8 invoices x signature + photo)
--
-- INVARIANTS HELD:
--   * Every FK resolves to a seeded row.
--   * Every route stop's customer belongs to the route's territory.
--   * Every visit maps to exactly one (route, customer) assignment.
--   * Every invoice's rep matches its visit's rep and its customer.
--   * Van inventory = loaded quantities MINUS all SENT/APPROVED/REJECTED
--     invoice quantities. DRAFT invoices are not deducted.
--   * total_amount = sum of that invoice's line subtotals.
--   * REJECTED invoices have a non-blank rejection_reason and a reviewer.
--   * SENT invoices have neither reviewer nor reason.
--
-- All email/password combos work with: Owais@1234
-- Login reference:
--   admin@sm.com          -> ADMIN
--   ahmad.mgr@sm.com      -> SALES_MANAGER   (reviews invoices 1,2,3,4,9)
--   hussam.mgr@sm.com     -> SALES_MANAGER   (reviews invoices 5,10)
--   khaled.rep@sm.com     -> SALES_REP  (id 4, territory 1)
--   shadi.rep@sm.com      -> SALES_REP  (id 5, territory 2)
--   omar.rep@sm.com       -> SALES_REP  (id 6, territory 3)
--   rami.rep@sm.com       -> SALES_REP  (id 7, territory 4)
--   fadi.rep@sm.com       -> SALES_REP  (id 8, territory 5)
--   mazen.rep@sm.com      -> SALES_REP  (id 9, territory 6)
--   bilal.wh@sm.com       -> WAREHOUSE_MANAGER
--   nour.wh@sm.com        -> WAREHOUSE_MANAGER
-- ============================================================================