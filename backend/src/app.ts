import cors from "cors";
import express from "express";
import rateLimit from "express-rate-limit";
import helmet from "helmet";
import { pinoHttp } from "pino-http";
import type { AppConfig } from "./config.js";
import type { Store } from "./store/store.js";
import type { TokenService } from "./auth/tokens.js";
import { authenticate } from "./auth/middleware.js";
import { errorHandler, HttpError } from "./errors.js";
import { createAuthRouter } from "./routes/auth.js";
import { createUsersRouter, type PresenceReader } from "./routes/users.js";
import { createFriendsRouter } from "./routes/friends.js";
import { createCallsRouter } from "./routes/calls.js";
import { createConfigRouter } from "./routes/config.js";
import type { CallService } from "./calls/service.js";

export function createApp(dependencies: {
  config: AppConfig;
  store: Store;
  tokens: TokenService;
  calls: CallService;
  presence: PresenceReader;
}) {
  const { config, store, tokens, calls, presence } = dependencies;
  const app = express();
  app.disable("x-powered-by");
  if (config.trustProxy) app.set("trust proxy", 1);
  app.use(pinoHttp({
    redact: ["req.headers.authorization", "req.body.password", "req.body.refreshToken"],
    autoLogging: config.env !== "test"
  }));
  app.use(helmet());
  app.use(cors({
    origin(origin, callback) {
      if (!origin || config.corsOrigins.includes(origin)) callback(null, true);
      else callback(new HttpError(403, "Origin is not allowed", "CORS_REJECTED"));
    },
    credentials: false
  }));
  app.use(express.json({ limit: "128kb" }));
  app.use(rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: "draft-7", legacyHeaders: false }));

  app.get("/health", (_request, response) => response.json({ status: "ok", service: "calling-app-backend", time: new Date().toISOString() }));
  app.use("/api/auth", createAuthRouter(store, tokens));

  const requireAuth = authenticate(tokens, store);
  app.use("/api/users", requireAuth, createUsersRouter(store, presence));
  app.use("/api/friends", requireAuth, createFriendsRouter(store, presence));
  app.use("/api/calls", requireAuth, createCallsRouter(store, calls));
  app.use("/api/config", requireAuth, createConfigRouter(config));

  app.use((_request, _response, next) => next(new HttpError(404, "Route not found", "NOT_FOUND")));
  app.use(errorHandler);
  return app;
}
