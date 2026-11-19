# Supabase deployment status

Last updated: 2026-11-19

## Truthful status

| Deliverable | Status |
| --- | --- |
| Device-ID crash fix (`protocol address is invalid`) | Implemented in the client and relay; relay deployed to production and verified |
| v1.0.4 Android release APK | Built and signed in this workspace (see the release notes for the artifact hash) |
| Supabase SQL migrations | Written in `supabase/migrations/`; **not applied to any project** |
| Supabase RLS / privilege lockdown | Written; **not applied** |
| Supabase Storage buckets | Written; **not applied** |
| Supabase project link / deployment | **Blocked**: no project reference or authorized Supabase session in this environment |
| Relay running on Supabase Postgres | **Not started** |
| Supabase Auth / Realtime / Edge Functions | **Not implemented**; see scope in `supabase/README.md` |
| Real-device call and message test | **Not executed**: `adb devices` reports no connected device |

The production relay is currently Fastify on Vercel with a Neon PostgreSQL
database. It is live at `https://nexchat-three.vercel.app` and already
normalizes Signal device IDs. No Supabase deployment is claimed.

## Why the Supabase cutover is blocked

1. No Supabase project reference, project URL, or anon key exists in the
   repository or environment.
2. No Supabase access token or logged-in CLI session exists.
3. The relay's custom username/password authentication, Signal prekey
   distribution, and authenticated WebSocket are working on the current host.
   Replacing them without a linked project and a staging test would risk
   breaking messaging and calls.

## Minimum owner actions

Only you can authorize access to your Supabase account. Choose one path:

### Option A — local CLI login (recommended)

On this computer, run:

```powershell
supabase login
```

A browser window opens at supabase.com. Sign in with your own account and
approve the CLI. The CLI stores a revocable token locally; your password is
never shared with this workspace. Then provide only your **project reference**
(Dashboard → Project Settings → General → Reference ID) and say "proceed".

### Option B — fine-grained access token

Create a revocable personal access token at
<https://supabase.com/dashboard/account/tokens>, set it in the terminal
environment as `SUPABASE_ACCESS_TOKEN`, and provide the project reference. Do
not paste the token into chat or commit it.

### What happens next (executed by the agent)

1. `supabase link --project-ref <ref>`
2. `supabase db push` applies `supabase/migrations/*`
3. Verify RLS, grants, and storage buckets with the queries in
   `supabase/README.md`
4. Point a **staging** relay at the Supabase pooler URL and run the backend
   smoke tests
5. After staging passes, switch production and keep the Neon URL for rollback

The database password is separate from your Supabase account password. If the
CLI prompts for it, enter it yourself in your terminal. Never send it in chat.

## What is already implemented in the repository

- `supabase/config.toml` — local/remote project configuration.
- `supabase/migrations/20261119090000_nexchat_core.sql` — full NEXCHAT schema,
  indexes, check constraints, RLS enablement, Data API lockdown, and optional
  Supabase Auth linkage.
- `supabase/migrations/20261119090100_nexchat_storage.sql` — private
  attachments and public avatar buckets with owner-scoped policies.
- `supabase/seed.sql` — deterministic empty seed.
- `scripts/deploy-supabase.ps1` — link + push + verification helper.
- `backend/.env.supabase.example` — relay connection template (no secrets).

## Security notes

- Passwords remain hashed by the relay; plaintext passwords are never stored.
- The service-role key is not used by the Android app and is not in this
  repository or the APK.
- RLS is enabled on every table; `anon` and `authenticated` lose all access to
  envelopes, prekeys, refresh tokens, receipts, and password columns.
- The optional Supabase Auth linkage is inert until `users.auth_user_id` is set.

## Rollback

Until the production relay is switched, rollback is simply "do not switch".
After a switch, restore the previous Vercel environment variable
`DATABASE_URL` (Neon), redeploy, and verify `/healthz`. No Android release is
required for a database rollback because the client API surface is unchanged.
