CREATE TABLE notification.notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    user_id         UUID NOT NULL REFERENCES identity.users (id),
    type            TEXT NOT NULL,
    payload_json    JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ NULL
);
CREATE INDEX idx_notifications_user_created ON notification.notifications (user_id, created_at DESC);

CREATE TABLE notification.notification_preferences (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES identity.users (id),
    channel     TEXT NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uq_notification_preferences_user_channel UNIQUE (user_id, channel)
);

CREATE TABLE notification.delivery_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notification.notifications (id),
    channel         TEXT NOT NULL,
    status          TEXT NOT NULL,
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    error           TEXT NULL
);
CREATE INDEX idx_delivery_attempts_notification ON notification.delivery_attempts (notification_id);
