import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { newDb } from "pg-mem";
import type { Pool } from "pg";
import { Database } from "../src/db";
import { createPostgresRateLimitStore, type RateLimitStoreLike } from "../src/rate-limit-store";

function testDatabase(): Database {
  const mem = newDb();
  const schema = readFileSync(path.join(__dirname, "..", "src", "schema.sql"), "utf8");
  mem.public.none(schema);
  const memPg = mem.adapters.createPg();
  const db = new Database("postgres://unused");
  (db as unknown as { pool: Pool }).pool = new memPg.Pool();
  return db;
}

function incr(store: RateLimitStoreLike, key: string): Promise<{ current: number; ttl: number }> {
  return new Promise((resolve, reject) => {
    store.incr(key, (error, result) => {
      if (error) reject(error);
      else if (!result) reject(new Error("missing rate-limit result"));
      else resolve(result);
    });
  });
}

describe("postgres rate-limit store", () => {
  it("shares one counter across store children", async () => {
    const db = testDatabase();
    const Store = createPostgresRateLimitStore(db, 60_000);
    const root = new Store();
    const child = root.child({} as never);

    const first = await incr(root, "203.0.113.1");
    const second = await incr(child, "203.0.113.1");

    assert.equal(first.current, 1);
    assert.equal(second.current, 2);
    assert.ok(second.ttl > 0);
  });

  it("keeps route-specific limits in separate counters", async () => {
    const db = testDatabase();
    const Store = createPostgresRateLimitStore(db, 60_000);
    const root = new Store();
    const routeStore = root.child({
      routeInfo: { url: "/v1/admin/auth/login" },
    } as never);

    await incr(root, "203.0.113.3");
    const routeResult = await incr(routeStore, "203.0.113.3");

    assert.equal(routeResult.current, 1);
  });

  it("resets a counter after its window expires", async () => {
    const db = testDatabase();
    const Store = createPostgresRateLimitStore(db, 60_000);
    const store = new Store();

    await incr(store, "203.0.113.2");
    await incr(store, "203.0.113.2");
    await db.query("UPDATE rate_limit_counters SET expires_at = 1 WHERE key = $1", [
      `global\u001f203.0.113.2`,
    ]);
    const afterExpiry = await incr(store, "203.0.113.2");

    assert.equal(afterExpiry.current, 1);
  });
});
