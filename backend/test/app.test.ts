import request from "supertest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { TokenService } from "../src/auth/tokens.js";
import { CallService } from "../src/calls/service.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store/memory-store.js";

function testContext(overrides: NodeJS.ProcessEnv = {}) {
  const config = loadConfig({
    NODE_ENV: "test",
    JWT_SECRET: "test-secret-with-more-than-thirty-two-characters",
    CORS_ORIGINS: "http://localhost",
    ...overrides
  });
  const store = new MemoryStore();
  const tokens = new TokenService(config, store);
  const calls = new CallService(store);
  const app = createApp({ config, store, tokens, calls, presence: { isOnline: () => false } });
  return { app, store };
}

async function register(app: ReturnType<typeof createApp>, name: string, email: string) {
  const response = await request(app).post("/api/auth/register").send({ name, email, password: "a-secure-password" }).expect(201);
  return response.body as { user: { id: string }; tokens: { accessToken: string; refreshToken: string } };
}

describe("REST API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("registers, authenticates, rotates refresh tokens, and never stores plaintext passwords", async () => {
    const { app, store } = testContext();
    const account = await register(app, "Driver One", "driver@example.com");
    const state = await store.read();
    expect(state.users[0]?.passwordHash).toMatch(/^scrypt\$/);
    expect(state.users[0]?.passwordHash).not.toContain("a-secure-password");

    await request(app).post("/api/auth/login").send({ email: "driver@example.com", password: "wrong-password" }).expect(401);
    const login = await request(app).post("/api/auth/login").send({ email: "driver@example.com", password: "a-secure-password" }).expect(200);
    const refresh = await request(app).post("/api/auth/refresh").send({ refreshToken: login.body.tokens.refreshToken }).expect(200);
    expect(refresh.body.tokens.accessToken).toBeTypeOf("string");
    await request(app).post("/api/auth/refresh").send({ refreshToken: login.body.tokens.refreshToken }).expect(401);

    await request(app).get("/api/users/me").set("Authorization", `Bearer ${account.tokens.accessToken}`).expect(200);
  });

  it("supports invitations, friendship, blocking protection, and call history", async () => {
    const { app } = testContext();
    const alice = await register(app, "Alice Driver", "alice@example.com");
    const bob = await register(app, "Bob Friend", "bob@example.com");
    const authAlice = { Authorization: `Bearer ${alice.tokens.accessToken}` };
    const authBob = { Authorization: `Bearer ${bob.tokens.accessToken}` };

    const invitation = await request(app).post("/api/friends/invitations").set(authAlice).send({ userId: bob.user.id }).expect(201);
    await request(app).post(`/api/friends/invitations/${invitation.body.invitation.id}/accept`).set(authBob).expect(200);
    const call = await request(app).post("/api/calls").set(authAlice).send({ type: "voip", receiverId: bob.user.id }).expect(201);
    await request(app).post(`/api/calls/${call.body.call.id}/end`).set(authBob).send({ quality: "good" }).expect(200);
    const history = await request(app).get("/api/calls").set(authAlice).expect(200);
    expect(history.body.calls).toHaveLength(1);
    expect(history.body.calls[0]).toMatchObject({ status: "ended", quality: "good", receiverId: bob.user.id });

    await request(app).post("/api/friends/blocks").set(authAlice).send({ userId: bob.user.id }).expect(204);
    await request(app).post("/api/calls").set(authBob).send({ type: "voip", receiverId: alice.user.id }).expect(403);
  });

  it("does not persist customer phone numbers in call metadata", async () => {
    const { app, store } = testContext();
    const driver = await register(app, "Delivery Driver", "delivery@example.com");
    await request(app).post("/api/calls").set("Authorization", `Bearer ${driver.tokens.accessToken}`)
      .send({ type: "customer", deliveryReference: "DROP-42", phoneNumber: "+441234567890" }).expect(201);
    const state = await store.read();
    expect(JSON.stringify(state.calls)).not.toContain("441234567890");
    expect(state.calls[0]?.deliveryReference).toBe("DROP-42");
  });

  it("requires authentication and reports when no TURN relay is configured", async () => {
    const { app } = testContext();
    await request(app).get("/api/config/ice").expect(401);
    const account = await register(app, "Relay Tester", "relay-none@example.com");
    const response = await request(app)
      .get("/api/config/ice")
      .set("Authorization", `Bearer ${account.tokens.accessToken}`)
      .expect(200);

    expect(response.body.relayReady).toBe(false);
    expect(response.body.iceServers).toEqual([
      { urls: ["stun:stun.l.google.com:19302"] }
    ]);
    const health = await request(app).get("/health").expect(200);
    expect(health.body.mediaRelay).toBe("not_configured");
  });

  it("serves authenticated static TURN credentials without exposing configuration secrets in health", async () => {
    const { app } = testContext({
      TURN_URLS: "turn:relay.example.com:3478?transport=udp,turns:relay.example.com:443?transport=tcp",
      TURN_USERNAME: "relay-user",
      TURN_CREDENTIAL: "relay-password"
    });
    const account = await register(app, "Static Relay", "relay-static@example.com");
    const response = await request(app)
      .get("/api/config/ice")
      .set("Authorization", `Bearer ${account.tokens.accessToken}`)
      .expect(200);

    expect(response.body.relayReady).toBe(true);
    expect(response.body.iceServers).toContainEqual({
      urls: [
        "turn:relay.example.com:3478?transport=udp",
        "turns:relay.example.com:443?transport=tcp"
      ],
      username: "relay-user",
      credential: "relay-password"
    });
    const health = await request(app).get("/health").expect(200);
    expect(health.body).toMatchObject({ status: "ok", mediaRelay: "configured" });
    expect(JSON.stringify(health.body)).not.toContain("relay-password");
  });

  it("fetches, validates, and briefly caches dynamic TURN credentials", async () => {
    const provider = vi.fn(async () => new Response(JSON.stringify({
      iceServers: [
        { urls: "stun:provider.example.com:3478" },
        {
          urls: ["turn:provider.example.com:3478?transport=udp", "turns:provider.example.com:443?transport=tcp"],
          username: "temporary-user",
          credential: "temporary-password"
        }
      ],
      ttlSeconds: 600
    }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", provider);
    const { app } = testContext({
      TURN_CREDENTIALS_URL: "https://turn-provider.example.test/credentials?apiKey=server-secret"
    });
    const account = await register(app, "Dynamic Relay", "relay-dynamic@example.com");
    const auth = { Authorization: `Bearer ${account.tokens.accessToken}` };

    const first = await request(app).get("/api/config/ice").set(auth).expect(200);
    const second = await request(app).get("/api/config/ice").set(auth).expect(200);

    expect(provider).toHaveBeenCalledTimes(1);
    expect(first.body.relayReady).toBe(true);
    expect(first.body.ttlSeconds).toBe(600);
    expect(first.body.iceServers).toContainEqual({
      urls: ["turn:provider.example.com:3478?transport=udp", "turns:provider.example.com:443?transport=tcp"],
      username: "temporary-user",
      credential: "temporary-password"
    });
    expect(second.body).toEqual(first.body);
    expect(JSON.stringify(first.body)).not.toContain("server-secret");
  });
});
