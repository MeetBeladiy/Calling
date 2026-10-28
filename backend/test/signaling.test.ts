import { createServer } from "node:http";
import request from "supertest";
import WebSocket from "ws";
import { afterEach, describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { TokenService } from "../src/auth/tokens.js";
import { CallService } from "../src/calls/service.js";
import { loadConfig } from "../src/config.js";
import { SignalingHub } from "../src/signaling/hub.js";
import { MemoryStore } from "../src/store/memory-store.js";

const resources: Array<() => void> = [];
afterEach(() => { while (resources.length) resources.pop()?.(); });

function nextMessage(
  socket: WebSocket,
  type: string,
  matches: (message: Record<string, unknown>) => boolean = () => true
): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${type}`)), 3000);
    const listener = (data: WebSocket.RawData) => {
      const message = JSON.parse(data.toString()) as Record<string, unknown>;
      if (message.type === type && matches(message)) {
        clearTimeout(timeout);
        socket.off("message", listener);
        resolve(message);
      }
    };
    socket.on("message", listener);
  });
}

function nextMessages(socket: WebSocket, types: string[]): Promise<Array<Record<string, unknown>>> {
  return new Promise((resolve, reject) => {
    const messages: Array<Record<string, unknown>> = [];
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${types.join(", ")}`)), 3000);
    const listener = (data: WebSocket.RawData) => {
      const message = JSON.parse(data.toString()) as Record<string, unknown>;
      if (!types.includes(String(message.type))) return;
      messages.push(message);
      if (messages.length === types.length) {
        clearTimeout(timeout);
        socket.off("message", listener);
        resolve(messages);
      }
    };
    socket.on("message", listener);
  });
}

describe("WebSocket signaling", () => {
  it("releases stale active calls after a participant remains disconnected", async () => {
    const store = new MemoryStore();
    const calls = new CallService(store);
    const startedAt = new Date(Date.now() - 90_000).toISOString();
    await store.mutate((state) => {
      state.calls.push(
        {
          id: "stale-call",
          callerId: "user-a",
          receiverId: "user-b",
          type: "voip",
          startedAt,
          answeredAt: startedAt,
          durationSeconds: 0,
          status: "connected",
          reconnectionCount: 0
        },
        {
          id: "unrelated-call",
          callerId: "user-c",
          receiverId: "user-d",
          type: "voip",
          startedAt,
          answeredAt: startedAt,
          durationSeconds: 0,
          status: "connected",
          reconnectionCount: 0
        }
      );
    });

    await expect(
      calls.failActiveCallsForUser("user-a", () => false)
    ).resolves.toEqual([]);
    expect((await store.read()).calls.find((call) => call.id === "stale-call")?.status)
      .toBe("connected");

    await expect(calls.failActiveCallsForUser("user-a")).resolves.toMatchObject([
      { id: "stale-call", status: "failed", endedAt: expect.any(String) }
    ]);
    const state = await store.read();
    expect(state.calls.find((call) => call.id === "stale-call")?.status).toBe("failed");
    expect(state.calls.find((call) => call.id === "unrelated-call")?.status).toBe("connected");

    await expect(calls.failAllActiveCalls()).resolves.toMatchObject([
      { id: "unrelated-call", status: "failed", endedAt: expect.any(String) }
    ]);
  });

  it("authenticates and relays call negotiation only between call participants", async () => {
    const config = loadConfig({ NODE_ENV: "test", JWT_SECRET: "test-secret-with-more-than-thirty-two-characters" });
    const store = new MemoryStore();
    const tokens = new TokenService(config, store);
    const calls = new CallService(store);
    const presence = { isOnline: (_id: string) => false };
    const app = createApp({ config, store, tokens, calls, presence });
    const server = createServer(app);
    const hub = new SignalingHub(store, tokens, calls);
    presence.isOnline = (id) => hub.isOnline(id);
    server.on("upgrade", (req, socket, head) => { void hub.handleUpgrade(req, socket, head); });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    resources.push(() => { hub.close(); server.close(); });
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("No test server address");

    const create = async (name: string, email: string) => (await request(app).post("/api/auth/register").send({ name, email, password: "a-secure-password" })).body;
    const alice = await create("Alice", "alice@ws.test");
    const bob = await create("Bob", "bob@ws.test");
    const invitation = await request(app).post("/api/friends/invitations").set("Authorization", `Bearer ${alice.tokens.accessToken}`).send({ userId: bob.user.id });
    await request(app).post(`/api/friends/invitations/${invitation.body.invitation.id}/accept`).set("Authorization", `Bearer ${bob.tokens.accessToken}`);

    const aliceSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${alice.tokens.accessToken}`);
    const bobSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${bob.tokens.accessToken}`);
    resources.push(() => { aliceSocket.close(); bobSocket.close(); });
    await Promise.all([nextMessage(aliceSocket, "ready"), nextMessage(bobSocket, "ready")]);

    const incomingPromise = nextMessage(bobSocket, "call.incoming");
    const createdPromise = nextMessage(aliceSocket, "call.created");
    aliceSocket.send(JSON.stringify({ type: "call.invite", to: bob.user.id, requestId: "invite-1" }));
    const [incoming, created] = await Promise.all([incomingPromise, createdPromise]);
    expect(incoming.from).toBe(alice.user.id);
    expect(incoming.callId).toBe(created.callId);
    expect(incoming.startedAt).toEqual(expect.any(Number));
    expect(created.delivered).toBe(true);

    const acceptedPromise = nextMessage(aliceSocket, "call.accept");
    const acceptAckPromise = nextMessage(bobSocket, "ack");
    bobSocket.send(JSON.stringify({ type: "call.accept", callId: created.callId, requestId: "accept-1" }));
    await acceptedPromise;
    await expect(acceptAckPromise).resolves.toMatchObject({
      action: "call.accept",
      requestId: "accept-1",
      delivered: true
    });

    // Acceptance is idempotent so a receiver can safely retry after losing the ack.
    const retryAckPromise = nextMessage(bobSocket, "ack");
    bobSocket.send(JSON.stringify({ type: "call.accept", callId: created.callId, requestId: "accept-2" }));
    await expect(retryAckPromise).resolves.toMatchObject({
      action: "call.accept",
      requestId: "accept-2",
      delivered: true
    });

    const offerPromise = nextMessage(bobSocket, "call.offer");
    const offerAckPromise = nextMessage(aliceSocket, "ack");
    const negotiationId = created.callId;
    aliceSocket.send(JSON.stringify({
      type: "call.offer",
      callId: created.callId,
      to: bob.user.id,
      sdp: "v=0",
      negotiationId,
      requestId: "offer-1"
    }));
    const offer = await offerPromise;
    expect(offer).toMatchObject({
      from: alice.user.id,
      callId: created.callId,
      sdp: "v=0",
      negotiationId
    });
    await expect(offerAckPromise).resolves.toMatchObject({
      action: "call.offer",
      requestId: "offer-1",
      delivered: true
    });

    const orderedSignals = nextMessages(aliceSocket, ["call.answer", "ice.candidate"]);
    bobSocket.send(JSON.stringify({
      type: "call.answer",
      callId: created.callId,
      to: alice.user.id,
      sdp: "v=0-answer",
      negotiationId,
      requestId: "answer-1"
    }));
    bobSocket.send(JSON.stringify({
      type: "ice.candidate",
      callId: created.callId,
      to: alice.user.id,
      negotiationId,
      requestId: "candidate-1",
      candidate: { candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 0 }
    }));
    await expect(orderedSignals).resolves.toMatchObject([
      { type: "call.answer", sdp: "v=0-answer", negotiationId },
      { type: "ice.candidate", candidate: { candidate: "candidate:1" }, negotiationId }
    ]);

    const endedAck = nextMessage(aliceSocket, "ack");
    aliceSocket.send(JSON.stringify({
      type: "call.end",
      callId: created.callId,
      requestId: "end-1"
    }));
    await endedAck;

    const carol = await create("Carol", "carol@ws.test");
    const carolInvitation = await request(app).post("/api/friends/invitations")
      .set("Authorization", `Bearer ${alice.tokens.accessToken}`)
      .send({ userId: carol.user.id });
    await request(app).post(`/api/friends/invitations/${carolInvitation.body.invitation.id}/accept`)
      .set("Authorization", `Bearer ${carol.tokens.accessToken}`);

    const offlineCreated = nextMessage(aliceSocket, "call.created");
    const unavailable = nextMessage(aliceSocket, "peer.unavailable");
    aliceSocket.send(JSON.stringify({
      type: "call.invite",
      to: carol.user.id,
      requestId: "offline-invite"
    }));
    await expect(offlineCreated).resolves.toMatchObject({
      requestId: "offline-invite",
      delivered: false
    });
    await expect(unavailable).resolves.toMatchObject({ userId: carol.user.id });
  });

  it("fails an undelivered acceptance without locking either participant", async () => {
    const config = loadConfig({ NODE_ENV: "test", JWT_SECRET: "test-secret-with-more-than-thirty-two-characters" });
    const store = new MemoryStore();
    const tokens = new TokenService(config, store);
    const calls = new CallService(store);
    const presence = { isOnline: (_id: string) => false };
    const app = createApp({ config, store, tokens, calls, presence });
    const server = createServer(app);
    const hub = new SignalingHub(store, tokens, calls);
    presence.isOnline = (id) => hub.isOnline(id);
    server.on("upgrade", (req, socket, head) => { void hub.handleUpgrade(req, socket, head); });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    resources.push(() => { hub.close(); server.close(); });
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("No test server address");

    const create = async (name: string, email: string) => (
      await request(app).post("/api/auth/register").send({ name, email, password: "a-secure-password" })
    ).body;
    const alice = await create("Alice", "alice-undelivered@ws.test");
    const bob = await create("Bob", "bob-undelivered@ws.test");
    const invitation = await request(app).post("/api/friends/invitations")
      .set("Authorization", `Bearer ${alice.tokens.accessToken}`)
      .send({ userId: bob.user.id });
    await request(app).post(`/api/friends/invitations/${invitation.body.invitation.id}/accept`)
      .set("Authorization", `Bearer ${bob.tokens.accessToken}`);

    const aliceSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${alice.tokens.accessToken}`);
    const bobSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${bob.tokens.accessToken}`);
    resources.push(() => { aliceSocket.close(); bobSocket.close(); });
    await Promise.all([nextMessage(aliceSocket, "ready"), nextMessage(bobSocket, "ready")]);

    const incomingPromise = nextMessage(bobSocket, "call.incoming");
    const createdPromise = nextMessage(aliceSocket, "call.created");
    aliceSocket.send(JSON.stringify({ type: "call.invite", to: bob.user.id, requestId: "invite-before-disconnect" }));
    const [incoming, created] = await Promise.all([incomingPromise, createdPromise]);
    expect(created).toMatchObject({ requestId: "invite-before-disconnect", delivered: true });
    expect(incoming.callId).toBe(created.callId);

    const offlinePresence = nextMessage(
      bobSocket,
      "presence",
      (message) => message.userId === alice.user.id && message.online === false
    );
    const aliceClosed = new Promise<void>((resolve) => aliceSocket.once("close", () => resolve()));
    aliceSocket.close();
    await Promise.all([aliceClosed, offlinePresence]);

    const acceptAck = nextMessage(bobSocket, "ack");
    bobSocket.send(JSON.stringify({
      type: "call.accept",
      callId: created.callId,
      requestId: "accept-undelivered"
    }));
    await expect(acceptAck).resolves.toMatchObject({
      action: "call.accept",
      callId: created.callId,
      requestId: "accept-undelivered",
      delivered: false
    });

    const failedCall = (await store.read()).calls.find((call) => call.id === created.callId);
    expect(failedCall).toMatchObject({ status: "failed", receiverId: bob.user.id });

    const retryError = nextMessage(bobSocket, "error");
    bobSocket.send(JSON.stringify({
      type: "call.accept",
      callId: created.callId,
      requestId: "accept-after-failure"
    }));
    await expect(retryError).resolves.toMatchObject({
      code: "CALL_NOT_RINGING",
      requestId: "accept-after-failure"
    });

    const terminalDeclineAck = nextMessage(bobSocket, "ack");
    bobSocket.send(JSON.stringify({
      type: "call.decline",
      callId: created.callId,
      requestId: "decline-after-failure"
    }));
    await expect(terminalDeclineAck).resolves.toMatchObject({
      action: "call.decline",
      callId: created.callId,
      requestId: "decline-after-failure"
    });
    expect((await store.read()).calls.find((call) => call.id === created.callId)?.status).toBe("failed");

    const reconnectedAlice = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${alice.tokens.accessToken}`);
    resources.push(() => reconnectedAlice.close());
    await nextMessage(reconnectedAlice, "ready");

    const nextIncoming = nextMessage(bobSocket, "call.incoming");
    const nextCreated = nextMessage(reconnectedAlice, "call.created");
    reconnectedAlice.send(JSON.stringify({
      type: "call.invite",
      to: bob.user.id,
      requestId: "invite-after-failure"
    }));
    const [secondIncoming, secondCreated] = await Promise.all([nextIncoming, nextCreated]);
    expect(secondCreated).toMatchObject({ requestId: "invite-after-failure", delivered: true });
    expect(secondCreated.callId).not.toBe(created.callId);
    expect(secondIncoming.callId).toBe(secondCreated.callId);

    const declineNotice = nextMessage(reconnectedAlice, "call.decline");
    const declineAck = nextMessage(bobSocket, "ack");
    bobSocket.send(JSON.stringify({
      type: "call.decline",
      callId: secondCreated.callId,
      requestId: "decline-next-call"
    }));
    await expect(declineNotice).resolves.toMatchObject({ callId: secondCreated.callId, from: bob.user.id });
    await expect(declineAck).resolves.toMatchObject({
      action: "call.decline",
      callId: secondCreated.callId,
      requestId: "decline-next-call"
    });
  });
});
