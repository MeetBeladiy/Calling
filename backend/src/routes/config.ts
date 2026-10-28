import { Router } from "express";
import { z } from "zod";
import type { AppConfig } from "../config.js";

type IceServer = {
  urls: string[];
  username?: string;
  credential?: string;
};

type IceConfiguration = {
  iceServers: IceServer[];
  relayReady: boolean;
  ttlSeconds: number;
};

const providerIceServerSchema = z.object({
  urls: z.union([z.string(), z.array(z.string())]),
  username: z.string().optional(),
  credential: z.string().optional()
});

const providerResponseSchema = z.union([
  z.array(providerIceServerSchema),
  z.object({
    iceServers: z.array(providerIceServerSchema),
    ttlSeconds: z.coerce.number().int().min(60).max(86_400).optional()
  })
]);

const supportedIceUrl = /^(?:stun|stuns|turn|turns):/i;
const turnUrl = /^(?:turn|turns):/i;

function normalizeIceServers(input: z.infer<typeof providerIceServerSchema>[]): IceServer[] {
  return input.flatMap((server) => {
    const urls = (Array.isArray(server.urls) ? server.urls : [server.urls])
      .map((url) => url.trim())
      .filter((url, index, all) => supportedIceUrl.test(url) && all.indexOf(url) === index);
    if (!urls.length) return [];
    const username = server.username?.trim();
    const credential = server.credential?.trim();
    const hasTurnUrl = urls.some((url) => turnUrl.test(url));
    // Authenticated TURN is required for a usable production relay. Keep STUN
    // entries, but never advertise a TURN URL that cannot allocate a relay.
    if (hasTurnUrl && (!username || !credential)) {
      const stunUrls = urls.filter((url) => !turnUrl.test(url));
      return stunUrls.length ? [{ urls: stunUrls }] : [];
    }
    return [{ urls, ...(username ? { username } : {}), ...(credential ? { credential } : {}) }];
  });
}

function hasUsableRelay(servers: IceServer[]): boolean {
  return servers.some((server) =>
    Boolean(server.username && server.credential && server.urls.some((url) => turnUrl.test(url)))
  );
}

export function createConfigRouter(config: AppConfig): Router {
  const router = Router();
  let dynamicCache: { configuration: IceConfiguration; expiresAt: number } | null = null;
  let pendingDynamicRequest: Promise<IceConfiguration> | null = null;

  const loadDynamicConfiguration = async (): Promise<IceConfiguration> => {
    if (!config.turnCredentialsUrl) return { iceServers: [], relayReady: false, ttlSeconds: 60 };
    const now = Date.now();
    if (dynamicCache && dynamicCache.expiresAt > now) return dynamicCache.configuration;
    if (pendingDynamicRequest) return pendingDynamicRequest;

    pendingDynamicRequest = (async () => {
      try {
        const response = await fetch(config.turnCredentialsUrl, {
          headers: { Accept: "application/json" },
          // The URL can contain a credential-scoped API key. Never forward it
          // through a provider redirect to a different host or plaintext URL.
          redirect: "error",
          signal: AbortSignal.timeout(5_000)
        });
        if (!response.ok) throw new Error(`TURN credentials provider returned ${response.status}`);
        const parsed = providerResponseSchema.parse(await response.json());
        const providerServers = Array.isArray(parsed) ? parsed : parsed.iceServers;
        const ttlSeconds = Array.isArray(parsed) ? 3_600 : (parsed.ttlSeconds ?? 3_600);
        const iceServers = normalizeIceServers(providerServers);
        const configuration = {
          iceServers,
          relayReady: hasUsableRelay(iceServers),
          ttlSeconds
        };
        const cacheSeconds = Math.max(30, Math.min(300, Math.floor(ttlSeconds / 2)));
        dynamicCache = { configuration, expiresAt: Date.now() + cacheSeconds * 1_000 };
        return configuration;
      } catch {
        const configuration = { iceServers: [], relayReady: false, ttlSeconds: 60 };
        // Brief negative caching prevents a failed provider from being hammered by
        // both peers repeatedly tapping Retry.
        dynamicCache = { configuration, expiresAt: Date.now() + 30_000 };
        return configuration;
      } finally {
        pendingDynamicRequest = null;
      }
    })();
    return pendingDynamicRequest;
  };

  router.get("/ice", async (_request, response) => {
    const iceServers: IceServer[] = [];
    if (config.stunUrls.length) iceServers.push({ urls: config.stunUrls });
    if (config.turnUrls.length && config.turnUsername.trim() && config.turnCredential.trim()) {
      iceServers.push({ urls: config.turnUrls, username: config.turnUsername, credential: config.turnCredential });
    }
    const dynamic = await loadDynamicConfiguration();
    iceServers.push(...dynamic.iceServers);
    response.json({
      iceServers,
      relayReady: hasUsableRelay(iceServers),
      ttlSeconds: config.turnCredentialsUrl ? dynamic.ttlSeconds : 3_600
    });
  });
  return router;
}
