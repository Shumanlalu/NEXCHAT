-- NEXCHAT core schema for Supabase Postgres.
--
-- This migration creates the exact relational model used by the NEXCHAT relay
-- (backend/src/schema.sql) plus the security posture required when the database
-- is exposed through Supabase. It is additive and idempotent so it can be
-- applied to an existing database that the relay already created.
--
-- Security model
-- --------------
-- The Android client does NOT talk to the Supabase Data API directly. It talks
-- to the NEXCHAT relay, which authenticates a custom username/password session
-- and connects with the database owner role. Row Level Security is enabled on
-- every table and all privileges are revoked from `anon` and `authenticated`,
-- so a leaked anon key cannot read messages, password hashes, or prekeys.
--
-- The policies below are intentionally scoped to rows linked to a Supabase Auth
-- user (`users.auth_user_id`). They stay inert until an account is linked, which
-- keeps a future Supabase-Auth migration possible without opening the Data API
-- today.

set search_path = public;

create extension if not exists pg_trgm with schema extensions;

create table if not exists public.users (
    id uuid primary key,
    nex_id text not null unique,
    username text not null unique,
    display_name text not null,
    password_hash text not null,
    password_salt text not null,
    avatar text,
    share_presence boolean not null default true,
    created_at timestamptz not null default now(),
    deleted_at timestamptz,
    -- Optional link to a Supabase Auth identity. Null for accounts created by
    -- the current custom username/password layer.
    auth_user_id uuid unique references auth.users (id) on delete set null
);

-- The relay may already have created `users` with its own startup migration.
-- Add the optional Auth link in place when upgrading an existing database.
alter table public.users add column if not exists auth_user_id uuid;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'users_auth_user_id_fkey'
    ) then
        alter table public.users
            add constraint users_auth_user_id_fkey
            foreign key (auth_user_id) references auth.users (id) on delete set null;
    end if;
end
$$;

-- Allow the relay to resolve a Supabase Auth identity after an optional link
-- without a sequential scan.
create unique index if not exists users_auth_user_id_key
    on public.users (auth_user_id) where auth_user_id is not null;
create index if not exists users_username_trgm_idx
    on public.users using gin (username extensions.gin_trgm_ops);
create index if not exists users_display_name_trgm_idx
    on public.users using gin (display_name extensions.gin_trgm_ops);

create table if not exists public.devices (
    id uuid primary key,
    user_id uuid not null references public.users (id) on delete cascade,
    name text not null,
    platform text not null default 'android',
    registration_id integer not null,
    signal_device_id integer not null,
    identity_key text not null,
    signed_pre_key_id integer not null,
    signed_pre_key_public text not null,
    signed_pre_key_signature text not null,
    kyber_pre_key_id integer,
    kyber_pre_key_public text,
    kyber_pre_key_signature text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    revoked_at timestamptz,
    constraint devices_signal_device_id_range
        check (signal_device_id between 1 and 127)
);

-- Guard the 1..127 libsignal range on existing databases without failing the
-- migration when legacy rows still need the relay's repair pass.
do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'devices_signal_device_id_range'
    ) and not exists (
        select 1 from public.devices where signal_device_id not between 1 and 127
    ) then
        alter table public.devices
            add constraint devices_signal_device_id_range
            check (signal_device_id between 1 and 127);
    end if;
end
$$;

create index if not exists devices_user_active_idx
    on public.devices (user_id) where revoked_at is null;
create unique index if not exists devices_user_signal_active_idx
    on public.devices (user_id, signal_device_id) where revoked_at is null;

create table if not exists public.one_time_prekeys (
    device_id uuid not null references public.devices (id) on delete cascade,
    key_id integer not null,
    public_key text not null,
    used_at timestamptz,
    primary key (device_id, key_id)
);

create table if not exists public.kyber_prekeys (
    device_id uuid not null references public.devices (id) on delete cascade,
    key_id integer not null,
    public_key text not null,
    signature text not null,
    used_at timestamptz,
    primary key (device_id, key_id)
);

create table if not exists public.refresh_tokens (
    id uuid primary key,
    user_id uuid not null references public.users (id) on delete cascade,
    device_id uuid not null references public.devices (id) on delete cascade,
    token_hash text not null unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create index if not exists refresh_tokens_user_idx on public.refresh_tokens (user_id);

create table if not exists public.envelopes (
    id uuid primary key,
    sender_user_id uuid not null references public.users (id) on delete cascade,
    sender_device_id uuid not null references public.devices (id) on delete cascade,
    recipient_user_id uuid not null references public.users (id) on delete cascade,
    recipient_device_id uuid not null references public.devices (id) on delete cascade,
    group_id uuid,
    client_id uuid not null,
    cipher_type integer not null,
    ciphertext text not null,
    ephemeral boolean not null default false,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    delivered_at timestamptz,
    unique (sender_device_id, recipient_device_id, client_id)
);

create index if not exists envelopes_recipient_idx
    on public.envelopes (recipient_device_id, created_at) where delivered_at is null;
create index if not exists envelopes_expiry_idx on public.envelopes (expires_at);

create table if not exists public.delivery_receipts (
    id uuid primary key,
    sender_user_id uuid not null references public.users (id) on delete cascade,
    sender_device_id uuid not null references public.devices (id) on delete cascade,
    recipient_user_id uuid not null references public.users (id) on delete cascade,
    recipient_device_id uuid not null references public.devices (id) on delete cascade,
    envelope_id uuid not null,
    client_id uuid not null,
    delivered_at timestamptz not null default now(),
    unique (sender_device_id, recipient_device_id, client_id)
);

create index if not exists delivery_receipts_sender_idx
    on public.delivery_receipts (sender_device_id, delivered_at);
create index if not exists delivery_receipts_age_idx
    on public.delivery_receipts (delivered_at);

create table if not exists public.groups (
    id uuid primary key,
    created_by uuid not null references public.users (id) on delete cascade,
    created_at timestamptz not null default now()
);

create table if not exists public.group_members (
    group_id uuid not null references public.groups (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    role text not null default 'member' check (role in ('admin', 'member')),
    joined_at timestamptz not null default now(),
    removed_at timestamptz,
    primary key (group_id, user_id)
);

create index if not exists group_members_user_idx
    on public.group_members (user_id) where removed_at is null;

create table if not exists public.abuse_reports (
    id uuid primary key,
    reporter_user_id uuid not null references public.users (id) on delete cascade,
    target_nex_id text not null,
    reason text not null,
    created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'users',
        'devices',
        'one_time_prekeys',
        'kyber_prekeys',
        'refresh_tokens',
        'envelopes',
        'delivery_receipts',
        'groups',
        'group_members',
        'abuse_reports'
    ]
    loop
        execute format('alter table public.%I enable row level security', table_name);
        -- Do not FORCE RLS: the relay connects as the table owner and must keep
        -- working. ENABLE still protects anon/authenticated Data API requests.
        -- Deny the Supabase Data API by default. The relay connects as the
        -- database owner, which is not subject to these grants.
        execute format('revoke all on table public.%I from anon, authenticated', table_name);
    end loop;
end
$$;

-- The service role is used only by trusted server-side tooling. It bypasses
-- RLS, but it still should not read password material through accidental rows.
revoke all on table public.users from service_role;
grant select (
    id,
    nex_id,
    username,
    display_name,
    avatar,
    share_presence,
    created_at,
    deleted_at,
    auth_user_id
) on public.users to service_role;
grant select on table public.devices to service_role;
grant select on table public.one_time_prekeys to service_role;
grant select on table public.kyber_prekeys to service_role;
grant select on table public.envelopes to service_role;
grant select on table public.delivery_receipts to service_role;
grant select on table public.groups to service_role;
grant select on table public.group_members to service_role;

-- Self-service profile access once an account is linked to Supabase Auth.
create policy users_select_self on public.users
    for select to authenticated
    using (auth_user_id = auth.uid() and deleted_at is null);

create policy users_update_self on public.users
    for update to authenticated
    using (auth_user_id = auth.uid() and deleted_at is null)
    with check (auth_user_id = auth.uid());

grant select (
    id,
    nex_id,
    username,
    display_name,
    avatar,
    share_presence,
    created_at,
    deleted_at,
    auth_user_id
) on public.users to authenticated;
grant update (display_name, avatar, share_presence) on public.users to authenticated;

-- Group members may read the groups they belong to after a Supabase Auth link.
create policy groups_select_member on public.groups
    for select to authenticated
    using (
        exists (
            select 1
            from public.group_members gm
            join public.users u on u.id = gm.user_id
            where gm.group_id = groups.id
              and gm.removed_at is null
              and u.auth_user_id = auth.uid()
        )
    );

-- A direct self-reference inside a policy on the same table recurses under
-- RLS, so this policy exposes only the caller's own membership rows. The group
-- policy above then grants visibility of the groups those rows belong to.
create policy group_members_select_self on public.group_members
    for select to authenticated
    using (
        exists (
            select 1
            from public.users u
            where u.id = group_members.user_id
              and u.auth_user_id = auth.uid()
        )
    );

grant select on public.groups to authenticated;
grant select on public.group_members to authenticated;

-- Messages, prekeys, refresh tokens and delivery receipts are never exposed
-- through PostgREST/Realtime. They remain reachable only through the relay.

comment on table public.envelopes is
    'Encrypted Signal envelopes. Plaintext never reaches the server.';
comment on table public.delivery_receipts is
    'Durable per-device delivery acknowledgements; survives sender disconnects.';
