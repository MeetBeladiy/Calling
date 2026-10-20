import { randomUUID } from "node:crypto";
import { Router } from "express";
import { z } from "zod";
import type { AuthenticatedRequest } from "../auth/middleware.js";
import { asyncHandler, HttpError } from "../errors.js";
import { toPublicUser } from "../models.js";
import type { Store } from "../store/store.js";
import type { PresenceReader } from "./users.js";

const invitationSchema = z.object({ userId: z.string().uuid() });
const reportSchema = z.object({
  userId: z.string().uuid(),
  reason: z.enum(["spam", "harassment", "impersonation", "other"]),
  details: z.string().trim().max(1000).optional()
});

export function areAcceptedFriends(state: Awaited<ReturnType<Store["read"]>>, a: string, b: string): boolean {
  return state.friendships.some((friendship) => friendship.status === "accepted" &&
    ((friendship.requesterId === a && friendship.addresseeId === b) ||
     (friendship.requesterId === b && friendship.addresseeId === a)));
}

export function areBlocked(state: Awaited<ReturnType<Store["read"]>>, a: string, b: string): boolean {
  return state.blocks.some((block) =>
    (block.blockerId === a && block.blockedId === b) || (block.blockerId === b && block.blockedId === a));
}

export function createFriendsRouter(store: Store, presence: PresenceReader): Router {
  const router = Router();

  router.get("/", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const state = await store.read();
    const friendships = state.friendships.filter((item) => item.requesterId === actor.id || item.addresseeId === actor.id);
    response.json({
      friends: friendships.map((friendship) => {
        const otherId = friendship.requesterId === actor.id ? friendship.addresseeId : friendship.requesterId;
        const other = state.users.find((user) => user.id === otherId);
        return {
          id: friendship.id,
          status: friendship.status,
          direction: friendship.requesterId === actor.id ? "outgoing" : "incoming",
          user: other ? toPublicUser(other, presence.isOnline(other.id)) : undefined,
          createdAt: friendship.createdAt
        };
      }).filter((item) => item.user)
    });
  }));

  router.post("/invitations", asyncHandler(async (request, response) => {
    const { userId } = invitationSchema.parse(request.body);
    const actor = (request as AuthenticatedRequest).user;
    if (userId === actor.id) throw new HttpError(400, "You cannot invite yourself", "INVALID_FRIEND");
    const invitation = await store.mutate((state) => {
      if (!state.users.some((user) => user.id === userId && !user.suspendedAt)) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
      if (areBlocked(state, actor.id, userId)) throw new HttpError(403, "Invitation not permitted", "BLOCKED");
      if (state.friendships.some((item) => [item.requesterId, item.addresseeId].includes(actor.id) && [item.requesterId, item.addresseeId].includes(userId))) {
        throw new HttpError(409, "A friend relationship already exists", "FRIENDSHIP_EXISTS");
      }
      const now = new Date().toISOString();
      const created = { id: randomUUID(), requesterId: actor.id, addresseeId: userId, status: "pending" as const, createdAt: now, updatedAt: now };
      state.friendships.push(created);
      return created;
    });
    response.status(201).json({ invitation });
  }));

  router.post("/invitations/:id/accept", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const invitationId = z.string().uuid().parse(request.params.id);
    const friendship = await store.mutate((state) => {
      const item = state.friendships.find((candidate) => candidate.id === invitationId);
      if (!item || item.addresseeId !== actor.id || item.status !== "pending") throw new HttpError(404, "Invitation not found", "INVITATION_NOT_FOUND");
      if (areBlocked(state, item.requesterId, item.addresseeId)) throw new HttpError(403, "Invitation not permitted", "BLOCKED");
      item.status = "accepted";
      item.updatedAt = new Date().toISOString();
      return structuredClone(item);
    });
    response.json({ friendship });
  }));

  router.post("/invitations/:id/reject", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const invitationId = z.string().uuid().parse(request.params.id);
    await store.mutate((state) => {
      const index = state.friendships.findIndex((item) => item.id === invitationId && item.addresseeId === actor.id && item.status === "pending");
      if (index < 0) throw new HttpError(404, "Invitation not found", "INVITATION_NOT_FOUND");
      state.friendships.splice(index, 1);
    });
    response.status(204).end();
  }));

  router.delete("/:userId", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const userId = z.string().uuid().parse(request.params.userId);
    await store.mutate((state) => {
      state.friendships = state.friendships.filter((item) => !(
        [item.requesterId, item.addresseeId].includes(actor.id) &&
        [item.requesterId, item.addresseeId].includes(userId)
      ));
    });
    response.status(204).end();
  }));

  router.post("/blocks", asyncHandler(async (request, response) => {
    const { userId } = invitationSchema.parse(request.body);
    const actor = (request as AuthenticatedRequest).user;
    if (userId === actor.id) throw new HttpError(400, "You cannot block yourself", "INVALID_BLOCK");
    await store.mutate((state) => {
      if (!state.users.some((user) => user.id === userId)) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
      if (!state.blocks.some((item) => item.blockerId === actor.id && item.blockedId === userId)) {
        state.blocks.push({ blockerId: actor.id, blockedId: userId, createdAt: new Date().toISOString() });
      }
      state.friendships = state.friendships.filter((item) => !(
        [item.requesterId, item.addresseeId].includes(actor.id) && [item.requesterId, item.addresseeId].includes(userId)
      ));
    });
    response.status(204).end();
  }));

  router.delete("/blocks/:userId", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const userId = z.string().uuid().parse(request.params.userId);
    await store.mutate((state) => {
      state.blocks = state.blocks.filter((item) => !(item.blockerId === actor.id && item.blockedId === userId));
    });
    response.status(204).end();
  }));

  router.post("/reports", asyncHandler(async (request, response) => {
    const input = reportSchema.parse(request.body);
    const actor = (request as AuthenticatedRequest).user;
    if (input.userId === actor.id) throw new HttpError(400, "You cannot report yourself", "INVALID_REPORT");
    const report = await store.mutate((state) => {
      if (!state.users.some((user) => user.id === input.userId)) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
      const created = { id: randomUUID(), reporterId: actor.id, reportedId: input.userId, reason: input.reason, ...(input.details ? { details: input.details } : {}), createdAt: new Date().toISOString() };
      state.reports.push(created);
      return created;
    });
    response.status(201).json({ reportId: report.id });
  }));

  return router;
}
