-- Spring Modulith 2.0.6 added completion_attempts, status, and
-- last_resubmission_date to the main event_publication table.
-- The archive table already had them; the main table did not.

ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status                 VARCHAR(20),
    ADD COLUMN IF NOT EXISTS completion_attempts    INT,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMP(9) WITH TIME ZONE;