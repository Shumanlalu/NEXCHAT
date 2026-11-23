import { randomBytes } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import type { FastifyInstance, FastifyReply } from "fastify";

const UI_PATH = path.join(__dirname, "ui.html");
let cached: string | null = null;

function html(nonce: string): string {
  if (cached === null) cached = fs.readFileSync(UI_PATH, "utf8");
  return cached.replace("__CSP_NONCE__", nonce);
}

function sendAdminUi(reply: FastifyReply): void {
  const nonce = randomBytes(18).toString("base64");
  let page: string;
  try {
    page = html(nonce);
  } catch {
    reply
      .code(503)
      .type("text/plain; charset=utf-8")
      .send("Administrator panel assets are unavailable in this deployment.");
    return;
  }
  reply
    .header("cache-control", "no-store, max-age=0")
    .header("pragma", "no-cache")
    .header("referrer-policy", "no-referrer")
    .header("x-content-type-options", "nosniff")
    .header("x-frame-options", "DENY")
    .header(
      "content-security-policy",
      [
        "default-src 'none'",
        `script-src 'nonce-${nonce}'`,
        "style-src 'unsafe-inline'",
        "img-src 'self' data:",
        "connect-src 'self'",
        "font-src 'self'",
        "base-uri 'none'",
        "form-action 'self'",
        "frame-ancestors 'none'",
      ].join("; "),
    )
    .type("text/html; charset=utf-8")
    .send(page);
}

export async function registerAdminUiRoutes(app: FastifyInstance): Promise<void> {
  app.get("/admin", async (_request, reply) => sendAdminUi(reply));
  app.get("/admin/*", async (_request, reply) => sendAdminUi(reply));
}
