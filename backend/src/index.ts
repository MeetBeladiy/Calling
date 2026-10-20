import { createServer } from "node:http";
import { createApp } from "./app.js";
import { TokenService } from "./auth/tokens.js";
import { CallService } from "./calls/service.js";
import { loadConfig } from "./config.js";
import { SignalingHub } from "./signaling/hub.js";
import { FileStore } from "./store/file-store.js";

const config = loadConfig();
const store = await FileStore.open(config.dataFile);
const tokens = new TokenService(config, store);
const calls = new CallService(store);
const presencePlaceholder = { isOnline: (_userId: string) => false };
const app = createApp({ config, store, tokens, calls, presence: presencePlaceholder });
const server = createServer(app);
const signaling = new SignalingHub(store, tokens, calls);

// Routers retain the reader object, so delegate it after construction without a circular dependency.
presencePlaceholder.isOnline = (userId: string) => signaling.isOnline(userId);
server.on("upgrade", (request, socket, head) => {
  void signaling.handleUpgrade(request, socket, head);
});

server.listen(config.port, config.host, () => {
  console.log(`Calling App backend listening on http://${config.host}:${config.port}`);
});

const shutdown = () => {
  signaling.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10_000).unref();
};
process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
