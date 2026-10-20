import { randomUUID } from "node:crypto";
import { HttpError } from "../errors.js";
import type { CallRecord } from "../models.js";
import type { Store } from "../store/store.js";
import { areAcceptedFriends, areBlocked } from "../routes/friends.js";

export class CallService {
  constructor(private readonly store: Store) {}

  async startVoip(callerId: string, receiverId: string): Promise<CallRecord> {
    if (callerId === receiverId) throw new HttpError(400, "You cannot call yourself", "INVALID_CALL");
    return this.store.mutate((state) => {
      const now = new Date();
      // Unanswered rings must not lock either account forever after a client/network failure.
      for (const existing of state.calls) {
        if (existing.type === "voip" && existing.status === "ringing" && now.getTime() - new Date(existing.startedAt).getTime() > 60_000) {
          existing.status = "missed";
          existing.endedAt = now.toISOString();
        }
      }
      if (!state.users.some((user) => user.id === receiverId && !user.suspendedAt)) throw new HttpError(404, "User not found", "USER_NOT_FOUND");
      if (areBlocked(state, callerId, receiverId)) throw new HttpError(403, "Call not permitted", "BLOCKED");
      if (!areAcceptedFriends(state, callerId, receiverId)) throw new HttpError(403, "Only accepted friends can be called", "NOT_FRIENDS");
      const cutoff = Date.now() - 10 * 60_000;
      const recentCalls = state.calls.filter((call) => call.callerId === callerId && call.type === "voip" && new Date(call.startedAt).getTime() > cutoff);
      if (recentCalls.length >= 10) throw new HttpError(429, "Call frequency limit reached", "CALL_RATE_LIMIT");
      const participants = new Set([callerId, receiverId]);
      const active = state.calls.some((call) => call.type === "voip" && ["ringing", "connected"].includes(call.status) &&
        (participants.has(call.callerId) || (call.receiverId ? participants.has(call.receiverId) : false)));
      if (active) throw new HttpError(409, "An active call already exists", "ACTIVE_CALL_EXISTS");
      const call: CallRecord = {
        id: randomUUID(), callerId, receiverId, type: "voip", startedAt: now.toISOString(),
        durationSeconds: 0, status: "ringing", reconnectionCount: 0
      };
      state.calls.push(call);
      return structuredClone(call);
    });
  }

  async startCustomer(callerId: string, deliveryReference?: string): Promise<CallRecord> {
    return this.store.mutate((state) => {
      const call: CallRecord = {
        id: randomUUID(), callerId, type: "customer", startedAt: new Date().toISOString(),
        durationSeconds: 0, status: "connected", reconnectionCount: 0,
        ...(deliveryReference ? { deliveryReference } : {})
      };
      state.calls.push(call);
      return structuredClone(call);
    });
  }

  async getParticipantCall(callId: string, userId: string): Promise<CallRecord> {
    const state = await this.store.read();
    const call = state.calls.find((item) => item.id === callId && (item.callerId === userId || item.receiverId === userId));
    if (!call) throw new HttpError(404, "Call not found", "CALL_NOT_FOUND");
    return call;
  }

  async answer(callId: string, userId: string): Promise<CallRecord> {
    return this.store.mutate((state) => {
      const call = state.calls.find((item) => item.id === callId && item.receiverId === userId && item.type === "voip");
      if (!call || call.status !== "ringing") throw new HttpError(409, "Call is no longer ringing", "CALL_NOT_RINGING");
      call.status = "connected";
      call.answeredAt = new Date().toISOString();
      return structuredClone(call);
    });
  }

  async end(callId: string, userId: string, status: "ended" | "declined" | "failed" = "ended", quality?: CallRecord["quality"]): Promise<CallRecord> {
    return this.store.mutate((state) => {
      const call = state.calls.find((item) => item.id === callId && (item.callerId === userId || item.receiverId === userId));
      if (!call) throw new HttpError(404, "Call not found", "CALL_NOT_FOUND");
      if (["ended", "declined", "missed", "failed"].includes(call.status)) return structuredClone(call);
      const endedAt = new Date();
      call.endedAt = endedAt.toISOString();
      call.status = status;
      call.quality = quality ?? call.quality ?? "unknown";
      const basis = call.answeredAt ?? call.startedAt;
      call.durationSeconds = Math.max(0, Math.round((endedAt.getTime() - new Date(basis).getTime()) / 1000));
      return structuredClone(call);
    });
  }

  async reconnect(callId: string, userId: string): Promise<CallRecord> {
    return this.store.mutate((state) => {
      const call = state.calls.find((item) => item.id === callId && (item.callerId === userId || item.receiverId === userId));
      if (!call || call.status !== "connected") throw new HttpError(409, "Call is not connected", "CALL_NOT_CONNECTED");
      call.reconnectionCount += 1;
      return structuredClone(call);
    });
  }
}
