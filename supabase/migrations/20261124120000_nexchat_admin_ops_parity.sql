-- Forward migration: bring Supabase to parity with backend/src/schema.sql.
--
-- The original core migration predated administration, analytics, remote
-- configuration, suspension/ban enforcement and shared rate limiting. Those
-- tables were created by the relay's startup SQL on Neon, so the drift was
-- invisible there. This migration is additive and idempotent; it never drops or
-- rewrites existing data. It is safe to apply before or after the relay has
-- already created the same tables.

set search_path = public;

-- Core-table columns added after the original migration.
alter table public.users add column if not exists suspended_at timestamptz;
alter table public.users add column if not exists suspended_reason text;
alter table public.users add column if not exists banned_at timestamptz;
alter table public.users add column if not exists banned_reason text;
create index if not exists users_created_idx on public.users (created_at);
create index if not exists devices_last_seen_idx on public.devices (last_seen_at);

alter table public.envelopes add column if not exists priority boolean not null default false;
create index if not exists envelopes_priority_idx
    on public.envelopes (recipient_device_id, priority desc, created_at)
    where delivered_at is null;

-- Administration accounts and sessions. Password material is never readable
-- through the Data API.
create table if not exists public.admin_users (
    id uuid primary key,
    username text not null unique,
    display_name text not null,
    password_hash text not null,
    password_salt text not null,
    totp_secret_enc text not null,
    role text not null default 'admin'
        check (role in ('super_admin', 'admin', 'analyst', 'support')),
    active boolean not null default true,
    failed_attempts integer not null default 0,
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz not null default now()
);

create table if not exists public.admin_sessions (
    id uuid primary key,
    admin_id uuid not null references public.admin_users (id) on delete cascade,
    token_hash text not null unique,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    ip text,
    user_agent text
);
create index if not exists admin_sessions_admin_idx
    on public.admin_sessions (admin_id);

-- Only a SHA-256 hash of a one-time reset token is stored.
create table if not exists public.admin_password_resets (
    id uuid primary key,
    admin_id uuid not null references public.admin_users (id) on delete cascade,
    token_hash text not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now(),
    ip text
);
create index if not exists admin_password_resets_admin_idx
    on public.admin_password_resets (admin_id);
create index if not exists admin_password_resets_expiry_idx
    on public.admin_password_resets (expires_at);

create table if not exists public.admin_audit_logs (
    id bigserial primary key,
    admin_id uuid references public.admin_users (id) on delete set null,
    action text not null,
    target_type text,
    target_id text,
    details jsonb,
    ip text,
    user_agent text,
    created_at timestamptz not null default now()
);
create index if not exists admin_audit_created_idx
    on public.admin_audit_logs (created_at);
create index if not exists admin_audit_admin_idx
    on public.admin_audit_logs (admin_id, created_at);

-- Remote configuration and announcements. The first-admin claim row and the
-- remote-config guard row both live in app_settings.
create table if not exists public.app_settings (
    key text primary key,
    value jsonb not null,
    version bigint not null default 1,
    updated_by uuid references public.admin_users (id) on delete set null,
    updated_at timestamptz not null default now()
);

create table if not exists public.app_setting_history (
    id bigserial primary key,
    key text not null,
    previous_value jsonb,
    new_value jsonb not null,
    version bigint not null,
    admin_id uuid references public.admin_users (id) on delete set null,
    created_at timestamptz not null default now()
);
create index if not exists app_setting_history_key_idx
    on public.app_setting_history (key, created_at);

create table if not exists public.announcements (
    id uuid primary key,
    title text not null,
    body text not null,
    severity text not null default 'info'
        check (severity in ('info', 'warning', 'critical')),
    active boolean not null default true,
    starts_at timestamptz not null default now(),
    ends_at timestamptz,
    created_by uuid references public.admin_users (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists announcements_active_idx
    on public.announcements (active, starts_at);

-- Analytics and operations.
create table if not exists public.user_activity_daily (
    user_id uuid not null references public.users (id) on delete cascade,
    day date not null,
    last_seen_at timestamptz not null default now(),
    app_version text,
    platform text,
    primary key (user_id, day)
);
create index if not exists user_activity_day_idx
    on public.user_activity_daily (day);

create table if not exists public.app_events (
    id bigserial primary key,
    user_id uuid references public.users (id) on delete set null,
    device_id uuid references public.devices (id) on delete set null,
    event_type text not null,
    app_version text,
    platform text,
    metadata jsonb,
    created_at timestamptz not null default now()
);
create index if not exists app_events_type_idx
    on public.app_events (event_type, created_at);
create index if not exists app_events_user_idx
    on public.app_events (user_id, created_at);

create table if not exists public.call_sessions (
    call_id uuid primary key,
    caller_user_id uuid references public.users (id) on delete set null,
    callee_user_id uuid references public.users (id) on delete set null,
    peer_nex_id text,
    mode text not null default 'audio' check (mode in ('audio', 'video')),
    status text not null default 'ringing'
        check (status in ('ringing', 'connected', 'ended', 'declined', 'missed', 'failed')),
    failure_reason text,
    started_at timestamptz not null default now(),
    connected_at timestamptz,
    ended_at timestamptz,
    duration_seconds integer,
    app_version text,
    platform text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists call_sessions_started_idx
    on public.call_sessions (started_at);
create index if not exists call_sessions_status_idx
    on public.call_sessions (status, started_at);
create index if not exists call_sessions_caller_idx
    on public.call_sessions (caller_user_id, started_at);
create index if not exists call_sessions_callee_idx
    on public.call_sessions (callee_user_id, started_at);

create table if not exists public.crash_reports (
    id uuid primary key,
    user_id uuid references public.users (id) on delete set null,
    device_id uuid references public.devices (id) on delete set null,
    app_version text,
    platform text,
    message text not null,
    stack text,
    context jsonb,
    created_at timestamptz not null default now()
);
create index if not exists crash_reports_created_idx
    on public.crash_reports (created_at);
create index if not exists crash_reports_version_idx
    on public.crash_reports (app_version, created_at);

create table if not exists public.service_heartbeats (
    id bigserial primary key,
    source text not null,
    db_latency_ms integer,
    created_at timestamptz not null default now()
);
create index if not exists service_heartbeats_created_idx
    on public.service_heartbeats (created_at);

create table if not exists public.message_stats_daily (
    day date primary key,
    sent bigint not null default 0,
    delivered bigint not null default 0,
    skipped bigint not null default 0
);

-- Counters are shared across serverless instances so the configured limit is
-- not multiplied by the number of cold-started functions.
create table if not exists public.rate_limit_counters (
    key text primary key,
    current integer not null,
    expires_at bigint not null
);

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
-- The relay connects as the table owner, so ENABLE (not FORCE) keeps it working
-- while every Data API role is denied. No client needs direct table access.
do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'admin_users',
        'admin_sessions',
        'admin_password_resets',
        'admin_audit_logs',
        'app_settings',
        'app_setting_history',
        'announcements',
        'user_activity_daily',
        'app_events',
        'call_sessions',
        'crash_reports',
        'service_heartbeats',
        'message_stats_daily',
        'rate_limit_counters'
    ]
    loop
        execute format('alter table public.%I enable row level security', table_name);
        execute format(
            'revoke all on table public.%I from anon, authenticated, service_role',
            table_name
        );
    end loop;
end
$$;

comment on table public.rate_limit_counters is
    'Shared request counters. Not exposed through the Supabase Data API.';
comment on table public.app_settings is
    'Remote configuration and first-admin claim rows; relay owner access only.';
