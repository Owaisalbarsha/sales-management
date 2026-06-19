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
TRUNCATE TABLE return_sheet_lines,
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
-- 8. VAN INVENTORY  (for all 6 reps from LOADED demand orders)
-- ============================================================================
-- These quantities match the FulfilledQty from LOADED orders (1–6).
-- In production, loading a demand order moves stock from warehouse → van.
-- Here we just reflect the final state.

INSERT INTO van_inventory_items (representative_id, product_id, quantity, created_at, updated_at) VALUES
                                                                                                      -- Rep 4 (Khaled) — from order 1
                                                                                                      (4, 1,  30, NOW(), NOW()),   -- Coca-Cola
                                                                                                      (4, 3,  20, NOW(), NOW()),   -- Lays
                                                                                                      (4, 9,  25, NOW(), NOW()),   -- Rani Juice

                                                                                                      -- Rep 5 (Shadi) — from order 2
                                                                                                      (5, 5,  10, NOW(), NOW()),   -- Tide
                                                                                                      (5, 6,  15, NOW(), NOW()),   -- Fairy
                                                                                                      (5, 11, 12, NOW(), NOW()),   -- Puck Cheese
                                                                                                      (5, 14, 20, NOW(), NOW()),   -- Colgate

                                                                                                      -- Rep 6 (Omar) — from order 3
                                                                                                      (6, 7,  15, NOW(), NOW()),   -- Tahini
                                                                                                      (6, 8,  10, NOW(), NOW()),   -- Sunflower Oil
                                                                                                      (6, 4,  40, NOW(), NOW()),   -- Nescafe

                                                                                                      -- Rep 7 (Rami) — from order 4
                                                                                                      (7, 2,  35, NOW(), NOW()),   -- Pepsi
                                                                                                      (7, 10, 25, NOW(), NOW()),   -- Galaxy
                                                                                                      (7, 13, 30, NOW(), NOW()),   -- Indomie
                                                                                                      (7, 15, 10, NOW(), NOW()),   -- Head & Shoulders

                                                                                                      -- Rep 8 (Fadi) — from order 5
                                                                                                      (8, 1,  20, NOW(), NOW()),   -- Coca-Cola
                                                                                                      (8, 2,  20, NOW(), NOW()),   -- Pepsi
                                                                                                      (8, 14, 15, NOW(), NOW()),   -- Colgate

                                                                                                      -- Rep 9 (Mazen) — from order 6
                                                                                                      (9, 12, 8,  NOW(), NOW()),   -- Nido
                                                                                                      (9, 11, 10, NOW(), NOW()),   -- Puck Cheese
                                                                                                      (9, 7,  12, NOW(), NOW()),   -- Tahini
                                                                                                      (9, 13, 25, NOW(), NOW());   -- Indomie


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
-- SEED COMPLETE
-- ============================================================================
-- Summary:
--   6  territories
--   11 users (1 admin, 2 managers, 6 reps, 2 warehouse)
--   15 products (all ACTIVE)
--   15 customers (spread across 6 territories)
--   15 warehouse stock items
--   24 van inventory items (6 reps loaded)
--   12 demand orders (6 LOADED, 3 SUBMITTED, 3 ADJUSTED)
--   38 demand order lines
--    8 return sheets (4 DRAFT, 4 COMPLETED)
--   17 return sheet lines
--
-- All foreign keys are referentially valid.
-- All email/password combos work with: Owais@1234
-- Login reference:
--   admin@sm.com          → ADMIN
--   ahmad.mgr@sm.com      → SALES_MANAGER
--   hussam.mgr@sm.com     → SALES_MANAGER
--   khaled.rep@sm.com     → SALES_REP
--   shadi.rep@sm.com      → SALES_REP
--   omar.rep@sm.com       → SALES_REP
--   rami.rep@sm.com       → SALES_REP
--   fadi.rep@sm.com       → SALES_REP
--   mazen.rep@sm.com      → SALES_REP
--   bilal.wh@sm.com       → WAREHOUSE_MANAGER
--   nour.wh@sm.com        → WAREHOUSE_MANAGER
-- ============================================================================