import { randomUUID } from "node:crypto";
import { Router } from "express";
import { rateLimit } from "express-rate-limit";
import { z } from "zod";
import type { TokenService } from "../auth/tokens.js";
import { hashPassword, verifyPassword } from "../auth/password.js";
import { asyncHandler, HttpError } from "../errors.js";
import { toAccountUser, type User } from "../models.js";
import type { Store } from "../store/store.js";

const credentialsSchema = z.object({
  email: z.string().trim().email().max(254).transform((value) => value.toLowerCase()),
  password: z.string().min(10).max(128)
});

const registerSchema = credentialsSchema.extend({
  name: z.string().trim().min(2).max(80),
  phone: z.string().trim().min(7).max(24).optional()
});

const refreshSchema = z.object({ refreshToken: z.string().min(40).max(512) });
const dummyHash = hashPassword("invalid-password-placeholder");

export function createAuthRouter(store: Store, tokens: TokenService): Router {
  const router = Router();
  const authLimiter = rateLimit({ windowMs: 15 * 60_000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false });

  router.post("/register", authLimiter, asyncHandler(async (request, response) => {
    const input = registerSchema.parse(request.body);
    const passwordHash = await hashPassword(input.password);
    const now = new Date().toISOString();
    const user: User = {
      id: randomUUID(),
      name: input.name,
      email: input.email,
      ...(input.phone ? { phone: input.phone } : {}),
      passwordHash,
      createdAt: now,
      lastActiveAt: now,
      shareLastActive: true
    };
    await store.mutate((state) => {
      if (state.users.some((candidate) => candidate.email === input.email)) {
        throw new HttpError(409, "An account already exists for this email", "EMAIL_IN_USE");
      }
      state.users.push(user);
    });
    response.status(201).json({ user: toAccountUser(user), tokens: await tokens.createSession(user) });
  }));

  router.post("/login", authLimiter, asyncHandler(async (request, response) => {
    const input = credentialsSchema.parse(request.body);
    const state = await store.read();
    const user = state.users.find((candidate) => candidate.email === input.email);
    // Always execute the expensive hash path to reduce account-enumeration timing signals.
    const valid = await verifyPassword(input.password, user?.passwordHash ?? await dummyHash);
    if (!user || !valid) throw new HttpError(401, "Email or password is incorrect", "INVALID_CREDENTIALS");
    if (user.suspendedAt) throw new HttpError(403, "Account is suspended", "ACCOUNT_SUSPENDED");
    response.json({ user: toAccountUser(user), tokens: await tokens.createSession(user) });
  }));

  router.post("/refresh", authLimiter, asyncHandler(async (request, response) => {
    const { refreshToken } = refreshSchema.parse(request.body);
    response.json({ tokens: await tokens.rotate(refreshToken) });
  }));

  router.post("/logout", asyncHandler(async (request, response) => {
    const { refreshToken } = refreshSchema.parse(request.body);
    await tokens.revoke(refreshToken);
    response.status(204).end();
  }));

  router.post("/password-reset/request", authLimiter, asyncHandler(async (request, response) => {
    z.object({ email: z.string().email() }).parse(request.body);
    // Deliberately identical response for known and unknown accounts. Plug an email provider into this boundary.
    response.status(202).json({ message: "If the account exists, reset instructions will be sent." });
  }));

  return router;
}
