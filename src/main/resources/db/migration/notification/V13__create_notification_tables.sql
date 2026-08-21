-- src/main/resources/db/migration/notification/V13__create_notification_tables.sql

-- ─────────────────────────────────────────────────────────────────────────
-- NOTIFICATION MODULE — two tables, applied atomically.
-- Owned by the `notification` module. This module is a LEAF: it consumes
-- domain events from other modules and stores a per-user notification feed;
-- nothing in the backend reads from it (the mobile/web client reads over REST),
-- so there is no published api/ facade and no @NamedInterface package (D8).
--
-- Flyway versions are GLOBALLY unique across all module subdirectories:
--   V0 modulith · V1,V2 identity · V3 territory · V4 customer ·
--   V5 inventory · V6 vanops · V7,V7_1 routing · V8 visit ·
--   V9 event publication patch · V10 invoicing · V11 tracking ·
--   V12 systemconfig · V13 notification (this file).
--
-- ═════════════════════════════════════════════════════════════════════════
-- TABLE 1: notifications — the stored per-user alert feed.
-- ═════════════════════════════════════════════════════════════════════════
-- Mirrors the ERD NOTIFICATION block, with two deliberate changes:
--   * ERD `CreatedAt` is DROPPED — it is redundant with BaseEntity.created_at,
--     exactly as WAREHOUSE_STOCK_ITEM.LastUpdated and GPS_LOG columns were
--     folded into BaseEntity elsewhere. created_at IS the notification's
--     "when it was raised" timestamp; the client orders the feed by it.
--   * Two columns ADDED beyond the ERD, both justified below:
--       - source_ref   : idempotency key for FR-114 (dedup on event redelivery)
--       - reference_id : deep-link target for FR-105 (open the invoice/route)
--
-- WRITE PATH: rows are written by @ApplicationModuleListener handlers in this
-- module (reacting to invoice/inventory/routing events, D3/D5) and by the
-- ADMIN announcement endpoint (FR-108, D6). There is no offline/sync write
-- path here — FR-113 (sync notification log on reconnect) belongs to the
-- future `sync` module, which will read these rows, not write them.
--
-- READ PATH: the client lists its own feed (GET /api/notifications), reads the
-- unread count (GET /api/notifications/unread-count), and marks rows read
-- (PATCH /{id}/read, PATCH /read-all). read_status is owned solely here (D4).
--
-- CROSS-MODULE FK: user_id → identity.users(id). Declared HERE because the
-- DEPENDENT module owns the FK — same rule as V8 (visits) and V11 (gps_logs).
-- The recipient may be ANY role (a rep gets invoice/route notifications; a
-- warehouse/sales manager gets low-stock; anyone gets announcements), so no
-- role condition is expressible or wanted at the DB layer.
--
-- FR-114 DEDUP (source_ref): events are delivered by Spring Modulith's
-- event-publication log, which RETRIES failed listeners (V9). A retried
-- delivery of the same InvoiceApprovedEvent would otherwise insert a second
-- identical row. source_ref is a DETERMINISTIC natural key built from the
-- triggering fact (e.g. 'invoice:42:APPROVED', 'route-assigned:87:rep:5',
-- 'low-stock:product:12'); the partial UNIQUE index makes the second insert a
-- no-op (the service catches the DataIntegrityViolationException, mirroring the
-- BR-4 dedup pattern in tracking). It is NULLABLE because ANNOUNCEMENTS are not
-- event-driven and are intentionally NOT deduplicated (an admin may post the
-- same text twice on purpose). A partial UNIQUE index (WHERE source_ref IS NOT
-- NULL) lets many announcement rows keep NULL while event rows stay unique.
-- This is Postgres partial-index behaviour:
-- https://www.postgresql.org/docs/current/indexes-partial.html
--
-- FR-105 DEEP-LINK (reference_id): the client opens the underlying record
-- straight from the notification. reference_id holds the target id (the
-- invoice id for INVOICE, the route id for ROUTE, the product id for
-- INVENTORY); combined with `type` the client knows which screen to open.
-- NULLABLE — SYSTEM announcements have no target. Kept SEPARATE from source_ref
-- on purpose: source_ref is a dedup key whose string format may change, so the
-- client must never parse it; reference_id is the stable, typed contract.
--
-- INDEXES: one composite index on (user_id, read_status) serves the two hot
-- reads — "my feed" (WHERE user_id = ?) and "my unread count"
-- (WHERE user_id = ? AND read_status = 'UNREAD'). The feed is ordered by
-- created_at DESC in the query; at realistic volumes a dedicated created_at
-- index is not worth the insert cost on this append-heavy table.
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE notifications (
                               id            BIGSERIAL     PRIMARY KEY,

    -- Recipient. FK to users.id; may be any role.
                               user_id       BIGINT        NOT NULL,

    -- FR-102: one of INVOICE | INVENTORY | ROUTE | SYSTEM. Stored as VARCHAR
    -- (EnumType.STRING on the entity) — readable in the DB, survives enum
    -- reordering, same convention as invoice.status / route.status.
                               type          VARCHAR(20)   NOT NULL,

    -- Short heading and body shown in the feed. Title is bounded; message is
    -- TEXT because a rejection reason (FR-104) can be long and free-form.
                               title         VARCHAR(150)  NOT NULL,
                               message       TEXT          NOT NULL,

    -- FR-111: UNREAD | READ. Owned solely by this module (D4). Defaults to
    -- UNREAD at insert; flipped by the mark-read endpoints.
                               read_status   VARCHAR(10)   NOT NULL,

    -- FR-114 idempotency key. NULL for announcements (not deduplicated).
                               source_ref    VARCHAR(120),

    -- FR-105 deep-link target id (invoice/route/product). NULL for SYSTEM.
                               reference_id  BIGINT,

    -- BaseEntity. created_at is the instant the notification was raised — the
    -- feed's sort key. updated_at moves when read_status flips.
                               created_at    TIMESTAMPTZ   NOT NULL,
                               updated_at    TIMESTAMPTZ   NOT NULL,

                               CONSTRAINT fk_notifications_user
                                   FOREIGN KEY (user_id) REFERENCES users (id),

    -- Defence in depth: the authoritative validation is on the entity/enum;
    -- these guard against a bad row entering through the seeder or manual SQL.
                               CONSTRAINT chk_notifications_type
                                   CHECK (type IN ('INVOICE', 'INVENTORY', 'ROUTE', 'SYSTEM')),
                               CONSTRAINT chk_notifications_read_status
                                   CHECK (read_status IN ('UNREAD', 'READ'))
);

-- FR-114: event-driven rows are unique on their deterministic source_ref;
-- announcement rows (source_ref NULL) are exempt via the partial predicate.
CREATE UNIQUE INDEX uq_notifications_source_ref
    ON notifications (source_ref)
    WHERE source_ref IS NOT NULL;

-- Serves "my feed" and "my unread count".
CREATE INDEX ix_notifications_user_read
    ON notifications (user_id, read_status);

COMMENT ON TABLE  notifications              IS 'Per-user notification feed. Rows are written by event listeners (invoice/inventory/routing) and by the ADMIN announcement endpoint. Read by the mobile/web client over REST; no backend module reads this table. FR-100..111,114.';
COMMENT ON COLUMN notifications.user_id      IS 'FK to users.id — the recipient. Any role. Taken from the triggering event or the announcement target, never from a request body.';
COMMENT ON COLUMN notifications.type         IS 'FR-102 category: INVOICE | INVENTORY | ROUTE | SYSTEM. Drives which screen the client opens together with reference_id.';
COMMENT ON COLUMN notifications.title        IS 'Short heading shown in the feed list.';
COMMENT ON COLUMN notifications.message      IS 'Body text. TEXT because an invoice rejection reason (FR-104) may be long.';
COMMENT ON COLUMN notifications.read_status  IS 'FR-111: UNREAD | READ. Owned solely by the notification module. Flipped by PATCH /{id}/read and PATCH /read-all.';
COMMENT ON COLUMN notifications.source_ref   IS 'FR-114 idempotency key, deterministic per triggering event (e.g. invoice:42:APPROVED). Partial-unique so event redelivery no-ops. NULL for announcements (not deduplicated). Clients must NOT parse this.';
COMMENT ON COLUMN notifications.reference_id IS 'FR-105 deep-link target id (invoice/route/product id) matching `type`. NULL for SYSTEM announcements. Stable typed contract for the client.';
COMMENT ON COLUMN notifications.created_at   IS 'UTC instant the notification was raised. The feed is ordered by this, newest first.';
COMMENT ON COLUMN notifications.updated_at   IS 'UTC instant of the last change — moves when read_status flips UNREAD -> READ.';


-- ═════════════════════════════════════════════════════════════════════════
-- TABLE 2: device_tokens — FCM registration tokens for push delivery (D2).
-- ═════════════════════════════════════════════════════════════════════════
-- NOT in the canonical ERD. Added deliberately to satisfy FR-100/101 (push to
-- mobile, including when the app is backgrounded/closed). Documented here as an
-- explicit ERD extension.
--
-- WHY A TABLE, NOT A COLUMN ON users: an FCM registration token is minted PER
-- DEVICE INSTALL by the OS, INDEPENDENTLY of the app's login/session lifecycle,
-- and it ROTATES (on reinstall, cache clear, device restore, or at Firebase's
-- discretion). A single users.fcm_token column cannot represent a user with
-- more than one device and is overwritten or stranded on every rotation,
-- silently pushing to a dead token. A table stores many tokens per user and
-- lets dead ones be deleted individually. This is Google's documented guidance:
-- https://firebase.google.com/docs/cloud-messaging/manage-tokens
-- (The system enforces single-session-per-user at the AUTH layer, but that
-- governs API access, not which device FCM will deliver to — different
-- lifecycles, so token storage is not simplified by it.)
--
-- WRITE PATH: POST /api/notifications/device-tokens, authenticated. The client
-- calls it after login AND from Firebase's onTokenRefresh callback (tokens can
-- rotate mid-session, so login alone is not enough). Upsert on the token
-- string: a token already present is touched, a new one inserted.
--
-- CLEANUP: a row is deleted on logout (the logged-out device should stop
-- receiving pushes, consistent with single-session intent) and whenever an FCM
-- send reports the token UNREGISTERED / NOT_FOUND (the table self-heals against
-- rotated/dead tokens).
--
-- CROSS-MODULE FK: user_id → identity.users(id). Same dependent-owns-the-FK
-- rule as above.
--
-- UNIQUE(token): a single FCM token identifies a single device install, so it
-- can belong to at most one row. This is the upsert key. A composite
-- (user_id, token) is unnecessary because token is already globally unique.
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE device_tokens (
                               id          BIGSERIAL     PRIMARY KEY,

    -- Owner. FK to users.id; may be any role that runs the mobile app.
                               user_id     BIGINT        NOT NULL,

    -- The FCM registration token. Globally unique per device install; the
    -- upsert key. Length is generous — FCM tokens are ~160+ chars and the
    -- format is not contractually fixed by Google, so TEXT-like sizing is safe.
                               token       VARCHAR(512)  NOT NULL,

    -- BaseEntity. created_at = first registration; updated_at = last time the
    -- client re-registered this same token (a liveness signal).
                               created_at  TIMESTAMPTZ   NOT NULL,
                               updated_at  TIMESTAMPTZ   NOT NULL,

                               CONSTRAINT fk_device_tokens_user
                                   FOREIGN KEY (user_id) REFERENCES users (id),

                               CONSTRAINT uq_device_tokens_token
                                   UNIQUE (token)
);

-- Serves "all tokens for this user" at send time and delete-on-logout.
CREATE INDEX ix_device_tokens_user
    ON device_tokens (user_id);

COMMENT ON TABLE  device_tokens             IS 'FCM registration tokens for push delivery (FR-100/101). One row per device install; many per user. NOT in the ERD — deliberate extension. Written via POST /api/notifications/device-tokens (after login and on Firebase onTokenRefresh); deleted on logout and on FCM UNREGISTERED.';
COMMENT ON COLUMN device_tokens.user_id     IS 'FK to users.id — the device owner. Taken from the JWT principal, never from the request body.';
COMMENT ON COLUMN device_tokens.token       IS 'FCM registration token. Globally unique per device install; the upsert key. Rotates over time — the client re-registers on Firebase onTokenRefresh.';
COMMENT ON COLUMN device_tokens.created_at  IS 'UTC instant this token was first registered.';
COMMENT ON COLUMN device_tokens.updated_at  IS 'UTC instant the client last re-registered this same token — a liveness signal.';
