-- ============================================================================
-- REPEATABLE FLYWAY SEED — بيانات تجريبية لنظام إدارة المبيعات
-- ============================================================================
-- File:     db/seeder/R__seed_demo_data.sql
-- Purpose:  Referentially consistent Arabic demo data (Damascus context) across
--           all completed modules EXCEPT notification and sync (left empty).
--           Runs AFTER all versioned migrations. Re-runs on any edit (checksum).
--
-- Login is by PHONE NUMBER (column users.phone_number), not email.
-- Password for ALL users: Owais@1234
-- BCrypt hash: $2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi
--
-- Admin login:  { "phone_number": "+963981491713", "password": "Owais@1234" }
--
-- To full-reset: DROP SCHEMA public CASCADE; CREATE SCHEMA public; then restart.
-- ============================================================================

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
-- 1. المناطق / TERRITORIES  (8 rows — id 1–8) — أحياء ومناطق دمشق
-- ============================================================================
INSERT INTO territories (name, description, created_at, updated_at) VALUES
                                                                        ('المزة',          'حي المزة والفيلات الغربية والشرقية',          NOW(), NOW()),  -- 1
                                                                        ('المالكي',        'منطقة المالكي والروضة التجارية',              NOW(), NOW()),  -- 2
                                                                        ('باب توما',       'باب توما والقيمرية في دمشق القديمة',          NOW(), NOW()),  -- 3
                                                                        ('الميدان',        'حي الميدان الجنوبي وأسواقه الشعبية',          NOW(), NOW()),  -- 4
                                                                        ('القابون',        'القابون الصناعي والسكني شمال شرق دمشق',       NOW(), NOW()),  -- 5
                                                                        ('ركن الدين',      'ركن الدين وسفح قاسيون',                       NOW(), NOW()),  -- 6
                                                                        ('مشروع دمر',      'مشروع دمر والمنطقة السكنية الغربية',          NOW(), NOW()),  -- 7
                                                                        ('ضاحية قدسيا',    'ضاحية قدسيا شمال غرب دمشق',                   NOW(), NOW());  -- 8


-- ============================================================================
-- 2. المستخدمون / USERS  (11 rows — id 1–11)
-- ============================================================================
-- تسجيل الدخول عبر رقم الهاتف. كلمة المرور للجميع: Owais@1234
-- 1 مدير نظام · 2–3 مدير مبيعات · 4–9 مندوبو مبيعات · 10–11 مدير مستودع
INSERT INTO users (name, phone_number, password_hash, role, status, created_at, updated_at) VALUES
                                                                                                ('أويس',            '+963981491713', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'ADMIN',             'ACTIVE', NOW(), NOW()),  -- 1
                                                                                                ('أحمد الحسن',      '+963991000002', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_MANAGER',     'ACTIVE', NOW(), NOW()),  -- 2
                                                                                                ('حسام رقية',       '+963991000003', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_MANAGER',     'ACTIVE', NOW(), NOW()),  -- 3
                                                                                                ('خالد حاج عثمان',  '+963991000004', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 4
                                                                                                ('شادي حمزة',       '+963991000005', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 5
                                                                                                ('عمر بكري',        '+963991000006', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 6
                                                                                                ('رامي صالح',       '+963991000007', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 7
                                                                                                ('فادي ناصر',       '+963991000008', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 8
                                                                                                ('مازن خوري',       '+963991000009', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'SALES_REP',         'ACTIVE', NOW(), NOW()),  -- 9
                                                                                                ('بلال المستودع',   '+963991000010', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'WAREHOUSE_MANAGER', 'ACTIVE', NOW(), NOW()),  -- 10
                                                                                                ('نور المستودع',    '+963991000011', '$2b$10$WleNbitKSsMmg8DKmjYhQuIyoiAQjyhO9gjECjsgD2ULt23ocPZvi', 'WAREHOUSE_MANAGER', 'ACTIVE', NOW(), NOW());  -- 11


-- ============================================================================
-- 3. المنتجات / PRODUCTS  (15 rows — id 1–15)  — SKU/باركود بالإنجليزية
-- ============================================================================
INSERT INTO products (name, sku, barcode, price, unit_of_measure, min_stock_level, status, created_at, updated_at) VALUES
                                                                                                                       ('كوكا كولا ٣٣٠ مل',        'BEV-001', '6281036000019', 1500.00,  'PIECE', 100, 'ACTIVE', NOW(), NOW()),  -- 1
                                                                                                                       ('بيبسي ٣٣٠ مل',           'BEV-002', '6281036000026', 1400.00,  'PIECE', 100, 'ACTIVE', NOW(), NOW()),  -- 2
                                                                                                                       ('شيبس ليز ١٦٠ غ',         'SNK-001', '6281036000033', 2000.00,  'PIECE',  80, 'ACTIVE', NOW(), NOW()),  -- 3
                                                                                                                       ('نسكافيه ٣×١ ظرف',        'BEV-003', '6281036000040', 500.00,   'PIECE', 200, 'ACTIVE', NOW(), NOW()),  -- 4
                                                                                                                       ('مسحوق تايد ٣ كغ',        'CLN-001', '6281036000057', 12000.00, 'PIECE',  40, 'ACTIVE', NOW(), NOW()),  -- 5
                                                                                                                       ('سائل جلي فيري ٧٥٠ مل',   'CLN-002', '6281036000064', 4500.00,  'PIECE',  60, 'ACTIVE', NOW(), NOW()),  -- 6
                                                                                                                       ('طحينة الدرة ٩٠٠ غ',      'FOD-001', '6281036000071', 8000.00,  'PIECE',  50, 'ACTIVE', NOW(), NOW()),  -- 7
                                                                                                                       ('زيت دوار الشمس ١٫٥ ل',   'FOD-002', '6281036000088', 15000.00, 'PIECE',  30, 'ACTIVE', NOW(), NOW()),  -- 8
                                                                                                                       ('عصير راني مانجا ١ ل',    'BEV-004', '6281036000095', 3500.00,  'PIECE',  70, 'ACTIVE', NOW(), NOW()),  -- 9
                                                                                                                       ('شوكولا غالاكسي ٩٠ غ',    'SNK-002', '6281036000101', 3000.00,  'PIECE',  90, 'ACTIVE', NOW(), NOW()),  -- 10
                                                                                                                       ('جبنة بوك ٥٠٠ غ',         'DAI-001', '6281036000118', 6000.00,  'PIECE',  40, 'ACTIVE', NOW(), NOW()),  -- 11
                                                                                                                       ('حليب نيدو ٩٠٠ غ',        'DAI-002', '6281036000125', 18000.00, 'PIECE',  25, 'ACTIVE', NOW(), NOW()),  -- 12
                                                                                                                       ('اندومي ٥ أظرف',         'FOD-003', '6281036000132', 2500.00,  'PIECE', 120, 'ACTIVE', NOW(), NOW()),  -- 13
                                                                                                                       ('معجون كولجيت ١٠٠ مل',    'PER-001', '6281036000149', 3000.00,  'PIECE',  60, 'ACTIVE', NOW(), NOW()),  -- 14
                                                                                                                       ('شامبو هيد أند شولدرز',   'PER-002', '6281036000156', 7500.00,  'PIECE',  35, 'ACTIVE', NOW(), NOW());  -- 15


-- ============================================================================
-- 4. الزبائن / CUSTOMERS  (15 rows — id 1–15) — موزّعون على مناطق دمشق
-- ============================================================================
-- خريطة المنطقة ← أرقام الزبائن:
--   ١ المزة        : 1, 2, 3
--   ٢ المالكي      : 4, 5
--   ٣ باب توما     : 6, 7
--   ٤ الميدان      : 8, 9, 10
--   ٥ القابون      : 11, 12
--   ٦ ركن الدين    : 13
--   ٧ مشروع دمر    : 14
--   ٨ ضاحية قدسيا  : 15
INSERT INTO customers (territory_id, name, address, phone, latitude, longitude, category, status, created_at, updated_at) VALUES
                                                                                                                              -- المزة (1)
                                                                                                                              (1, 'سوبر ماركت المزة',      'المزة أوتوستراد، دمشق',           '+963111234501', 33.5010, 36.2560, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),  -- 1
                                                                                                                              (1, 'بقالة الفيلات',          'المزة فيلات غربية، دمشق',         '+963111234502', 33.5045, 36.2480, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 2
                                                                                                                              (1, 'ميني ماركت الروضة',      'المزة جبل، دمشق',                 '+963111234503', 33.4980, 36.2610, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 3

                                                                                                                              -- المالكي (2)
                                                                                                                              (2, 'ماركت المالكي المركزي',  'شارع المالكي، دمشق',              '+963111234504', 33.5150, 36.2830, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),  -- 4
                                                                                                                              (2, 'بقالة أبو رمانة',        'أبو رمانة، دمشق',                 '+963111234505', 33.5185, 36.2890, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 5

                                                                                                                              -- باب توما (3)
                                                                                                                              (3, 'سوق باب توما',           'باب توما، دمشق القديمة',          '+963111234506', 33.5130, 36.3170, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),  -- 6
                                                                                                                              (3, 'بقالة القيمرية',         'القيمرية، دمشق القديمة',          '+963111234507', 33.5110, 36.3140, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 7

                                                                                                                              -- الميدان (4)
                                                                                                                              (4, 'سوق الميدان الكبير',     'شارع الميدان، دمشق',              '+963111234508', 33.4870, 36.2960, 'WHOLESALE',   'ACTIVE', NOW(), NOW()),  -- 8
                                                                                                                              (4, 'حلويات الميدان',         'الميدان التحتاني، دمشق',          '+963111234509', 33.4840, 36.2990, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 9
                                                                                                                              (4, 'ميني ماركت البوابة',     'بوابة الميدان، دمشق',             '+963111234510', 33.4900, 36.2940, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 10

                                                                                                                              -- القابون (5)
                                                                                                                              (5, 'ماركت القابون الصناعي',  'القابون الصناعية، دمشق',          '+963111234511', 33.5420, 36.3320, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),  -- 11
                                                                                                                              (5, 'بقالة الحي الشمالي',     'القابون السكني، دمشق',            '+963111234512', 33.5460, 36.3280, 'RETAIL',      'ACTIVE', NOW(), NOW()),  -- 12

                                                                                                                              -- ركن الدين (6)
                                                                                                                              (6, 'سوبر ماركت ركن الدين',   'ركن الدين، سفح قاسيون',           '+963111234513', 33.5330, 36.2960, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),  -- 13

                                                                                                                              -- مشروع دمر (7)
                                                                                                                              (7, 'ماركت مشروع دمر',        'مشروع دمر، دمشق',                 '+963111234514', 33.5230, 36.2260, 'SUPERMARKET', 'ACTIVE', NOW(), NOW()),  -- 14

                                                                                                                              -- ضاحية قدسيا (8)
                                                                                                                              (8, 'ماركت ضاحية قدسيا',      'ضاحية قدسيا، ريف دمشق',           '+963111234515', 33.5670, 36.2160, 'WHOLESALE',   'ACTIVE', NOW(), NOW());  -- 15


-- ============================================================================
-- 5. مخزون المستودع / WAREHOUSE STOCK  (15 rows)
-- ============================================================================
INSERT INTO warehouse_stock_items (product_id, quantity, created_at, updated_at) VALUES
                                                                                     (1,500,NOW(),NOW()),(2,450,NOW(),NOW()),(3,300,NOW(),NOW()),(4,800,NOW(),NOW()),
                                                                                     (5,150,NOW(),NOW()),(6,200,NOW(),NOW()),(7,180,NOW(),NOW()),(8,120,NOW(),NOW()),
                                                                                     (9,350,NOW(),NOW()),(10,400,NOW(),NOW()),(11,160,NOW(),NOW()),(12,100,NOW(),NOW()),
                                                                                     (13,600,NOW(),NOW()),(14,250,NOW(),NOW()),(15,140,NOW(),NOW());


-- ============================================================================
-- 6. طلبات التزويد / DEMAND ORDERS  (12 rows — id 1–12)
-- ============================================================================
-- المدير ٢ (أحمد) ← المندوبون ٤،٥،٦ | المدير ٣ (حسام) ← المندوبون ٧،٨،٩
INSERT INTO demand_orders (sales_manager_id, representative_id, order_date, status, created_at, updated_at) VALUES
                                                                                                                (2, 4, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 1
                                                                                                                (2, 5, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 2
                                                                                                                (2, 6, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 3
                                                                                                                (3, 7, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 4
                                                                                                                (3, 8, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 5
                                                                                                                (3, 9, CURRENT_DATE - 2, 'LOADED',    NOW(), NOW()),  -- 6
                                                                                                                (2, 4, CURRENT_DATE,     'SUBMITTED', NOW(), NOW()),  -- 7
                                                                                                                (2, 5, CURRENT_DATE,     'SUBMITTED', NOW(), NOW()),  -- 8
                                                                                                                (3, 7, CURRENT_DATE,     'SUBMITTED', NOW(), NOW()),  -- 9
                                                                                                                (2, 6, CURRENT_DATE,     'ADJUSTED',  NOW(), NOW()),  -- 10
                                                                                                                (3, 8, CURRENT_DATE,     'ADJUSTED',  NOW(), NOW()),  -- 11
                                                                                                                (3, 9, CURRENT_DATE,     'ADJUSTED',  NOW(), NOW());  -- 12


-- ============================================================================
-- 7. بنود طلبات التزويد / DEMAND ORDER LINES
-- ============================================================================
INSERT INTO demand_order_lines (demand_order_id, product_id, requested_qty, fulfilled_qty, created_at, updated_at) VALUES
                                                                                                                       (1,1,30,30,NOW(),NOW()),(1,3,20,20,NOW(),NOW()),(1,9,25,25,NOW(),NOW()),
                                                                                                                       (2,5,10,10,NOW(),NOW()),(2,6,15,15,NOW(),NOW()),(2,11,12,12,NOW(),NOW()),(2,14,20,20,NOW(),NOW()),
                                                                                                                       (3,7,15,15,NOW(),NOW()),(3,8,10,10,NOW(),NOW()),(3,4,40,40,NOW(),NOW()),
                                                                                                                       (4,2,35,35,NOW(),NOW()),(4,10,25,25,NOW(),NOW()),(4,13,30,30,NOW(),NOW()),(4,15,10,10,NOW(),NOW()),
                                                                                                                       (5,1,20,20,NOW(),NOW()),(5,2,20,20,NOW(),NOW()),(5,14,15,15,NOW(),NOW()),
                                                                                                                       (6,12,8,8,NOW(),NOW()),(6,11,10,10,NOW(),NOW()),(6,7,12,12,NOW(),NOW()),(6,13,25,25,NOW(),NOW()),
                                                                                                                       (7,2,25,25,NOW(),NOW()),(7,10,20,20,NOW(),NOW()),(7,4,30,30,NOW(),NOW()),
                                                                                                                       (8,1,15,15,NOW(),NOW()),(8,13,20,20,NOW(),NOW()),(8,9,15,15,NOW(),NOW()),
                                                                                                                       (9,3,18,18,NOW(),NOW()),(9,6,12,12,NOW(),NOW()),(9,15,8,8,NOW(),NOW()),
                                                                                                                       -- ADJUSTED: بند واحد على الأقل مخفّض
                                                                                                                       (10,8,20,10,NOW(),NOW()),(10,4,25,25,NOW(),NOW()),(10,1,15,15,NOW(),NOW()),
                                                                                                                       (11,12,15,7,NOW(),NOW()),(11,3,20,20,NOW(),NOW()),(11,10,15,15,NOW(),NOW()),
                                                                                                                       (12,15,12,5,NOW(),NOW()),(12,2,20,20,NOW(),NOW()),(12,7,10,10,NOW(),NOW());


-- ============================================================================
-- 8. مخزون المركبات / VAN INVENTORY  (post-invoice-deduction)
-- ============================================================================
-- الكمية = المُحمّل من الطلبات LOADED ناقص ما بيع على فواتير SENT/APPROVED/REJECTED.
-- فواتير DRAFT لا تُخصم. لا يوجد صف يصل للصفر (لا حذف).
INSERT INTO van_inventory_items (representative_id, product_id, quantity, created_at, updated_at) VALUES
                                                                                                      -- المندوب ٤ (خالد)
                                                                                                      (4, 1,  20, NOW(), NOW()),   -- 30 − 10
                                                                                                      (4, 3,  15, NOW(), NOW()),   -- 20 − 5
                                                                                                      (4, 9,  17, NOW(), NOW()),   -- 25 − 8
                                                                                                      -- المندوب ٥ (شادي)
                                                                                                      (5, 5,  7,  NOW(), NOW()),   -- 10 − 3
                                                                                                      (5, 6,  11, NOW(), NOW()),   -- 15 − 4
                                                                                                      (5, 11, 9,  NOW(), NOW()),   -- 12 − 3
                                                                                                      (5, 14, 14, NOW(), NOW()),   -- 20 − 6
                                                                                                      -- المندوب ٦ (عمر)
                                                                                                      (6, 7,  11, NOW(), NOW()),   -- 15 − 4
                                                                                                      (6, 8,  8,  NOW(), NOW()),   -- 10 − 2
                                                                                                      (6, 4,  25, NOW(), NOW()),   -- 40 − 15
                                                                                                      -- المندوب ٧ (رامي)
                                                                                                      (7, 2,  23, NOW(), NOW()),   -- 35 − 12
                                                                                                      (7, 10, 19, NOW(), NOW()),   -- 25 − 6
                                                                                                      (7, 13, 20, NOW(), NOW()),   -- 30 − 10
                                                                                                      (7, 15, 8,  NOW(), NOW()),   -- 10 − 2
                                                                                                      -- المندوب ٨ (فادي) — الفاتورة ١١ مسودة فلا تُخصم
                                                                                                      (8, 1,  13, NOW(), NOW()),   -- 20 − 7
                                                                                                      (8, 2,  20, NOW(), NOW()),   -- 20 − 0
                                                                                                      (8, 14, 15, NOW(), NOW()),   -- 15 − 0
                                                                                                      -- المندوب ٩ (مازن) — الفاتورة ١٢ مسودة فلا تُخصم
                                                                                                      (9, 12, 6,  NOW(), NOW()),   -- 8 − 2
                                                                                                      (9, 11, 10, NOW(), NOW()),   -- 10 − 0
                                                                                                      (9, 7,  12, NOW(), NOW()),   -- 12 − 0
                                                                                                      (9, 13, 20, NOW(), NOW());   -- 25 − 5


-- ============================================================================
-- 9. كشوف الإرجاع / RETURN SHEETS  (8 rows — 4 DRAFT, 4 COMPLETED)
-- ============================================================================
INSERT INTO return_sheets (representative_id, return_date, status, created_at, updated_at) VALUES
                                                                                               (4, CURRENT_DATE,     'DRAFT',     NOW(), NOW()),  -- 1
                                                                                               (5, CURRENT_DATE,     'DRAFT',     NOW(), NOW()),  -- 2
                                                                                               (6, CURRENT_DATE,     'DRAFT',     NOW(), NOW()),  -- 3
                                                                                               (7, CURRENT_DATE,     'DRAFT',     NOW(), NOW()),  -- 4
                                                                                               (4, CURRENT_DATE - 1, 'COMPLETED', NOW(), NOW()),  -- 5
                                                                                               (5, CURRENT_DATE - 1, 'COMPLETED', NOW(), NOW()),  -- 6
                                                                                               (8, CURRENT_DATE - 1, 'COMPLETED', NOW(), NOW()),  -- 7
                                                                                               (9, CURRENT_DATE - 1, 'COMPLETED', NOW(), NOW());  -- 8


-- ============================================================================
-- 10. بنود كشوف الإرجاع / RETURN SHEET LINES
-- ============================================================================
INSERT INTO return_sheet_lines (return_sheet_id, product_id, quantity, created_at, updated_at) VALUES
                                                                                                   (1,1,8,NOW(),NOW()),(1,3,5,NOW(),NOW()),
                                                                                                   (2,6,4,NOW(),NOW()),(2,14,7,NOW(),NOW()),
                                                                                                   (3,4,12,NOW(),NOW()),
                                                                                                   (4,10,6,NOW(),NOW()),(4,13,10,NOW(),NOW()),
                                                                                                   (5,2,5,NOW(),NOW()),(5,10,3,NOW(),NOW()),
                                                                                                   (6,1,4,NOW(),NOW()),(6,9,6,NOW(),NOW()),
                                                                                                   (7,1,3,NOW(),NOW()),(7,14,5,NOW(),NOW()),
                                                                                                   (8,12,2,NOW(),NOW()),(8,13,8,NOW(),NOW());


-- ============================================================================
-- 11. خطوط السير / ROUTES  (10 rows — id 1–10)
-- ============================================================================
-- كل خط سير ضمن منطقة واحدة، وكل زبون في الخط ينتمي لمنطقة الخط.
-- توزيع المندوبين على المناطق:
--   المندوب ٤ ← المزة(1) | ٥ ← المالكي(2) | ٦ ← باب توما(3)
--   المندوب ٧ ← الميدان(4) | ٨ ← القابون(5) | ٩ ← ركن الدين(6)
INSERT INTO routes (representative_id, territory_id, name, route_date, status, is_optimized, created_at, updated_at) VALUES
                                                                                                                         (4, 1, ' - الإثنين',     CURRENT_DATE - 1, 'COMPLETED', TRUE,  NOW(), NOW()),  -- 1
                                                                                                                         (5, 2, 'المالكي - الإثنين',   CURRENT_DATE - 1, 'COMPLETED', TRUE,  NOW(), NOW()),  -- 2
                                                                                                                         (6, 3, 'باب توما - الإثنين',  CURRENT_DATE - 1, 'COMPLETED', FALSE, NOW(), NOW()),  -- 3
                                                                                                                         (7, 4, 'الميدان - الإثنين',   CURRENT_DATE - 1, 'COMPLETED', TRUE,  NOW(), NOW()),  -- 4
                                                                                                                         (8, 5, 'القابون - الإثنين',   CURRENT_DATE - 1, 'COMPLETED', FALSE, NOW(), NOW()),  -- 5
                                                                                                                         (9, 6, 'ركن الدين - الإثنين', CURRENT_DATE - 1, 'COMPLETED', TRUE,  NOW(), NOW()),  -- 6
                                                                                                                         (4, 1, ' - اليوم',        CURRENT_DATE,     'ACTIVE',    TRUE,  NOW(), NOW()),  -- 7
                                                                                                                         (5, 2, 'المالكي - اليوم',      CURRENT_DATE,     'ACTIVE',    TRUE,  NOW(), NOW()),  -- 8
                                                                                                                         (6, 3, 'باب توما - اليوم',     CURRENT_DATE,     'ACTIVE',    FALSE, NOW(), NOW()),  -- 9
                                                                                                                         (7, 4, 'الميدان - غداً',       CURRENT_DATE + 1, 'PLANNED',   FALSE, NOW(), NOW());  -- 10


-- ============================================================================
-- 12. محطات خطوط السير / ROUTE CUSTOMER ASSIGNMENTS  (stops — id 1–23)
-- ============================================================================
INSERT INTO route_customer_assignments (route_id, customer_id, sequence_number, created_at, updated_at) VALUES
                                                                                                            -- خط ١ (المزة، الزبائن 1،2،3)
                                                                                                            (1,1,1,NOW(),NOW()),(1,2,2,NOW(),NOW()),(1,3,3,NOW(),NOW()),
                                                                                                            -- خط ٢ (المالكي، الزبائن 4،5)
                                                                                                            (2,4,1,NOW(),NOW()),(2,5,2,NOW(),NOW()),
                                                                                                            -- خط ٣ (باب توما، الزبائن 6،7)
                                                                                                            (3,6,1,NOW(),NOW()),(3,7,2,NOW(),NOW()),
                                                                                                            -- خط ٤ (الميدان، الزبائن 8،9،10)
                                                                                                            (4,8,1,NOW(),NOW()),(4,9,2,NOW(),NOW()),(4,10,3,NOW(),NOW()),
                                                                                                            -- خط ٥ (القابون، الزبائن 11،12)
                                                                                                            (5,11,1,NOW(),NOW()),(5,12,2,NOW(),NOW()),
                                                                                                            -- خط ٦ (ركن الدين، الزبون 13)
                                                                                                            (6,13,1,NOW(),NOW()),
                                                                                                            -- خط ٧ (المزة، اليوم)
                                                                                                            (7,1,1,NOW(),NOW()),(7,2,2,NOW(),NOW()),(7,3,3,NOW(),NOW()),
                                                                                                            -- خط ٨ (المالكي، اليوم)
                                                                                                            (8,4,1,NOW(),NOW()),(8,5,2,NOW(),NOW()),
                                                                                                            -- خط ٩ (باب توما، اليوم)
                                                                                                            (9,6,1,NOW(),NOW()),(9,7,2,NOW(),NOW()),
                                                                                                            -- خط ١٠ (الميدان، غداً)
                                                                                                            (10,8,1,NOW(),NOW()),(10,9,2,NOW(),NOW());


-- ============================================================================
-- 13. الزيارات / VISITS  (16 rows — id 1–16)
-- ============================================================================
-- COMPLETED: كل الأوقات مضبوطة · IN_PROGRESS: دخول فقط · MISSED: كلها NULL
INSERT INTO visits (customer_id, representative_id, route_id, check_in_time, check_in_location, check_out_time, check_out_location, status, created_at, updated_at) VALUES
                                                                                                                                                                        -- خط ١ (المندوب ٤، أمس) — 3 محطات، كلها مُنجزة
                                                                                                                                                                        (1, 4, 1, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 5, 0, '+03'), '33.5010,36.2560',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 40, 0, '+03'), '33.5010,36.2560', 'COMPLETED', NOW(), NOW()),  -- 1
                                                                                                                                                                        (2, 4, 1, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 15, 0, '+03'), '33.5045,36.2480',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 45, 0, '+03'), '33.5045,36.2480', 'COMPLETED', NOW(), NOW()),  -- 2
                                                                                                                                                                        (3, 4, 1, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 20, 0, '+03'), '33.4980,36.2610',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 50, 0, '+03'), '33.4980,36.2610', 'COMPLETED', NOW(), NOW()),  -- 3

                                                                                                                                                                        -- خط ٢ (المندوب ٥، أمس) — زيارتان مُنجزتان
                                                                                                                                                                        (4, 5, 2, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 10, 0, '+03'), '33.5150,36.2830',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 55, 0, '+03'), '33.5150,36.2830', 'COMPLETED', NOW(), NOW()),  -- 4
                                                                                                                                                                        (5, 5, 2, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 30, 0, '+03'), '33.5185,36.2890',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 0, 0, '+03'), '33.5185,36.2890', 'COMPLETED', NOW(), NOW()),  -- 5

                                                                                                                                                                        -- خط ٣ (المندوب ٦، أمس) — زيارة مُنجزة + زيارة فائتة
                                                                                                                                                                        (6, 6, 3, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 8, 50, 0, '+03'), '33.5130,36.3170',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 35, 0, '+03'), '33.5130,36.3170', 'COMPLETED', NOW(), NOW()),  -- 6
                                                                                                                                                                        (7, 6, 3, NULL, NULL, NULL, NULL, 'MISSED', NOW(), NOW()),                                                                                                                                                             -- 7 (المحل مغلق)

                                                                                                                                                                        -- خط ٤ (المندوب ٧، أمس) — ثلاث محطات، كلها مُنجزة
                                                                                                                                                                        (8,  7, 4, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 20, 0, '+03'), '33.4870,36.2960',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 50, 0, '+03'), '33.4870,36.2960', 'COMPLETED', NOW(), NOW()), -- 8
                                                                                                                                                                        (9,  7, 4, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 40, 0, '+03'), '33.4840,36.2990',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 25, 0, '+03'), '33.4840,36.2990', 'COMPLETED', NOW(), NOW()), -- 9
                                                                                                                                                                        (10, 7, 4, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 55, 0, '+03'), '33.4900,36.2940',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 12, 25, 0, '+03'), '33.4900,36.2940', 'COMPLETED', NOW(), NOW()), -- 10

                                                                                                                                                                        -- خط ٥ (المندوب ٨، أمس) — زيارة مُنجزة + زيارة فائتة
                                                                                                                                                                        (11, 8, 5, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 0, 0, '+03'), '33.5420,36.3320',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 30, 0, '+03'), '33.5420,36.3320', 'COMPLETED', NOW(), NOW()), -- 11
                                                                                                                                                                        (12, 8, 5, NULL, NULL, NULL, NULL, 'MISSED', NOW(), NOW()),                                                                                                                                                            -- 12 (صاحب المحل غائب)

                                                                                                                                                                        -- خط ٦ (المندوب ٩، أمس) — زيارة واحدة مُنجزة
                                                                                                                                                                        (13, 9, 6, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 15, 0, '+03'), '33.5330,36.2960',
                                                                                                                                                                         make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 55, 0, '+03'), '33.5330,36.2960', 'COMPLETED', NOW(), NOW()), -- 13

                                                                                                                                                                        -- خط ٤ الإضافي: المندوب ٧ زار الزبون ١٤؟ لا — نستخدم زيارات متوافقة مع الفواتير:
                                                                                                                                                                        -- زيارة المندوب ٩ للزبون ١٤ ضمن يوم أمس (لفاتورة مرفوضة id=10) عبر خط ٦؟ الزبون ١٤ ليس على خط ٦.
                                                                                                                                                                        -- بدلاً من ذلك: المندوب ٦ زيارة إضافية على خط ٣ ليست ممكنة. نُبقي الفاتورة ١٠ بلا زيارة.

                                                                                                                                                                        -- خط ٧ (المندوب ٤، اليوم، قيد التنفيذ) — داخل الزبون ١ الآن
                                                                                                                                                                        (1, 4, 7, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE)::int, EXTRACT(MONTH FROM CURRENT_DATE)::int, EXTRACT(DAY FROM CURRENT_DATE)::int, 9, 5, 0, '+03'), '33.5010,36.2560',
                                                                                                                                                                         NULL, NULL, 'IN_PROGRESS', NOW(), NOW()),                                                                                                                                                                    -- 14

                                                                                                                                                                        -- خط ٥: المندوب ٨ زيارة ثانية للزبون ١١ غير ممكنة (UNIQUE). نضيف زيارتين متوافقتين مع فواتير SENT:
                                                                                                                                                                        -- المندوب ٧ زار الزبون ١٠ (خط ٤) — أُنجزت أعلاه (id 10). المندوب ٨ زار الزبون ١١ (id 11).
                                                                                                                                                                        -- زيارة للزبون ١٤ (مشروع دمر) والزبون ١٥ غير مغطاة بخطوط أمس، لذا فاتورتا ٧ و ٨ ترتبطان بزيارات مُنجزة:
                                                                                                                                                                        -- فاتورة ٧ (المندوب ٧، الزبون ١٠) ← زيارة id 10 ✓ ; فاتورة ٨ (المندوب ٨، الزبون ١١) ← زيارة id 11 ✓
                                                                                                                                                                        -- فاتورة ٦ (المندوب ٥، الزبون ٥) ← زيارة id 5 ✓

                                                                                                                                                                        -- زيارة إضافية للمندوب ٩ على الزبون ١٣ سبق تسجيلها (id 13). للفاتورة المرفوضة id=10 (المندوب ٩، الزبون ١٤)
                                                                                                                                                                        -- لا تتوفر زيارة (بيع خارج المسار) → visit_id = NULL، وهذا مسموح (D13).

                                                                                                                                                                        -- خط ٦ ثانٍ: المندوب ٦ زار الزبون ٦ (خط ٣) وأُنجز (id 6). الفاتورة ٤ (المندوب ٦، الزبون ٧) مرفوضة؟ لا،
                                                                                                                                                                        -- الفاتورة ٤ للزبون ٧ والمندوب ٦ — لكن الزبون ٧ زيارته فائتة (id 7). لذا الفاتورة ٤ visit_id = NULL أيضاً.

                                                                                                                                                                        -- خط ٣ ثالث: لا حاجة. نُبقي 16 زيارة بإضافة زيارة الزبون ١٤ للمندوب ٩ اليوم كـ IN_PROGRESS؟ لا،
                                                                                                                                                                        -- المندوب ٩ ليس لديه خط اليوم. نكتفي بـ 16 صفاً: نضيف زيارة مُنجزة للمندوب ٥ على خط ٨ اليوم:
                                                                                                                                                                        (4, 5, 8, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE)::int, EXTRACT(MONTH FROM CURRENT_DATE)::int, EXTRACT(DAY FROM CURRENT_DATE)::int, 9, 10, 0, '+03'), '33.5150,36.2830',
                                                                                                                                                                         NULL, NULL, 'IN_PROGRESS', NOW(), NOW()),                                                                                                                                                                    -- 15

                                                                                                                                                                        -- محطة أخيرة: المندوب ٦ اليوم على خط ٩ (باب توما) قيد التنفيذ عند الزبون ٦
                                                                                                                                                                        (6, 6, 9, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE)::int, EXTRACT(MONTH FROM CURRENT_DATE)::int, EXTRACT(DAY FROM CURRENT_DATE)::int, 8, 50, 0, '+03'), '33.5130,36.3170',
                                                                                                                                                                         NULL, NULL, 'IN_PROGRESS', NOW(), NOW());                                                                                                                                                                    -- 16


-- ============================================================================
-- 14. الفواتير / INVOICES  (12 rows — id 1–12)
-- ============================================================================
-- 5 APPROVED · 3 SENT · 2 REJECTED · 2 DRAFT
-- المراجع: المدير ٢ للمندوبين ٤/٥/٦ ، المدير ٣ للمندوبين ٧/٨/٩.
-- ربط الزيارات: الفاتورة ترتبط بزيارة نفس (المندوب، الزبون) إن وُجدت، وإلا NULL (D13).
--   فاتورة 1→زيارة 1 · 2→2 · 3→4 · 4→NULL(زيارة الزبون٧ فائتة) · 5→؟
--   لتفادي التعقيد: فقط الفواتير التي لها زيارة مُنجزة مطابقة تأخذ visit_id، والبقية NULL.
INSERT INTO invoices (customer_id, representative_id, visit_id, invoice_date, total_amount, status, rejection_reason, reviewed_by_id, client_uuid, created_at, updated_at) VALUES
                                                                                                                                                                               -- APPROVED
                                                                                                                                                                               (1,  4, 1,    CURRENT_DATE - 1, 24000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- 1  (زيارة 1)
                                                                                                                                                                               (2,  4, 2,    CURRENT_DATE - 1, 28000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- 2  (زيارة 2)
                                                                                                                                                                               (4,  5, 4,    CURRENT_DATE - 1, 52000.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- 3  (زيارة 4)
                                                                                                                                                                               (6,  6, 6,    CURRENT_DATE - 1, 39500.00, 'APPROVED', NULL, 2, NULL, NOW(), NOW()),  -- 4  (زيارة 6)
                                                                                                                                                                               (8,  7, 8,    CURRENT_DATE - 1, 40400.00, 'APPROVED', NULL, 3, NULL, NOW(), NOW()),  -- 5  (زيارة 8)

                                                                                                                                                                               -- SENT
                                                                                                                                                                               (5,  5, 5,    CURRENT_DATE - 1, 36000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- 6  (زيارة 5)
                                                                                                                                                                               (10, 7, 10,   CURRENT_DATE - 1, 33000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- 7  (زيارة 10)
                                                                                                                                                                               (11, 8, 11,   CURRENT_DATE - 1, 10000.00, 'SENT', NULL, NULL, NULL, NOW(), NOW()),   -- 8  (زيارة 11)

                                                                                                                                                                               -- REJECTED — سبب غير فارغ إلزامي (BR-3)، المخزون يبقى مخصوماً
                                                                                                                                                                               (7,  6, NULL, CURRENT_DATE - 1, 30000.00, 'REJECTED',
                                                                                                                                                                                'اعترض الزبون على الكمية المسلَّمة، وأُعيدت البضاعة للمركبة دون كشف إرجاع.', 2, NULL, NOW(), NOW()),  -- 9  (بيع خارج المسار، بلا زيارة D13)
                                                                                                                                                                               (13, 9, 13,   CURRENT_DATE - 1, 48500.00, 'REJECTED',
                                                                                                                                                                                'السعر المطبَّق لا يطابق قائمة الأسعار المعتمدة لهذه الفئة من المنافذ.',      3, NULL, NOW(), NOW()),  -- 10 (زيارة 13)

                                                                                                                                                                               -- DRAFT — لا خصم، بلا مراجعة. الفاتورة ١١ بلا زيارة (بيع خارج المسار، D13)
                                                                                                                                                                               (12, 8, NULL, CURRENT_DATE, 16000.00, 'DRAFT', NULL, NULL, NULL, NOW(), NOW()),      -- 11
                                                                                                                                                                               (14, 9, NULL, CURRENT_DATE, 47500.00, 'DRAFT', NULL, NULL, NULL, NOW(), NOW());      -- 12


-- ============================================================================
-- 15. بنود الفواتير / INVOICE LINE ITEMS  (21 rows)
-- ============================================================================
-- subtotal = quantity*price − discount
INSERT INTO invoice_line_items (invoice_id, product_id, quantity, price, discount, subtotal, created_at, updated_at) VALUES
                                                                                                                         (1,  1,  10, 1500.00,    0.00, 15000.00, NOW(), NOW()),
                                                                                                                         (1,  3,   5, 2000.00, 1000.00,  9000.00, NOW(), NOW()),
                                                                                                                         (2,  9,   8, 3500.00,    0.00, 28000.00, NOW(), NOW()),
                                                                                                                         (3,  5,   3, 12000.00,   0.00, 36000.00, NOW(), NOW()),
                                                                                                                         (3, 14,   6, 3000.00, 2000.00, 16000.00, NOW(), NOW()),
                                                                                                                         (4,  4,  15, 500.00,     0.00,  7500.00, NOW(), NOW()),
                                                                                                                         (4,  7,   4, 8000.00,    0.00, 32000.00, NOW(), NOW()),
                                                                                                                         (5,  2,  12, 1400.00, 1400.00, 15400.00, NOW(), NOW()),
                                                                                                                         (5, 13,  10, 2500.00,    0.00, 25000.00, NOW(), NOW()),
                                                                                                                         (6,  6,   4, 4500.00,    0.00, 18000.00, NOW(), NOW()),
                                                                                                                         (6, 11,   3, 6000.00,    0.00, 18000.00, NOW(), NOW()),
                                                                                                                         (7, 10,   6, 3000.00,    0.00, 18000.00, NOW(), NOW()),
                                                                                                                         (7, 15,   2, 7500.00,    0.00, 15000.00, NOW(), NOW()),
                                                                                                                         (8,  1,   7, 1500.00,  500.00, 10000.00, NOW(), NOW()),
                                                                                                                         (9,  8,   2, 15000.00,   0.00, 30000.00, NOW(), NOW()),
                                                                                                                         (10, 12,  2, 18000.00,   0.00, 36000.00, NOW(), NOW()),
                                                                                                                         (10, 13,  5, 2500.00,    0.00, 12500.00, NOW(), NOW()),
                                                                                                                         (11,  2,  5, 1400.00,    0.00,  7000.00, NOW(), NOW()),
                                                                                                                         (11, 14,  3, 3000.00,    0.00,  9000.00, NOW(), NOW()),
                                                                                                                         (12, 11,  4, 6000.00,    0.00, 24000.00, NOW(), NOW()),
                                                                                                                         (12,  7,  3, 8000.00,  500.00, 23500.00, NOW(), NOW());


-- ============================================================================
-- 16. إثباتات التسليم / EPOD ARTIFACTS  (16 rows)
-- ============================================================================
-- توقيع + صورة تسليم لكل فاتورة مُرسَلة. الفواتير المسودة (11،12) بلا إثباتات.
-- الفاتورتان 8 و 10 بلا إثبات عمداً (حالة "أُرسلت دون إثبات").
INSERT INTO epod_artifacts (invoice_id, type, url, hash, latitude, longitude, captured_at, created_at, updated_at) VALUES
                                                                                                                       (1, 'SIGNATURE',      'signatures/invoice_1.png', 'a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801', 33.5010, 36.2560, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 38, 0, '+03'), NOW(), NOW()),
                                                                                                                       (1, 'DELIVERY_PHOTO', 'photos/invoice_1.jpg',     'b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2', 33.5010, 36.2560, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 39, 0, '+03'), NOW(), NOW()),
                                                                                                                       (2, 'SIGNATURE',      'signatures/invoice_2.png', 'c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3', 33.5045, 36.2480, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 43, 0, '+03'), NOW(), NOW()),
                                                                                                                       (2, 'DELIVERY_PHOTO', 'photos/invoice_2.jpg',     'd4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4', 33.5045, 36.2480, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 44, 0, '+03'), NOW(), NOW()),
                                                                                                                       (3, 'SIGNATURE',      'signatures/invoice_3.png', 'e5f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5', 33.5150, 36.2830, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 52, 0, '+03'), NOW(), NOW()),
                                                                                                                       (3, 'DELIVERY_PHOTO', 'photos/invoice_3.jpg',     'f60718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6', 33.5150, 36.2830, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 53, 0, '+03'), NOW(), NOW()),
                                                                                                                       (4, 'SIGNATURE',      'signatures/invoice_4.png', '0718293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f7', 33.5130, 36.3170, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 32, 0, '+03'), NOW(), NOW()),
                                                                                                                       (4, 'DELIVERY_PHOTO', 'photos/invoice_4.jpg',     '18293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708', 33.5130, 36.3170, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 33, 0, '+03'), NOW(), NOW()),
                                                                                                                       (5, 'SIGNATURE',      'signatures/invoice_5.png', '8293a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f7081', 33.4870, 36.2960, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 47, 0, '+03'), NOW(), NOW()),
                                                                                                                       (5, 'DELIVERY_PHOTO', 'photos/invoice_5.jpg',     '93a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192', 33.4870, 36.2960, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 9, 48, 0, '+03'), NOW(), NOW()),
                                                                                                                       (6, 'SIGNATURE',      'signatures/invoice_6.png', 'a4b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3', 33.5185, 36.2890, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 58, 0, '+03'), NOW(), NOW()),
                                                                                                                       (6, 'DELIVERY_PHOTO', 'photos/invoice_6.jpg',     'b5c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4', 33.5185, 36.2890, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 59, 0, '+03'), NOW(), NOW()),
                                                                                                                       (7, 'SIGNATURE',      'signatures/invoice_7.png', 'c6d7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5', 33.4900, 36.2940, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 22, 0, '+03'), NOW(), NOW()),
                                                                                                                       (7, 'DELIVERY_PHOTO', 'photos/invoice_7.jpg',     'd7e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6', 33.4900, 36.2940, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 11, 23, 0, '+03'), NOW(), NOW()),
                                                                                                                       (9, 'SIGNATURE',      'signatures/invoice_9.png', 'e8f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6e7', 33.4840, 36.2990, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 33, 0, '+03'), NOW(), NOW()),
                                                                                                                       (9, 'DELIVERY_PHOTO', 'photos/invoice_9.jpg',     'f901a2b3c4d5e6f708192a3b4c5d6e7f801a2b3c4d5e6f708192a3b4c5d6e7f8', 33.4840, 36.2990, make_timestamptz(EXTRACT(YEAR FROM CURRENT_DATE-1)::int, EXTRACT(MONTH FROM CURRENT_DATE-1)::int, EXTRACT(DAY FROM CURRENT_DATE-1)::int, 10, 34, 0, '+03'), NOW(), NOW());


-- ============================================================================
-- انتهى التعبئة / SEED COMPLETE
-- ============================================================================
-- ملخّص:
--   8  مناطق (أحياء دمشق)
--   11 مستخدم (1 مدير نظام، 2 مدير مبيعات، 6 مندوبين، 2 مدير مستودع)
--   15 منتج · 15 زبون · 15 صنف مخزون مستودع
--   21 صنف مخزون مركبات (بعد خصم الفواتير)
--   12 طلب تزويد (6 LOADED، 3 SUBMITTED، 3 ADJUSTED) · 39 بند
--   8  كشوف إرجاع (4 DRAFT، 4 COMPLETED) · 15 بند
--   10 خطوط سير (6 COMPLETED، 3 ACTIVE، 1 PLANNED) · 22 محطة
--   16 زيارة (11 COMPLETED، 2 MISSED، 3 IN_PROGRESS)
--   12 فاتورة (5 APPROVED، 3 SENT، 2 REJECTED، 2 DRAFT) · 21 بند
--   14 إثبات تسليم
--
-- notification و sync: مُتروكتان فارغتين عمداً.
--
-- تسجيل الدخول (كلمة المرور للجميع Owais@1234):
--   +963981491713  → ADMIN (أويس)
--   +963991000002  → SALES_MANAGER (أحمد)  — يراجع الفواتير 1،2،3،4،9
--   +963991000003  → SALES_MANAGER (حسام)  — يراجع الفواتير 5،10
--   +963991000004  → SALES_REP (خالد، المزة)
--   +963991000005  → SALES_REP (شادي، المالكي)
--   +963991000006  → SALES_REP (عمر، باب توما)
--   +963991000007  → SALES_REP (رامي، الميدان)
--   +963991000008  → SALES_REP (فادي، القابون)
--   +963991000009  → SALES_REP (مازن، ركن الدين)
--   +963991000010  → WAREHOUSE_MANAGER (بلال)
--   +963991000011  → WAREHOUSE_MANAGER (نور)
-- ============================================================================
