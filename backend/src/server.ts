import Fastify from "fastify";
import { loadConfig } from "./config";
import { Database } from "./db";
import { buildApp, fastifyOptions } from "./create-app";

async function main(): Promise<void> {
  const config = loadConfig();
  const db = new Database(config.databaseUrl, { max: config.dbPoolMax, ssl: config.dbSsl });
  await db.migrate();
  const app = Fastify(fastifyOptions(config));
  await buildApp({ config, db }, app);

  const shutdown = async (signal: string): Promise<void> => {
    app.log.info({ signal }, "shutting down");
    await app.close();
    await db.close();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));

  await app.listen({ host: config.host, port: config.port });
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
