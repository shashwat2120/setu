-- The first migration that belongs to a service and not to the monolith: it changes only the notification schema, and is applied by the Notification service to its own
-- database (and by the core to the shared database's copy, so the two stay in step; see infra/db/README.md, "A service's own migrations").
--
-- The unread count (what the bell shows) filters a person's notifications on read_at IS NULL. The existing index (user_id, created_at DESC) orders the list but is
-- read through for every row the person ever received; this one holds only the unread ones, so the count touches only those.
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notification.notifications (user_id) WHERE read_at IS NULL;
