import fs from "node:fs";
import path from "node:path";
import { Pool, type PoolClient, type QueryResultRow } from "pg";

export class Database {
  readonly pool: Pool;

  constructor(databaseUrl: string, options: { max?: number; ssl?: boolean } = {}) {
    this.pool = new Pool({
      connectionString: databaseUrl,
      max: options.max ?? 10,
      ssl: options.ssl ? { rejectUnauthorized: true } : undefined,
      application_name: "nexchat-server",
    });
  }

  async query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    values: unknown[] = [],
  ): Promise<T[]> {
    const result = await this.pool.query<T>(text, values);
    return result.rows;
  }

  async one<T extends QueryResultRow = QueryResultRow>(
    text: string,
    values: unknown[] = [],
  ): Promise<T | null> {
    const rows = await this.query<T>(text, values);
    return rows[0] ?? null;
  }

  async transaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await fn(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async migrate(): Promise<void> {
    const schemaPath = path.join(__dirname, "schema.sql");
    const schema = fs.readFileSync(schemaPath, "utf8");
    // Serverless cold starts can overlap. Serialize schema creation so two
    // instances cannot race on indexes or constraint changes.
    const client = await this.pool.connect();
    try {
      await client.query("SELECT pg_advisory_lock(741852963)");
      await client.query(schema);
      await this.repairSignalDeviceIds(client);
    } finally {
      await client.query("SELECT pg_advisory_unlock(741852963)").catch(() => undefined);
      client.release();
    }
  }

  /**
   * Device rows created before the libsignal device-ID limit was enforced can
   * contain values outside 1..127. libsignal cannot construct a protocol
   * address for those rows, which makes calls and messages fail for every
   * peer. Rewrite them once, assigning the lowest free ID per account.
   */
  private async repairSignalDeviceIds(client: PoolClient): Promise<void> {
    const invalid = await client.query<{
      id: string;
      user_id: string;
      signal_device_id: number;
    }>(
      `SELECT id, user_id, signal_device_id FROM devices
       WHERE revoked_at IS NULL AND (signal_device_id < 1 OR signal_device_id > 127)
       ORDER BY user_id, created_at`,
    );
    if (invalid.rows.length === 0) return;

    const valid = await client.query<{ user_id: string; signal_device_id: number }>(
      `SELECT user_id, signal_device_id FROM devices
       WHERE revoked_at IS NULL AND signal_device_id BETWEEN 1 AND 127`,
    );
    const used = new Map<string, Set<number>>();
    for (const row of valid.rows) {
      let ids = used.get(row.user_id);
      if (!ids) {
        ids = new Set();
        used.set(row.user_id, ids);
      }
      ids.add(row.signal_device_id);
    }

    for (const row of invalid.rows) {
      let ids = used.get(row.user_id);
      if (!ids) {
        ids = new Set();
        used.set(row.user_id, ids);
      }
      let assigned = 0;
      for (let id = 1; id <= 127; id += 1) {
        if (!ids.has(id)) {
          assigned = id;
          break;
        }
      }
      if (assigned === 0) continue;
      await client.query(
        "UPDATE devices SET signal_device_id = $1 WHERE id = $2",
        [assigned, row.id],
      );
      ids.add(assigned);
    }
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}
