CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    nex_id TEXT NOT NULL UNIQUE,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    avatar TEXT,
    share_presence BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android',
    registration_id INTEGER NOT NULL,
    signal_device_id INTEGER NOT NULL,
    identity_key TEXT NOT NULL,
    signed_pre_key_id INTEGER NOT NULL,
    signed_pre_key_public TEXT NOT NULL,
    signed_pre_key_signature TEXT NOT NULL,
    kyber_pre_key_id INTEGER,
    kyber_pre_key_public TEXT,
    kyber_pre_key_signature TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS devices_user_active_idx ON devices(user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS devices_user_signal_active_idx
    ON devices(user_id, signal_device_id) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS one_time_prekeys (
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    used_at TIMESTAMPTZ,
    PRIMARY KEY (device_id, key_id)
);

CREATE TABLE IF NOT EXISTS kyber_prekeys (
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    used_at TIMESTAMPTZ,
    PRIMARY KEY (device_id, key_id)
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS envelopes (
    id UUID PRIMARY KEY,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    group_id UUID,
    client_id UUID NOT NULL,
    cipher_type INTEGER NOT NULL,
    ciphertext TEXT NOT NULL,
    ephemeral BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    UNIQUE (sender_device_id, recipient_device_id, client_id)
);
CREATE INDEX IF NOT EXISTS envelopes_recipient_idx
    ON envelopes(recipient_device_id, created_at) WHERE delivered_at IS NULL;
CREATE INDEX IF NOT EXISTS envelopes_expiry_idx ON envelopes(expires_at);
-- Call signaling (SDP/ICE/hangup) shares this durable queue with messages.
-- Marking it priority keeps a short-lived offer from being starved behind old
-- envelopes that a device cannot decrypt or has not acknowledged yet.
ALTER TABLE envelopes ADD COLUMN IF NOT EXISTS priority BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS envelopes_priority_idx
    ON envelopes(recipient_device_id, priority DESC, created_at) WHERE delivered_at IS NULL;

-- Durable delivery receipts. The sender may be offline when the recipient
-- acknowledges an envelope, so the receipt outlives the ephemeral WebSocket
-- event. It is keyed by sender device + recipient device + client message ID so
-- retries cannot create duplicates.
CREATE TABLE IF NOT EXISTS delivery_receipts (
    id UUID PRIMARY KEY,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    envelope_id UUID NOT NULL,
    client_id UUID NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sender_device_id, recipient_device_id, client_id)
);
CREATE INDEX IF NOT EXISTS delivery_receipts_sender_idx
    ON delivery_receipts(sender_device_id, delivered_at);
CREATE INDEX IF NOT EXISTS delivery_receipts_age_idx ON delivery_receipts(delivered_at);

CREATE TABLE IF NOT EXISTS groups (
    id UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX IF NOT EXISTS group_members_user_idx ON group_members(user_id) WHERE removed_at IS NULL;

CREATE TABLE IF NOT EXISTS abuse_reports (
    id UUID PRIMARY KEY,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_nex_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Administration, analytics and remote configuration.
-- The Android client and messaging relay never read these tables directly;
-- all access is through authenticated /v1/admin endpoints.
-- ---------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_reason TEXT;
-- A ban is an administrative account-level block that cannot be undone by the
-- normal "restore" action. It is deliberately separate from suspension so an
-- operator can distinguish a temporary pause from an enforcement action.
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_reason TEXT;
CREATE INDEX IF NOT EXISTS users_created_idx ON users(created_at);
CREATE INDEX IF NOT EXISTS devices_last_seen_idx ON devices(last_seen_at);

CREATE TABLE IF NOT EXISTS admin_users (
    id UUID PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    totp_secret_enc TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'admin'
        CHECK (role IN ('super_admin', 'admin', 'analyst', 'support')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin_sessions (
    id UUID PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    ip TEXT,
    user_agent TEXT
);
CREATE INDEX IF NOT EXISTS admin_sessions_admin_idx ON admin_sessions(admin_id);

-- One-time password reset links. Only a SHA-256 hash of the raw token is stored.
CREATE TABLE IF NOT EXISTS admin_password_resets (
    id UUID PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip TEXT
);
CREATE INDEX IF NOT EXISTS admin_password_resets_admin_idx ON admin_password_resets(admin_id);
CREATE INDEX IF NOT EXISTS admin_password_resets_expiry_idx ON admin_password_resets(expires_at);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    admin_id UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    details JSONB,
    ip TEXT,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS admin_audit_created_idx ON admin_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS admin_audit_admin_idx ON admin_audit_logs(admin_id, created_at);

CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_setting_history (
    id BIGSERIAL PRIMARY KEY,
    key TEXT NOT NULL,
    previous_value JSONB,
    new_value JSONB NOT NULL,
    version BIGINT NOT NULL,
    admin_id UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS app_setting_history_key_idx ON app_setting_history(key, created_at);

CREATE TABLE IF NOT EXISTS announcements (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'info'
        CHECK (severity IN ('info', 'warning', 'critical')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at TIMESTAMPTZ,
    created_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS announcements_active_idx ON announcements(active, starts_at);

CREATE TABLE IF NOT EXISTS user_activity_daily (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    app_version TEXT,
    platform TEXT,
    PRIMARY KEY (user_id, day)
);
CREATE INDEX IF NOT EXISTS user_activity_day_idx ON user_activity_daily(day);

CREATE TABLE IF NOT EXISTS app_events (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    event_type TEXT NOT NULL,
    app_version TEXT,
    platform TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS app_events_type_idx ON app_events(event_type, created_at);
CREATE INDEX IF NOT EXISTS app_events_user_idx ON app_events(user_id, created_at);

CREATE TABLE IF NOT EXISTS call_sessions (
    call_id UUID PRIMARY KEY,
    caller_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    callee_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    peer_nex_id TEXT,
    mode TEXT NOT NULL DEFAULT 'audio' CHECK (mode IN ('audio', 'video')),
    status TEXT NOT NULL DEFAULT 'ringing'
        CHECK (status IN ('ringing', 'connected', 'ended', 'declined', 'missed', 'failed')),
    failure_reason TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    connected_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    duration_seconds INTEGER,
    app_version TEXT,
    platform TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS call_sessions_started_idx ON call_sessions(started_at);
CREATE INDEX IF NOT EXISTS call_sessions_status_idx ON call_sessions(status, started_at);
CREATE INDEX IF NOT EXISTS call_sessions_caller_idx ON call_sessions(caller_user_id, started_at);
CREATE INDEX IF NOT EXISTS call_sessions_callee_idx ON call_sessions(callee_user_id, started_at);

CREATE TABLE IF NOT EXISTS crash_reports (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    app_version TEXT,
    platform TEXT,
    message TEXT NOT NULL,
    stack TEXT,
    context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS crash_reports_created_idx ON crash_reports(created_at);
CREATE INDEX IF NOT EXISTS crash_reports_version_idx ON crash_reports(app_version, created_at);

CREATE TABLE IF NOT EXISTS service_heartbeats (
    id BIGSERIAL PRIMARY KEY,
    source TEXT NOT NULL,
    db_latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS service_heartbeats_created_idx ON service_heartbeats(created_at);

CREATE TABLE IF NOT EXISTS message_stats_daily (
    day DATE PRIMARY KEY,
    sent BIGINT NOT NULL DEFAULT 0,
    delivered BIGINT NOT NULL DEFAULT 0,
    skipped BIGINT NOT NULL DEFAULT 0
);

-- Shared rate-limit counters. A process-local store multiplies the configured
-- limit by the number of serverless instances, so counters live in Postgres.
CREATE TABLE IF NOT EXISTS rate_limit_counters (
    key TEXT PRIMARY KEY,
    current INTEGER NOT NULL,
    expires_at BIGINT NOT NULL
);

