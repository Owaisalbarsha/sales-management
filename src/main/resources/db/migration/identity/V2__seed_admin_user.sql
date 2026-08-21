-- =============================================================================
-- V2: Seed the initial ADMIN account
-- =============================================================================
--
-- Why this exists:
--   Creating a user requires POST /api/users, which requires @PreAuthorize("hasRole('ADMIN')").
--   But no ADMIN exists yet — chicken-and-egg problem.
--   The first ADMIN is seeded directly via migration. All subsequent accounts
--   are created through the API by this ADMIN.
--
-- Default credentials:
--   Email:    admin@salesmanagement.com
--   Password: Admin@1234
--
-- IMPORTANT:
--   Change this password immediately after first login.
--   This hash is visible in version control — it is not a secret.
--   The seeded password exists only to bootstrap the system.
--
-- BCrypt hash generated with cost factor 12 (matches PasswordEncoder config).
-- =============================================================================

INSERT INTO users (name, email, password_hash, role, status, created_at, updated_at)
VALUES (
           'System Administrator',
           'admin@salesmanagement.com',
           '$2b$12$qj7kAlTRkyGY3meyVxqqyeMPhBcRBDfvw69Zez7KYuoFg0b4YQ.ji',
           'ADMIN',
           'ACTIVE',
           NOW(),
           NOW()
       );