import { Router } from "express";
import type { AppConfig } from "../config.js";

export function createConfigRouter(config: AppConfig): Router {
  const router = Router();
  router.get("/ice", (_request, response) => {
    const iceServers: Array<{ urls: string[]; username?: string; credential?: string }> = [];
    if (config.stunUrls.length) iceServers.push({ urls: config.stunUrls });
    if (config.turnUrls.length) {
      iceServers.push({ urls: config.turnUrls, username: config.turnUsername, credential: config.turnCredential });
    }
    response.json({ iceServers, ttlSeconds: 3600 });
  });
  return router;
}
