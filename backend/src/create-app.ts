import websocket from "@fastify/websocket";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import type { FastifyInstance, FastifyServerOptions } from "fastify";
import type { AppConfig } from "./config";
import type { Database } from "./db";
import { HttpError, getBearerToken, type DeviceRow, type UserRow } from "./http";
import { registerAuthRoutes } from "./routes/auth";
import { registerGroupRoutes } from "./routes/groups";
import { registerMessageRoutes } from "./routes/messages";
import { registerKeyRoutes, registerUserRoutes } from "./routes/users";
import { registerAdminAuthRoutes } from "./routes/admin-auth";
import { registerAdminCoreRoutes } from "./routes/admin-core";
import { registerAdminConfigRoutes } from "./routes/admin-config";
import { registerAnalyticsRoutes } from "./routes/analytics";
import { registerAdminUiRoutes } from "./admin/ui";
import { TokenService } from "./security/tokens";
import { WsHub } from "./ws/hub";

export interface AppDependencies {
  config: AppConfig;
  db: Database;
}

export function fastifyOptions(config: AppConfig): FastifyServerOptions {
  return {
    logger: {
      level: config.logLevel,
      redact: {
        paths: [
          "req.headers.authorization",
          "req.headers.cookie",
          "req.headers.x-setup-token",
          "req.body.password",
          "req.body.currentPassword",
          "req.body.newPassword",
          "req.body.confirmPassword",
          "req.body.token",
          "req.body.resetToken",
          "req.body.setupToken",
          "req.body.refreshToken",
          "req.body.device.identityKey",
          "req.body.device.oneTimePreKeys",
          "res.headers.set-cookie",
          "*.password",
          "*.currentPassword",
          "*.newPassword",
          "*.confirmPassword",
          "*.token",
          "*.resetToken",
          "*.setupToken",
          "*.refreshToken",
        ],
        censor: "[REDACTED]",
      },
    },
    trustProxy: config.trustProxy,
    bodyLimit: Math.max(config.maxAttachmentBytes + 16_384, 1_048_576),
    requestIdHeader: "x-request-id",
    genReqId: () => crypto.randomUUID(),
  };
}

export async function buildApp(
  deps: AppDependencies,
  app: FastifyInstance,
): Promise<FastifyInstance> {
  const { config, db } = deps;
  const tokens = new TokenService(config);
  const hub = new WsHub();

  await app.register(helmet, {
    contentSecurityPolicy: false,
    crossOriginResourcePolicy: { policy: "same-origin" },
  });
  await app.register(cors, {
    origin: config.env === "development" ? true : config.corsOrigin,
    methods: ["GET", "POST", "PATCH", "DELETE"],
    allowedHeaders: ["Authorization", "Content-Type", "X-Request-Id"],
    maxAge: 600,
  });
  await app.register(rateLimit, {
    max: 240,
    timeWindow: "1 minute",
    allowList: [],
  });
  await app.register(websocket, {
    options: { maxPayload: 512 * 1024, perMessageDeflate: false },
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.statusCode).send({
        error: { code: error.code, message: error.message },
      });
    }
    if ((error as Error & { validation?: boolean }).validation) {
      return reply.code(400).send({
        error: { code: "VALIDATION_ERROR", message: error.message },
      });
    }
    const statusCode = (error as { statusCode?: number }).statusCode ?? 500;
    if (statusCode === 429) {
      return reply.code(429).send({
        error: { code: "RATE_LIMITED", message: "Too many requests; try again shortly" },
      });
    }
    if (statusCode >= 400 && statusCode < 500) {
      return reply.code(statusCode).send({
        error: { code: "BAD_REQUEST", message: "The request could not be processed" },
      });
    }
    request.log.error({ err: error }, "unhandled request error");
    return reply.code(500).send({
      error: { code: "INTERNAL_ERROR", message: "An internal server error occurred" },
    });
  });

  app.addHook("onResponse", async (request, reply) => {
    // Do not log bodies. Request metadata is enough to operate the service.
    request.log.info(
      { method: request.method, url: request.routeOptions.url, status: reply.statusCode },
      "request completed",
    );
  });

  await registerAuthRoutes(app, { db, tokens, config, hub });
  await registerUserRoutes(app, { db, tokens, config });
  await registerKeyRoutes(app, { db, tokens, config });
  await registerMessageRoutes(app, { db, tokens, config, hub });
  await registerGroupRoutes(app, { db, tokens });
  await registerAnalyticsRoutes(app, { db, tokens, config });
  await registerAdminAuthRoutes(app, { db, config });
  await registerAdminCoreRoutes(app, { db, config, hub });
  await registerAdminConfigRoutes(app, { db, config });
  await registerAdminUiRoutes(app);

  app.get("/v1/presence/:nexId", async (request, reply) => {
    const authHeader = getBearerToken(request);
    if (!authHeader) throw new HttpError(401, "UNAUTHORIZED", "Authentication required");
    // Presence is intentionally coarse: online only while a socket is connected.
    // Clients that have disabled presence never call this endpoint.
    let claims;
    try {
      claims = await tokens.verifyAccessToken(authHeader);
    } catch {
      throw new HttpError(401, "INVALID_TOKEN", "Access token is invalid");
    }
    const own = await db.one("SELECT id FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL", [
      claims.did,
      claims.sub,
    ]);
    if (!own) throw new HttpError(401, "SESSION_REVOKED", "Session revoked");
    const { nexId } = request.params as { nexId: string };
    const target = await db.query<{ id: string; share_presence: boolean }>(
      "SELECT id, share_presence FROM users WHERE nex_id = $1 AND deleted_at IS NULL",
      [nexId],
    );
    if (!target[0]) throw new HttpError(404, "USER_NOT_FOUND", "User not found");
    if (!target[0].share_presence) {
      return reply.send({ online: false, hidden: true });
    }
    const devices = await db.query<{ id: string }>(
      "SELECT id FROM devices WHERE user_id = $1 AND revoked_at IS NULL",
      [target[0].id],
    );
    return reply.send({ online: devices.some((d) => hub.isOnline(d.id)), hidden: false });
  });

  app.get("/v1/ws", { websocket: true }, (socket, request) => {
    void (async () => {
      const token = getBearerToken(request);
      if (!token) {
        socket.close(4401, "authentication required");
        return;
      }
      let claims;
      try {
        claims = await tokens.verifyAccessToken(token);
      } catch {
        socket.close(4401, "invalid token");
        return;
      }
      const rows = await db.query<UserRow & DeviceRow>(
        `SELECT u.id, u.nex_id, u.deleted_at, u.suspended_at, d.id AS ws_device_id, d.revoked_at
         FROM users u JOIN devices d ON d.user_id = u.id
         WHERE u.id = $1 AND d.id = $2`,
        [claims.sub, claims.did],
      );
      const row = rows[0];
      if (!row || row.deleted_at || row.suspended_at || row.revoked_at) {
        socket.close(4403, "session revoked");
        return;
      }

      if (socket.readyState !== socket.OPEN) return;
      const connection = hub.add(socket, claims.sub, claims.did);
      try {
        socket.send(JSON.stringify({ type: "ready", deviceId: claims.did }));
      } catch {
        hub.remove(connection);
        return;
      }
      await db
        .query("UPDATE devices SET last_seen_at = now() WHERE id = $1", [claims.did])
        .catch(() => undefined);

      const heartbeat = setInterval(() => {
        if (!connection.alive) {
          socket.terminate();
          clearInterval(heartbeat);
          return;
        }
        connection.alive = false;
        try {
          socket.ping();
        } catch {
          clearInterval(heartbeat);
        }
      }, 30_000);
      heartbeat.unref();

      socket.on("message", (raw) => {
        const text = raw.toString();
        if (Buffer.byteLength(text, "utf8") > 4096) {
          socket.close(1009, "message too large");
          return;
        }
        try {
          const data = JSON.parse(text) as { type?: unknown };
          if (data.type === "ping") socket.send(JSON.stringify({ type: "pong" }));
        } catch {
          // Ignore malformed control frames. Message content never travels this way.
        }
      });
      socket.on("close", () => clearInterval(heartbeat));
    })().catch((error: unknown) => {
      request.log.error({ err: error }, "websocket authentication failed");
      try {
        socket.close(1011, "internal error");
      } catch {
        // Socket already closed.
      }
    });
  });

  app.addHook("onClose", async () => {
    hub.closeAll();
  });

  return app;
}
