import type { NextFunction, Request, Response } from "express";
import type { User } from "../models.js";
import type { Store } from "../store/store.js";
import { HttpError } from "../errors.js";
import type { TokenService } from "./tokens.js";

export interface AuthenticatedRequest extends Request {
  user: User;
}

export function authenticate(tokens: TokenService, store: Store) {
  return async (request: Request, _response: Response, next: NextFunction) => {
    try {
      const [scheme, token] = request.headers.authorization?.split(" ") ?? [];
      if (scheme?.toLowerCase() !== "bearer" || !token) {
        throw new HttpError(401, "Bearer token required", "AUTH_REQUIRED");
      }
      const claims = tokens.verifyAccessToken(token);
      const state = await store.read();
      const user = state.users.find((candidate) => candidate.id === claims.sub);
      if (!user || user.suspendedAt) throw new HttpError(401, "Account unavailable", "ACCOUNT_UNAVAILABLE");
      user.lastActiveAt = new Date().toISOString();
      (request as AuthenticatedRequest).user = user;
      next();
    } catch (error) {
      next(error);
    }
  };
}
