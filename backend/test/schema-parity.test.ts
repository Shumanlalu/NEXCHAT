import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const root = path.join(__dirname, "..", "..");
const relaySchema = readFileSync(path.join(root, "backend", "src", "schema.sql"), "utf8");
const migrationsDir = path.join(root, "supabase", "migrations");
const supabaseSchema = readdirSync(migrationsDir)
  .filter((file) => file.endsWith(".sql"))
  .sort()
  .map((file) => readFileSync(path.join(migrationsDir, file), "utf8"))
  .join("\n");

describe("Supabase schema parity", () => {
  it("contains every relay table", () => {
    const tables = [...relaySchema.matchAll(/CREATE TABLE IF NOT EXISTS\s+(\w+)/gi)].map(
      (match) => match[1]!,
    );
    assert.ok(tables.length > 20, "expected to parse the relay schema");
    for (const table of tables) {
      assert.match(
        supabaseSchema,
        new RegExp(`create\\s+table\\s+if\\s+not\\s+exists\\s+public\\.${table}\\b`, "i"),
        `Supabase migrations are missing table ${table}`,
      );
    }
  });

  it("contains columns added after the core migration", () => {
    const required = [
      /alter\s+table\s+public\.users\s+add\s+column\s+if\s+not\s+exists\s+suspended_at/i,
      /alter\s+table\s+public\.users\s+add\s+column\s+if\s+not\s+exists\s+banned_at/i,
      /alter\s+table\s+public\.envelopes\s+add\s+column\s+if\s+not\s+exists\s+priority/i,
      /create\s+table\s+if\s+not\s+exists\s+public\.rate_limit_counters/i,
    ];
    for (const pattern of required) {
      assert.match(supabaseSchema, pattern);
    }
  });

  it("locks down admin and operations tables with RLS", () => {
    for (const table of [
      "admin_users",
      "admin_sessions",
      "app_settings",
      "call_sessions",
      "crash_reports",
      "rate_limit_counters",
    ]) {
      assert.match(
        supabaseSchema,
        new RegExp(`['"]${table}['"]`, "i"),
        `Supabase RLS list is missing ${table}`,
      );
    }
    assert.match(supabaseSchema, /revoke all on table public\.%I from anon, authenticated, service_role/i);
  });
});
