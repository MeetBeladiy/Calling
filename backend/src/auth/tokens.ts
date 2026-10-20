import { createHash, randomBytes, randomUUID } from "node:crypto";
import jwt from "jsonwebtoken";
import type { AppConfig } from "../config.js";
import { HttpError } from "../errors.js";
import type { RefreshSession, User } from "../models.js";
import type { Store } from "../store/store.js";

interface AccessClaims extends jwt.JwtPayload {
  sub: string;
  type: "access";
}

const tokenHash = (token: string) => createHash("sha256").update(token).digest("hex");

export class TokenService {
  constructor(private readonly config: AppConfig, private readonly store: Store) {}

  issueAccessToken(user: User): string {
    return jwt.sign(
      { type: "access" },
      this.config.jwtSecret,
      {
        subject: user.id,
        issuer: this.config.jwtIssuer,
        expiresIn: this.config.accessTokenTtl as jwt.SignOptions["expiresIn"],
        algorithm: "HS256"
      }
    );
  }

  verifyAccessToken(token: string): AccessClaims {
    try {
      const claims = jwt.verify(token, this.config.jwtSecret, {
        issuer: this.config.jwtIssuer,
        algorithms: ["HS256"]
      });
      if (typeof claims === "string" || claims.type !== "access" || !claims.sub) throw new Error("invalid claims");
      return claims as AccessClaims;
    } catch {
      throw new HttpError(401, "Invalid or expired access token", "INVALID_TOKEN");
    }
  }

  async createSession(user: User): Promise<{ accessToken: string; refreshToken: string; expiresIn: string }> {
    const id = randomUUID();
    const refreshToken = `${id}.${randomBytes(48).toString("base64url")}`;
    const createdAt = new Date();
    const session: RefreshSession = {
      id,
      userId: user.id,
      tokenHash: tokenHash(refreshToken),
      createdAt: createdAt.toISOString(),
      expiresAt: new Date(createdAt.getTime() + this.config.refreshTokenTtlDays * 86_400_000).toISOString()
    };
    await this.store.mutate((state) => state.refreshSessions.push(session));
    return { accessToken: this.issueAccessToken(user), refreshToken, expiresIn: this.config.accessTokenTtl };
  }

  async rotate(refreshToken: string): Promise<{ accessToken: string; refreshToken: string; expiresIn: string }> {
    const sessionId = refreshToken.split(".", 1)[0];
    if (!sessionId) throw new HttpError(401, "Invalid refresh token", "INVALID_REFRESH_TOKEN");
    const replacementId = randomUUID();
    const replacementToken = `${replacementId}.${randomBytes(48).toString("base64url")}`;
    const now = new Date();
    const user = await this.store.mutate((state) => {
      // Validation and rotation are one transaction so concurrent replay cannot mint two sessions.
      const session = state.refreshSessions.find((item) => item.id === sessionId);
      if (!session || session.revokedAt || session.tokenHash !== tokenHash(refreshToken) || new Date(session.expiresAt) <= now) {
        throw new HttpError(401, "Invalid or expired refresh token", "INVALID_REFRESH_TOKEN");
      }
      const currentUser = state.users.find((item) => item.id === session.userId);
      if (!currentUser || currentUser.suspendedAt) throw new HttpError(401, "Account unavailable", "ACCOUNT_UNAVAILABLE");
      session.revokedAt = now.toISOString();
      session.replacedById = replacementId;
      state.refreshSessions.push({
        id: replacementId,
        userId: currentUser.id,
        tokenHash: tokenHash(replacementToken),
        createdAt: now.toISOString(),
        expiresAt: new Date(now.getTime() + this.config.refreshTokenTtlDays * 86_400_000).toISOString()
      });
      return structuredClone(currentUser);
    });
    return { accessToken: this.issueAccessToken(user), refreshToken: replacementToken, expiresIn: this.config.accessTokenTtl };
  }

  async revoke(refreshToken: string): Promise<void> {
    const hash = tokenHash(refreshToken);
    await this.store.mutate((state) => {
      const session = state.refreshSessions.find((item) => item.tokenHash === hash);
      if (session && !session.revokedAt) session.revokedAt = new Date().toISOString();
    });
  }
}
