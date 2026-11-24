import type { RouteOptions } from "fastify";
import type { Database } from "./db";

type CounterResult = { current: number; ttl: number };
type CounterCallback = (error: Error | null, result?: CounterResult) => void;

export interface RateLimitStoreLike {
  incr(key: string, callback: CounterCallback): void;
  child(routeOptions: RouteOptions & { path: string; prefix: string }): RateLimitStoreLike;
}

type StoreOptions = {
  timeWindow?: number | string;
  routeInfo?: { url?: string };
  path?: string;
  url?: string;
};

function windowFrom(options: StoreOptions, fallbackMs: number): number {
  const value = options.timeWindow;
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return Math.trunc(value);
  }
  return fallbackMs;
}

function namespaceFrom(options: StoreOptions): string {
  return options.routeInfo?.url ?? options.path ?? options.url ?? "global";
}

/**
 * A @fastify/rate-limit store backed by Postgres rather than process memory.
 * Every serverless instance increments the same row, so the configured limit
 * remains a global limit instead of being multiplied by instance count.
 */
export function createPostgresRateLimitStore(
  db: Database,
  windowMs: number,
): new () => RateLimitStoreLike {
  let calls = 0;

  class PostgresRateLimitStore implements RateLimitStoreLike {
    private readonly windowMs: number;
    private readonly namespace: string;

    constructor(options: StoreOptions = {}) {
      this.windowMs = windowFrom(options, windowMs);
      this.namespace = namespaceFrom(options);
    }

    incr(key: string, callback: CounterCallback): void {
      const now = Date.now();
      const expiresAt = now + this.windowMs;
      const counterKey = `${this.namespace}\u001f${key}`;
      void db
        .query<{ current: string | number; expires_at: string | number }>(
          `INSERT INTO rate_limit_counters (key, current, expires_at)
           VALUES ($1, 1, $3)
           ON CONFLICT (key) DO UPDATE
           SET current = CASE
                 WHEN rate_limit_counters.expires_at <= $2 THEN 1
                 ELSE rate_limit_counters.current + 1
               END,
               expires_at = CASE
                 WHEN rate_limit_counters.expires_at <= $2 THEN $3
                 ELSE rate_limit_counters.expires_at
               END
           RETURNING current, expires_at`,
          [counterKey, now, expiresAt],
        )
        .then((rows) => {
          const row = rows[0];
          if (!row) {
            callback(new Error("rate-limit counter was not persisted"));
            return;
          }
          callback(null, {
            current: Number(row.current),
            ttl: Math.max(0, Number(row.expires_at) - Date.now()),
          });
        })
        .catch((error: unknown) => callback(error as Error));

      // Bounded opportunistic cleanup; counters are tiny and this avoids a
      // background worker or another paid dependency.
      calls += 1;
      if (calls % 256 === 0) {
        void db.query("DELETE FROM rate_limit_counters WHERE expires_at <= $1", [now]).catch(() => undefined);
      }
    }

    child(routeOptions: RouteOptions & { path: string; prefix: string }): RateLimitStoreLike {
      return new PostgresRateLimitStore(routeOptions as unknown as StoreOptions);
    }
  }

  return PostgresRateLimitStore;
}
