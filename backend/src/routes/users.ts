import { Router } from "express";
import { z } from "zod";
import type { AuthenticatedRequest } from "../auth/middleware.js";
import { asyncHandler, HttpError } from "../errors.js";
import { toAccountUser, toPublicUser } from "../models.js";
import type { Store } from "../store/store.js";

export interface PresenceReader { isOnline(userId: string): boolean }

const updateSchema = z.object({
  name: z.string().trim().min(2).max(80).optional(),
  phone: z.string().trim().min(7).max(24).nullable().optional(),
  profilePhoto: z.string().url().max(2048).nullable().optional(),
  shareLastActive: z.boolean().optional()
}).refine((value) => Object.keys(value).length > 0, "At least one field is required");

export function createUsersRouter(store: Store, presence: PresenceReader): Router {
  const router = Router();

  router.get("/me", asyncHandler(async (request, response) => {
    response.json({ user: toAccountUser((request as AuthenticatedRequest).user) });
  }));

  router.patch("/me", asyncHandler(async (request, response) => {
    const input = updateSchema.parse(request.body);
    const actor = (request as AuthenticatedRequest).user;
    const user = await store.mutate((state) => {
      const current = state.users.find((item) => item.id === actor.id);
      if (!current) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
      if (input.name !== undefined) current.name = input.name;
      if (input.phone !== undefined) input.phone === null ? delete current.phone : current.phone = input.phone;
      if (input.profilePhoto !== undefined) input.profilePhoto === null ? delete current.profilePhoto : current.profilePhoto = input.profilePhoto;
      if (input.shareLastActive !== undefined) current.shareLastActive = input.shareLastActive;
      return structuredClone(current);
    });
    response.json({ user: toAccountUser(user) });
  }));

  router.get("/search", asyncHandler(async (request, response) => {
    const { q } = z.object({ q: z.string().trim().min(2).max(80) }).parse(request.query);
    const actor = (request as AuthenticatedRequest).user;
    const state = await store.read();
    const needle = q.toLowerCase();
    const blockedIds = new Set(state.blocks.flatMap((block) => block.blockerId === actor.id ? [block.blockedId] : block.blockedId === actor.id ? [block.blockerId] : []));
    const users = state.users
      .filter((user) => user.id !== actor.id && !user.suspendedAt && !blockedIds.has(user.id))
      .filter((user) => user.name.toLowerCase().includes(needle) || user.email.toLowerCase() === needle)
      .slice(0, 20)
      .map((user) => {
        const publicUser = toPublicUser(user, presence.isOnline(user.id));
        if (!user.shareLastActive) delete (publicUser as { lastActiveAt?: string }).lastActiveAt;
        return publicUser;
      });
    response.json({ users });
  }));

  router.get("/:id", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const state = await store.read();
    const blocked = state.blocks.some((item) =>
      (item.blockerId === actor.id && item.blockedId === request.params.id) ||
      (item.blockerId === request.params.id && item.blockedId === actor.id));
    if (blocked) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
    const user = state.users.find((item) => item.id === request.params.id && !item.suspendedAt);
    if (!user) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
    const publicUser = toPublicUser(user, presence.isOnline(user.id));
    if (!user.shareLastActive) delete (publicUser as { lastActiveAt?: string }).lastActiveAt;
    response.json({ user: publicUser });
  }));

  return router;
}
