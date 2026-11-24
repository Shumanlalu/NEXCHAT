import { createHash, scrypt as scryptCallback, timingSafeEqual } from "node:crypto";
import bcrypt from "bcryptjs";

const KEY_LENGTH = 64;
const SCRYPT_COST = 32768;
const SCRYPT_BLOCK_SIZE = 8;
const SCRYPT_PARALLELIZATION = 2;
const SCRYPT_SALT_VERSION = 1;
const BCRYPT_ROUNDS = 12;
const BCRYPT_MARKER = "bcrypt-sha256:v1";

/**
 * New passwords use bcrypt with a SHA-256 prehash. The prehash keeps bcrypt's
 * 72-byte input limit from silently truncating longer passwords while still
 * storing a standard bcrypt hash. Existing scrypt rows are verified with their
 * original parameters and transparently upgraded to bcrypt on next login.
 */
interface ScryptOptions {
  N: number;
  r: number;
  p: number;
  maxmem: number;
}

export interface PasswordRecord {
  hash: string;
  salt: string;
}

function deriveKey(password: string, salt: Buffer, options: ScryptOptions): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scryptCallback(password, salt, KEY_LENGTH, options, (error, derived) => {
      if (error) reject(error);
      else resolve(derived as Buffer);
    });
  });
}

function bcryptInput(password: string): string {
  return createHash("sha256").update(password.normalize("NFKC")).digest("base64");
}

export async function hashPassword(password: string): Promise<PasswordRecord> {
  const hash = await bcrypt.hash(bcryptInput(password), BCRYPT_ROUNDS);
  return { hash, salt: BCRYPT_MARKER };
}

export function usesLegacyPasswordHash(storedHash: string, storedSalt: string): boolean {
  return storedSalt !== BCRYPT_MARKER && !storedHash.startsWith("$2");
}

export async function verifyPassword(
  password: string,
  storedHash: string,
  storedSalt: string,
): Promise<boolean> {
  try {
    if (!usesLegacyPasswordHash(storedHash, storedSalt)) {
      return await bcrypt.compare(bcryptInput(password), storedHash);
    }

    // Legacy scrypt rows: salt = random bytes + one version byte, hash = 64 bytes.
    const saltBlob = Buffer.from(storedSalt, "base64");
    if (saltBlob.length < 2) return false;
    const version = saltBlob[saltBlob.length - 1];
    if (version !== SCRYPT_SALT_VERSION) return false;
    const salt = saltBlob.subarray(0, saltBlob.length - 1);
    const expected = Buffer.from(storedHash, "base64");
    if (expected.length !== KEY_LENGTH) return false;
    const derived = await deriveKey(password.normalize("NFKC"), salt, {
      N: SCRYPT_COST,
      r: SCRYPT_BLOCK_SIZE,
      p: SCRYPT_PARALLELIZATION,
      maxmem: 64 * 1024 * 1024,
    });
    return timingSafeEqual(expected, derived);
  } catch {
    return false;
  }
}

export function validatePassword(password: string, minLength = 10): string | null {
  if (password.length < minLength) {
    return `Password must contain at least ${minLength} characters`;
  }
  if (password.length > 128) return "Password must be at most 128 characters";
  if (/^\s+$/.test(password)) return "Password cannot be only whitespace";
  return null;
}
