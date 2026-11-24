import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { Database } from "../db";
import { HttpError, requireAuth, type UserRow } from "../http";
import { publicUser } from "../security/ids";
import type { TokenService } from "../security/tokens";
import { groupCreateSchema, groupMembersSchema, parseWith } from "../validation";

interface Deps {
  db: Database;
  tokens: TokenService;
}

interface MemberRow {
  user_id: string;
  role: string;
  joined_at: Date | string;
  nex_id: string;
  username: string;
  display_name: string;
  avatar: string | null;
}

function memberView(row: MemberRow) {
  return {
    userId: row.user_id,
    nexId: row.nex_id,
    username: row.username,
    displayName: row.display_name,
    avatar: row.avatar,
    role: row.role,
    joinedAt: new Date(row.joined_at).toISOString(),
  };
}

async function getGroupOrThrow(db: Database, groupId: string, userId: string) {
  if (!/^[0-9a-f-]{36}$/i.test(groupId)) {
    throw new HttpError(400, "INVALID_GROUP_ID", "Invalid group identifier");
  }
  const group = await db.one<{ id: string; created_by: string; created_at: Date | string }>(
    "SELECT * FROM groups WHERE id = $1",
    [groupId],
  );
  if (!group) throw new HttpError(404, "GROUP_NOT_FOUND", "Group not found");
  const membership = await db.one<{ role: string }>(
    `SELECT role FROM group_members
     WHERE group_id = $1 AND user_id = $2 AND removed_at IS NULL`,
    [groupId, userId],
  );
  if (!membership) throw new HttpError(403, "NOT_A_GROUP_MEMBER", "You are not a member of this group");
  return { group, role: membership.role };
}

async function groupDetails(db: Database, groupId: string) {
  const group = await db.one<{ id: string; created_by: string; created_at: Date | string }>(
    "SELECT * FROM groups WHERE id = $1",
    [groupId],
  );
  if (!group) throw new HttpError(404, "GROUP_NOT_FOUND", "Group not found");
  const members = await db.query<MemberRow>(
    `SELECT gm.user_id, gm.role, gm.joined_at, u.nex_id, u.username, u.display_name, u.avatar
     FROM group_members gm JOIN users u ON u.id = gm.user_id
     WHERE gm.group_id = $1 AND gm.removed_at IS NULL AND u.deleted_at IS NULL
       AND u.suspended_at IS NULL AND u.banned_at IS NULL
     ORDER BY gm.joined_at ASC`,
    [groupId],
  );
  return {
    id: group.id,
    createdBy: group.created_by,
    createdAt: new Date(group.created_at).toISOString(),
    members: members.map(memberView),
  };
}

async function findUsersByNexIds(db: Database, nexIds: string[]): Promise<UserRow[]> {
  if (nexIds.length === 0) return [];
  const unique = [...new Set(nexIds)];
  const placeholders = unique.map((_, i) => `$${i + 1}`).join(",");
  return db.query<UserRow>(
    `SELECT * FROM users
     WHERE nex_id IN (${placeholders}) AND deleted_at IS NULL
       AND suspended_at IS NULL AND banned_at IS NULL`,
    unique,
  );
}

export async function registerGroupRoutes(app: FastifyInstance, deps: Deps): Promise<void> {
  const { db, tokens } = deps;

  app.post("/v1/groups", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const body = parseWith(groupCreateSchema, request.body);
    const requestedMembers = body.memberNexIds ?? [];
    const users = await findUsersByNexIds(db, requestedMembers);
    if (users.length !== new Set(requestedMembers).size) {
      throw new HttpError(404, "MEMBER_NOT_FOUND", "One or more NEX IDs do not exist");
    }
    const memberIds = new Set([auth.user.id, ...users.map((u) => u.id)]);
    if (memberIds.size > 100) throw new HttpError(400, "GROUP_TOO_LARGE", "Groups support up to 100 members");

    const groupId = randomUUID();
    await db.transaction(async (client) => {
      await client.query("INSERT INTO groups (id, created_by) VALUES ($1,$2)", [
        groupId,
        auth.user.id,
      ]);
      for (const userId of memberIds) {
        await client.query(
          "INSERT INTO group_members (group_id, user_id, role) VALUES ($1,$2,$3)",
          [groupId, userId, userId === auth.user.id ? "admin" : "member"],
        );
      }
    });
    return reply.code(201).send({ group: await groupDetails(db, groupId) });
  });

  app.get("/v1/groups", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const rows = await db.query<{ group_id: string }>(
      `SELECT group_id FROM group_members
       WHERE user_id = $1 AND removed_at IS NULL ORDER BY joined_at ASC`,
      [auth.user.id],
    );
    const groups = [];
    for (const row of rows) {
      groups.push(await groupDetails(db, row.group_id));
    }
    return reply.send({ groups });
  });

  app.get("/v1/groups/:groupId", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { groupId } = request.params as { groupId: string };
    await getGroupOrThrow(db, groupId, auth.user.id);
    return reply.send({ group: await groupDetails(db, groupId) });
  });

  app.post("/v1/groups/:groupId/members", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { groupId } = request.params as { groupId: string };
    const { role } = await getGroupOrThrow(db, groupId, auth.user.id);
    if (role !== "admin") throw new HttpError(403, "ADMIN_REQUIRED", "Only group admins can add members");
    const body = parseWith(groupMembersSchema, request.body);
    const users = await findUsersByNexIds(db, body.memberNexIds);
    if (users.length !== new Set(body.memberNexIds).size) {
      throw new HttpError(404, "MEMBER_NOT_FOUND", "One or more NEX IDs do not exist");
    }
    await db.transaction(async (client) => {
      // Lock the group row so concurrent admin adds cannot both pass the cap check.
      await client.query("SELECT id FROM groups WHERE id = $1 FOR UPDATE", [groupId]);
      const active = await client.query<{ user_id: string }>(
        "SELECT user_id FROM group_members WHERE group_id = $1 AND removed_at IS NULL",
        [groupId],
      );
      const activeIds = new Set(active.rows.map((row) => row.user_id));
      const additions = users.filter((user) => !activeIds.has(user.id));
      if (activeIds.size + additions.length > 100) {
        throw new HttpError(400, "GROUP_TOO_LARGE", "Groups support up to 100 members");
      }
      for (const user of additions) {
        await client.query(
          `INSERT INTO group_members (group_id, user_id, role)
           VALUES ($1,$2,'member')
           ON CONFLICT (group_id, user_id) DO UPDATE SET removed_at = NULL, role = 'member', joined_at = now()`,
          [groupId, user.id],
        );
      }
    });
    return reply.send({ group: await groupDetails(db, groupId) });
  });

  app.delete("/v1/groups/:groupId/members/:userId", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { groupId, userId } = request.params as { groupId: string; userId: string };
    const { role } = await getGroupOrThrow(db, groupId, auth.user.id);
    if (role !== "admin") throw new HttpError(403, "ADMIN_REQUIRED", "Only group admins can remove members");
    if (userId === auth.user.id) {
      throw new HttpError(400, "USE_LEAVE", "Use the leave endpoint to leave a group");
    }
    const member = await db.one<{ user_id: string; role: string }>(
      "SELECT user_id, role FROM group_members WHERE group_id = $1 AND user_id = $2 AND removed_at IS NULL",
      [groupId, userId],
    );
    if (!member) throw new HttpError(404, "MEMBER_NOT_FOUND", "That user is not in the group");
    await db.query(
      "UPDATE group_members SET removed_at = now() WHERE group_id = $1 AND user_id = $2",
      [groupId, userId],
    );
    return reply.send({ group: await groupDetails(db, groupId) });
  });

  app.post("/v1/groups/:groupId/leave", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { groupId } = request.params as { groupId: string };
    const { role } = await getGroupOrThrow(db, groupId, auth.user.id);
    await db.query(
      "UPDATE group_members SET removed_at = now() WHERE group_id = $1 AND user_id = $2",
      [groupId, auth.user.id],
    );
    if (role === "admin") {
      const remainingAdmin = await db.one<{ user_id: string }>(
        `SELECT user_id FROM group_members
         WHERE group_id = $1 AND removed_at IS NULL AND role = 'admin' ORDER BY joined_at LIMIT 1`,
        [groupId],
      );
      if (!remainingAdmin) {
        const next = await db.one<{ user_id: string }>(
          `SELECT user_id FROM group_members
           WHERE group_id = $1 AND removed_at IS NULL ORDER BY joined_at LIMIT 1`,
          [groupId],
        );
        if (next) {
          await db.query(
            "UPDATE group_members SET role = 'admin' WHERE group_id = $1 AND user_id = $2",
            [groupId, next.user_id],
          );
        }
      }
    }
    return reply.send({ ok: true });
  });

  app.delete("/v1/groups/:groupId", async (request, reply) => {
    const auth = await requireAuth(request, db, tokens);
    const { groupId } = request.params as { groupId: string };
    const { group } = await getGroupOrThrow(db, groupId, auth.user.id);
    if (group.created_by !== auth.user.id) {
      throw new HttpError(403, "CREATOR_REQUIRED", "Only the group creator can delete it");
    }
    await db.query("DELETE FROM groups WHERE id = $1", [groupId]);
    return reply.send({ ok: true });
  });
}
