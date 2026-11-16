import { randomInt, randomUUID } from "node:crypto";

const NEX_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export function generateNexId(): string {
  let raw = "";
  for (let i = 0; i < 8; i += 1) {
    raw += NEX_ALPHABET[randomInt(0, NEX_ALPHABET.length)];
  }
  return `NX-${raw.slice(0, 4)}-${raw.slice(4)}`;
}

export function newId(): string {
  return randomUUID();
}

export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value,
  );
}

export function isNexId(value: string): boolean {
  return /^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(value);
}

export function normalizeUsername(value: string): string {
  return value.trim().toLowerCase();
}

export function publicUser(row: {
  id: string;
  nex_id: string;
  username: string;
  display_name: string;
  avatar: string | null;
  created_at: Date | string;
}) {
  return {
    id: row.id,
    nexId: row.nex_id,
    username: row.username,
    displayName: row.display_name,
    avatar: row.avatar,
    createdAt: new Date(row.created_at).toISOString(),
  };
}
