import { z } from "zod";

export const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;

export const base64 = (maxBytes: number, label: string) =>
  z
    .string()
    .min(1)
    .max(Math.ceil(maxBytes * 4 / 3) + 8)
    .regex(BASE64_PATTERN, `${label} must be valid base64`)
    .refine((value) => Buffer.from(value, "base64").length <= maxBytes, {
      message: `${label} exceeds ${maxBytes} bytes`,
    });

export const usernameSchema = z
  .string()
  .trim()
  .transform((v) => v.toLowerCase())
  .pipe(z.string().regex(/^[a-z0-9_]{3,24}$/, "Username must be 3-24 letters, digits, or underscores"));

export const passwordSchema = z
  .string()
  .min(10, "Password must contain at least 10 characters")
  .max(128, "Password must be at most 128 characters");

export const deviceSchema = z.object({
  deviceId: z.string().uuid(),
  name: z.string().trim().min(1).max(64),
  platform: z.string().trim().max(32).default("android"),
  registrationId: z.number().int().min(0).max(16383),
  // Older APKs still send 32-bit values. The relay normalizes them to a
  // libsignal-safe ID (1..127) before storing; see insertDevice in auth.ts.
  signalDeviceId: z.number().int().min(1).max(2_147_483_647),
  identityKey: base64(256, "identityKey"),
  signedPreKeyId: z.number().int().min(0).max(2_147_483_647),
  signedPreKeyPublic: base64(256, "signedPreKeyPublic"),
  signedPreKeySignature: base64(512, "signedPreKeySignature"),
  kyberPreKeyId: z.number().int().min(0).max(2_147_483_647).nullable(),
  kyberPreKeyPublic: base64(8192, "kyberPreKeyPublic").nullable(),
  kyberPreKeySignature: base64(512, "kyberPreKeySignature").nullable(),
  oneTimePreKeys: z
    .array(
      z.object({
        id: z.number().int().min(0).max(2_147_483_647),
        publicKey: base64(256, "oneTimePreKey"),
      }),
    )
    .max(200),
});

export const registerSchema = z.object({
  username: usernameSchema,
  password: passwordSchema,
  displayName: z.string().trim().min(1).max(40),
  device: deviceSchema,
});

export const loginSchema = z.object({
  username: usernameSchema,
  password: z.string().min(1).max(128),
  device: deviceSchema,
});

export const refreshSchema = z.object({
  refreshToken: z.string().min(20).max(256),
});

export const updateProfileSchema = z.object({
  displayName: z.string().trim().min(1).max(40).optional(),
  avatar: z
    .string()
    .max(700_000)
    .nullable()
    .optional(),
});

export const rotateKeysSchema = z.object({
  signedPreKeyId: z.number().int().min(0),
  signedPreKeyPublic: base64(256, "signedPreKeyPublic"),
  signedPreKeySignature: base64(512, "signedPreKeySignature"),
  kyberPreKeyId: z.number().int().min(0).nullable(),
  kyberPreKeyPublic: base64(8192, "kyberPreKeyPublic").nullable(),
  kyberPreKeySignature: base64(512, "kyberPreKeySignature").nullable(),
  oneTimePreKeys: z
    .array(
      z.object({
        id: z.number().int().min(0),
        publicKey: base64(256, "oneTimePreKey"),
      }),
    )
    .max(200),
});

export const signalDeviceIdSchema = z.object({
  // Accept legacy 32-bit values here too; the route normalizes them before
  // persisting so an old installation can repair itself without reinstalling.
  signalDeviceId: z.number().int().min(1).max(2_147_483_647).optional(),
});

export const sendEnvelopeSchema = z.object({
  messages: z
    .array(
      z.object({
        recipientNexId: z.string().regex(/^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/),
        recipientSignalDeviceId: z.number().int().min(1).max(2_147_483_647),
        clientId: z.string().uuid(),
        ciphertext: base64(131_072, "ciphertext"),
        cipherType: z.number().int().min(0).max(10),
        ephemeral: z.boolean().default(false),
        // Call signals may have only a few seconds left when a retry is sent.
        // Rejecting those with a 400 kept them cycling until they expired.
        expiresInSeconds: z.number().int().min(5).max(7 * 24 * 3600).default(7 * 24 * 3600),
        priority: z.boolean().default(false),
        groupId: z.string().uuid().nullable().optional(),
      }),
    )
    .min(1)
    .max(100),
});

export const ackSchema = z.object({
  envelopeIds: z.array(z.string().uuid()).min(1).max(200),
});

export const receiptAckSchema = z.object({
  receiptIds: z.array(z.string().uuid()).min(1).max(500),
});

export const groupCreateSchema = z.object({
  memberNexIds: z.array(z.string().regex(/^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/)).max(99).default([]),
});

export const groupMembersSchema = z.object({
  memberNexIds: z.array(z.string().regex(/^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/)).min(1).max(99),
});

export const reportSchema = z.object({
  targetNexId: z.string().regex(/^NX-[A-Z2-9]{4}-[A-Z2-9]{4}$/),
  reason: z.string().trim().min(1).max(500),
});

export function parseWith<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    const message = result.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
    const error = new Error(message) as Error & { validation: true };
    error.validation = true;
    throw error;
  }
  return result.data;
}
