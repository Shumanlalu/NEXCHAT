import { createHash, randomBytes, randomUUID } from "node:crypto";
import { SignJWT, jwtVerify } from "jose";
import type { AppConfig } from "../config";

export interface AccessClaims {
  sub: string;
  did: string;
  typ: "access";
}

export class TokenService {
  private readonly key: Uint8Array;

  constructor(private readonly config: AppConfig) {
    this.key = new TextEncoder().encode(config.jwtSecret);
  }

  async issueAccessToken(userId: string, deviceId: string): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    return new SignJWT({ did: deviceId, typ: "access" })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setSubject(userId)
      .setIssuer("nexchat")
      .setAudience("nexchat-android")
      .setIssuedAt(now)
      .setExpirationTime(now + this.config.accessTokenTtlSeconds)
      .setJti(randomUUID())
      .sign(this.key);
  }

  async verifyAccessToken(token: string): Promise<AccessClaims> {
    const { payload } = await jwtVerify(token, this.key, {
      issuer: "nexchat",
      audience: "nexchat-android",
      algorithms: ["HS256"],
    });
    if (
      typeof payload.sub !== "string" ||
      typeof payload.did !== "string" ||
      payload.typ !== "access"
    ) {
      throw new Error("Invalid access token claims");
    }
    return { sub: payload.sub, did: payload.did, typ: "access" };
  }

  createRefreshToken(): { token: string; hash: string } {
    const token = randomBytes(48).toString("base64url");
    return { token, hash: hashToken(token) };
  }

  refreshExpiry(): Date {
    return new Date(Date.now() + this.config.refreshTokenTtlDays * 24 * 60 * 60 * 1000);
  }
}

export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}
