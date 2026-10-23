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

function nextMessage(socket: WebSocket, type: string): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${type}`)), 3000);
    const listener = (data: WebSocket.RawData) => {
      const message = JSON.parse(data.toString()) as Record<string, unknown>;
      if (message.type === type) {
        clearTimeout(timeout);
        socket.off("message", listener);
        resolve(message);
      }
    };
    socket.on("message", listener);
  });
}

describe("WebSocket signaling", () => {
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

    const acceptedPromise = nextMessage(aliceSocket, "call.accept");
    bobSocket.send(JSON.stringify({ type: "call.accept", callId: created.callId }));
    await acceptedPromise;
    const offerPromise = nextMessage(bobSocket, "call.offer");
    aliceSocket.send(JSON.stringify({ type: "call.offer", callId: created.callId, to: bob.user.id, sdp: "v=0" }));
    const offer = await offerPromise;
    expect(offer).toMatchObject({ from: alice.user.id, callId: created.callId, sdp: "v=0" });
  });
});
