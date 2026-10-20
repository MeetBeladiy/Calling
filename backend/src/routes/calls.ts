import { Router } from "express";
import { z } from "zod";
import type { AuthenticatedRequest } from "../auth/middleware.js";
import type { CallService } from "../calls/service.js";
import { asyncHandler } from "../errors.js";
import { toPublicUser } from "../models.js";
import type { Store } from "../store/store.js";

const startSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("voip"), receiverId: z.string().uuid() }),
  z.object({ type: z.literal("customer"), deliveryReference: z.string().trim().max(100).optional() })
]);
const endSchema = z.object({ quality: z.enum(["excellent", "good", "weak", "unknown"]).optional() });

export function createCallsRouter(store: Store, calls: CallService): Router {
  const router = Router();

  router.get("/", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const { limit, before } = z.object({
      limit: z.coerce.number().int().min(1).max(100).default(25),
      before: z.string().datetime().optional()
    }).parse(request.query);
    const state = await store.read();
    const history = state.calls
      .filter((call) => call.callerId === actor.id || call.receiverId === actor.id)
      .filter((call) => !before || call.startedAt < before)
      .sort((a, b) => b.startedAt.localeCompare(a.startedAt))
      .slice(0, limit)
      .map((call) => {
        const peerId = call.callerId === actor.id ? call.receiverId : call.callerId;
        const peer = peerId ? state.users.find((user) => user.id === peerId) : undefined;
        return { ...call, peer: peer ? toPublicUser(peer) : undefined };
      });
    response.json({ calls: history, nextCursor: history.length === limit ? history.at(-1)?.startedAt : undefined });
  }));

  router.post("/", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const input = startSchema.parse(request.body);
    const call = input.type === "voip"
      ? await calls.startVoip(actor.id, input.receiverId)
      : await calls.startCustomer(actor.id, input.deliveryReference);
    response.status(201).json({ call });
  }));

  router.get("/:id", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const id = z.string().uuid().parse(request.params.id);
    response.json({ call: await calls.getParticipantCall(id, actor.id) });
  }));

  router.post("/:id/end", asyncHandler(async (request, response) => {
    const actor = (request as AuthenticatedRequest).user;
    const id = z.string().uuid().parse(request.params.id);
    const input = endSchema.parse(request.body ?? {});
    response.json({ call: await calls.end(id, actor.id, "ended", input.quality) });
  }));

  return router;
}
