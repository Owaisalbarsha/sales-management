-- =============================================================================
-- V3: Replace email with phone_number as the login identifier
-- =============================================================================
--
-- The system now uses phone numbers instead of email addresses for
-- authentication. This matches the field reality where sales reps
-- may not have corporate email addresses but always carry a phone.
--

ALTER TABLE users RENAME COLUMN email TO phone_number;

ALTER INDEX idx_users_email RENAME TO idx_users_phone_number;
ALTER TABLE users RENAME CONSTRAINT users_email_key TO uq_users_phone_number;

-- Update the seeded admin account
UPDATE users
SET phone_number = '+962780000000'
WHERE phone_number = 'admin@salesmanagement.com';