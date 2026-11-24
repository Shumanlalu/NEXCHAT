import assert from "node:assert/strict";
import { randomBytes, scryptSync } from "node:crypto";
import { describe, it } from "node:test";
import {
  hashPassword,
  usesLegacyPasswordHash,
  validatePassword,
  verifyPassword,
} from "../src/security/passwords";

function legacyScrypt(password: string): { hash: string; salt: string } {
  const salt = randomBytes(16);
  const derived = scryptSync(password.normalize("NFKC"), salt, 64, {
    N: 32768,
    r: 8,
    p: 2,
    maxmem: 64 * 1024 * 1024,
  });
  return {
    hash: derived.toString("base64"),
    salt: Buffer.concat([salt, Buffer.from([1])]).toString("base64"),
  };
}

describe("password hashing", () => {
  it("stores a bcrypt hash with no reusable salt for new passwords", async () => {
    const record = await hashPassword("CorrectHorseBattery-9");
    assert.match(record.hash, /^\$2[aby]\$/);
    assert.equal(record.salt, "bcrypt-sha256:v1");
    assert.equal(usesLegacyPasswordHash(record.hash, record.salt), false);
    assert.equal(await verifyPassword("CorrectHorseBattery-9", record.hash, record.salt), true);
    assert.equal(await verifyPassword("wrong-password", record.hash, record.salt), false);
  });

  it("still verifies legacy scrypt rows and flags them for upgrade", async () => {
    const legacy = legacyScrypt("LegacyScryptPassword-7");
    assert.equal(usesLegacyPasswordHash(legacy.hash, legacy.salt), true);
    assert.equal(await verifyPassword("LegacyScryptPassword-7", legacy.hash, legacy.salt), true);
    assert.equal(await verifyPassword("wrong-password", legacy.hash, legacy.salt), false);
  });

  it("keeps the app minimum at 10 and the admin minimum at 12", () => {
    assert.equal(validatePassword("abcdefghij"), null);
    assert.match(validatePassword("abcdefghi") ?? "", /at least 10/);
    assert.match(validatePassword("abcdefghij", 12) ?? "", /at least 12/);
    assert.match(validatePassword("            "), /only whitespace/);
  });
});
