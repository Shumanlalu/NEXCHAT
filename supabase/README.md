# NEXCHAT Supabase deployment kit

This directory provisions the database and storage layer that the NEXCHAT relay
uses. It is **not** a second implementation of the relay: the Android client
still talks to the authenticated HTTPS/WebSocket relay, and the relay connects
to this Postgres database with its own database role. That preserves Signal
E2EE, the existing username/password flow, and the working call signaling while
moving ownership of the data to your Supabase project.

## Status

| Item | State |
| --- | --- |
| SQL schema migration | Written, reviewed, **not applied to a Supabase project** |
| RLS + privilege lockdown | Written; Data API access is deny-by-default |
| Storage buckets and policies | Written, **not applied** |
| Supabase project link | **Blocked**: no project reference or access token in this environment |
| Relay cutover to Supabase Postgres | **Not started**; requires a verified staging deployment |
| Supabase Auth / Realtime / Edge Functions | **Not active**; see [Scope](#scope) |

No claim of deployment is made until `supabase migration list` shows these
migrations applied to your project and the relay passes its smoke tests against
the Supabase database.

## Prerequisites

- A Supabase project owned by **your** account. The project owner must remain
  you; no organization transfer is performed.
- The project reference (Dashboard → Project Settings → General → Reference ID).
- An authenticated Supabase CLI session. Run `supabase login` once. The CLI
  opens a browser and stores a revocable access token in your user profile. Do
  **not** send your account password to anyone.

## Deploy

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy-supabase.ps1 -ProjectRef <your-project-ref>
```

The script:

1. Locates the working Supabase CLI binary.
2. Verifies that you are logged in.
3. Links this repository to your project.
4. Pushes `supabase/migrations` to your project.
5. Prints the remote migration list.

`supabase link` may ask for the project's **database password**. That password
is separate from your Supabase account password and is stored by the CLI in your
OS keychain. If you prefer not to enter it, create a fine-grained personal
access token and run the script with `SUPABASE_ACCESS_TOKEN` set; SQL deployment
still uses the CLI's linked database credentials.

## Point the relay at Supabase

In the Vercel project (or any host running `backend/`), set:

```text
DATABASE_URL=postgresql://postgres.<project-ref>:<database-password>@<region>.pooler.supabase.com:6543/postgres?sslmode=require
DB_SSL=true
```

Use the **connection pooler** string from Dashboard → Connect. Keep
`JWT_SECRET`, `ICE_SERVERS_JSON`, and all other existing values unchanged.
Never put `DATABASE_URL`, the database password, or the service-role key in the
Android app or in git.

Before switching production traffic:

1. Run `npm run typecheck && npm test` in `backend/`.
2. Deploy the relay to a staging URL with the Supabase `DATABASE_URL`.
3. Register a temporary account, send a message between two devices, verify
   delivery and read receipts, then delete the account.
4. Only then move the production relay. Keep the previous Neon URL for rollback.

## Security model

- RLS is enabled on every NEXCHAT table.
- `anon` and `authenticated` have **no** privileges on message envelopes,
  prekeys, refresh tokens, delivery receipts, or password material.
- The relay connects as the database owner, so it is unaffected by RLS; the
  Supabase Data API remains closed even if the anon key leaks.
- `users.auth_user_id` and the self-read policies exist only to support a future
  Supabase Auth link. They are inert while it is null.
- Storage buckets are provisioned but not used by the current client.
  Attachments remain encrypted inside Signal envelopes.

## Scope

The current release deliberately does **not** cut over:

- **Auth**: Supabase Auth has no arbitrary username-only flow. Replacing the
  relay's Argon2/scrypt-style password verification with synthetic-email
  Supabase Auth would invalidate existing accounts and is a separate, tested
  migration.
- **Realtime**: the relay's authenticated WebSocket carries encrypted envelopes
  and call signaling. Supabase Realtime would require a client rewrite and a
  different authorization model.
- **Edge Functions**: porting the relay to Deno would duplicate working auth,
  Signal key distribution, and WebSocket logic. This kit keeps one backend
  implementation instead of two divergent ones.
- **Push**: FCM is not configured; no credentials exist in this repository.

These are documented limitations, not completed features.

## Verification queries

After deployment, run in the SQL editor:

```sql
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;

select grantee, table_name, privilege_type
from information_schema.role_table_grants
where table_schema = 'public'
  and grantee in ('anon', 'authenticated')
order by table_name, grantee;

select id, public, file_size_limit
from storage.buckets
where id in ('avatars', 'attachments');
```

Expected: every NEXCHAT table has `rowsecurity = true`; `anon` has no grants;
`authenticated` only has the documented self-service profile/group grants; both
buckets exist.
