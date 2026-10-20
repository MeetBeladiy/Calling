import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { TokenService } from "../src/auth/tokens.js";
import { CallService } from "../src/calls/service.js";
import { loadConfig } from "../src/config.js";
import { MemoryStore } from "../src/store/memory-store.js";

function testContext() {
  const config = loadConfig({
    NODE_ENV: "test",
    JWT_SECRET: "test-secret-with-more-than-thirty-two-characters",
    CORS_ORIGINS: "http://localhost"
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
});
