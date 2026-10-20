import type { IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import { URL } from "node:url";
import { WebSocket, WebSocketServer } from "ws";
import { z } from "zod";
import type { TokenService } from "../auth/tokens.js";
import type { CallService } from "../calls/service.js";
import { HttpError } from "../errors.js";
import type { Store } from "../store/store.js";
import type { PresenceReader } from "../routes/users.js";
import { areAcceptedFriends } from "../routes/friends.js";

const candidateSchema = z.object({
  candidate: z.string().max(4096),
  sdpMid: z.string().max(256).nullable().optional(),
  sdpMLineIndex: z.number().int().min(0).max(65535).nullable().optional(),
  usernameFragment: z.string().max(256).nullable().optional()
});

const clientMessageSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("ping"), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("presence"), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.invite"), to: z.string().uuid(), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.offer"), callId: z.string().uuid(), to: z.string().uuid(), sdp: z.string().min(1).max(131_072), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.answer"), callId: z.string().uuid(), to: z.string().uuid(), sdp: z.string().min(1).max(131_072), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("ice.candidate"), callId: z.string().uuid(), to: z.string().uuid(), candidate: candidateSchema, requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.accept"), callId: z.string().uuid(), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.decline"), callId: z.string().uuid(), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.reconnect"), callId: z.string().uuid(), to: z.string().uuid(), requestId: z.string().max(100).optional() }),
  z.object({ type: z.literal("call.end"), callId: z.string().uuid(), quality: z.enum(["excellent", "good", "weak", "unknown"]).optional(), requestId: z.string().max(100).optional() })
]);

type ClientMessage = z.infer<typeof clientMessageSchema>;
type Client = WebSocket & { userId: string; alive: boolean; windowStartedAt: number; messagesInWindow: number };

export class SignalingHub implements PresenceReader {
  private readonly wss = new WebSocketServer({ noServer: true, maxPayload: 150_000 });
  private readonly clients = new Map<string, Set<Client>>();
  private heartbeat?: NodeJS.Timeout;

  constructor(private readonly store: Store, private readonly tokens: TokenService, private readonly calls: CallService) {
    this.wss.on("connection", (socket: Client) => this.onConnection(socket));
    this.heartbeat = setInterval(() => this.checkHeartbeats(), 30_000);
    this.heartbeat.unref();
  }

  isOnline(userId: string): boolean {
    return (this.clients.get(userId)?.size ?? 0) > 0;
  }

  async handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer): Promise<void> {
    try {
      const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
      if (url.pathname !== "/ws") {
        socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }
      const header = request.headers.authorization;
      const headerToken = header?.toLowerCase().startsWith("bearer ") ? header.slice(7) : undefined;
      const token = headerToken ?? url.searchParams.get("token") ?? undefined;
      if (!token) throw new HttpError(401, "Authentication required");
      const claims = this.tokens.verifyAccessToken(token);
      const state = await this.store.read();
      const user = state.users.find((item) => item.id === claims.sub && !item.suspendedAt);
      if (!user) throw new HttpError(401, "Account unavailable");
      this.wss.handleUpgrade(request, socket, head, (webSocket) => {
        const client = webSocket as Client;
        client.userId = user.id;
        client.alive = true;
        client.windowStartedAt = Date.now();
        client.messagesInWindow = 0;
        this.wss.emit("connection", client, request);
      });
    } catch {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      socket.destroy();
    }
  }

  close(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    for (const sockets of this.clients.values()) for (const socket of sockets) socket.close(1001, "Server shutting down");
    this.wss.close();
  }

  private onConnection(socket: Client): void {
    const wasOnline = this.isOnline(socket.userId);
    const sockets = this.clients.get(socket.userId) ?? new Set<Client>();
    sockets.add(socket);
    this.clients.set(socket.userId, sockets);
    this.send(socket, { type: "ready", userId: socket.userId, connectedAt: new Date().toISOString() });
    if (!wasOnline) void this.broadcastPresence(socket.userId, true);

    socket.on("pong", () => { socket.alive = true; });
    socket.on("message", (raw, isBinary) => {
      if (isBinary) return this.sendError(socket, "UNSUPPORTED_MESSAGE", "Binary messages are not supported");
      void this.onMessage(socket, raw.toString()).catch((error: unknown) => {
        const message = error instanceof Error ? error.message : "Message failed";
        const code = error instanceof HttpError ? error.code : "SIGNALING_ERROR";
        this.sendError(socket, code, message);
      });
    });
    socket.on("close", () => {
      const userSockets = this.clients.get(socket.userId);
      userSockets?.delete(socket);
      if (!userSockets?.size) {
        this.clients.delete(socket.userId);
        void this.broadcastPresence(socket.userId, false);
      }
    });
  }

  private enforceMessageLimit(socket: Client): void {
    const now = Date.now();
    if (now - socket.windowStartedAt >= 60_000) {
      socket.windowStartedAt = now;
      socket.messagesInWindow = 0;
    }
    socket.messagesInWindow += 1;
    if (socket.messagesInWindow > 120) {
      socket.close(1008, "Message rate limit exceeded");
      throw new HttpError(429, "Message rate limit exceeded", "MESSAGE_RATE_LIMIT");
    }
  }

  private async onMessage(socket: Client, raw: string): Promise<void> {
    this.enforceMessageLimit(socket);
    let decoded: unknown;
    try { decoded = JSON.parse(raw); } catch { throw new HttpError(400, "Message must be valid JSON", "INVALID_JSON"); }
    const message = clientMessageSchema.parse(decoded);

    switch (message.type) {
      case "ping":
        this.send(socket, { type: "pong", requestId: message.requestId, at: new Date().toISOString() });
        return;
      case "presence":
        await this.sendPresenceSnapshot(socket, message.requestId);
        return;
      case "call.invite": {
        const call = await this.calls.startVoip(socket.userId, message.to);
        this.send(socket, { type: "call.created", callId: call.id, to: message.to, requestId: message.requestId });
        const delivered = this.sendTo(message.to, { type: "call.incoming", callId: call.id, from: socket.userId, startedAt: call.startedAt });
        if (!delivered) this.send(socket, { type: "peer.unavailable", callId: call.id, userId: message.to });
        return;
      }
      case "call.accept": {
        const call = await this.calls.answer(message.callId, socket.userId);
        this.sendTo(call.callerId, { type: "call.accept", callId: call.id, from: socket.userId, answeredAt: call.answeredAt });
        this.send(socket, { type: "ack", action: message.type, callId: call.id, requestId: message.requestId });
        return;
      }
      case "call.decline": {
        const call = await this.calls.getParticipantCall(message.callId, socket.userId);
        if (call.receiverId !== socket.userId || call.status !== "ringing") throw new HttpError(409, "Call is no longer ringing", "CALL_NOT_RINGING");
        await this.calls.end(call.id, socket.userId, "declined");
        this.sendTo(call.callerId, { type: "call.decline", callId: call.id, from: socket.userId });
        this.send(socket, { type: "ack", action: message.type, callId: call.id, requestId: message.requestId });
        return;
      }
      case "call.offer":
      case "call.answer":
      case "ice.candidate":
      case "call.reconnect":
        await this.relaySignal(socket, message);
        return;
      case "call.end": {
        const call = await this.calls.end(message.callId, socket.userId, "ended", message.quality);
        const peerId = call.callerId === socket.userId ? call.receiverId : call.callerId;
        if (peerId) this.sendTo(peerId, { type: "call.end", callId: call.id, from: socket.userId, endedAt: call.endedAt });
        this.send(socket, { type: "ack", action: message.type, callId: call.id, requestId: message.requestId });
      }
    }
  }

  private async relaySignal(socket: Client, message: Extract<ClientMessage, { type: "call.offer" | "call.answer" | "ice.candidate" | "call.reconnect" }>): Promise<void> {
    const call = await this.calls.getParticipantCall(message.callId, socket.userId);
    const peerId = call.callerId === socket.userId ? call.receiverId : call.callerId;
    if (!peerId || peerId !== message.to || !["ringing", "connected"].includes(call.status)) {
      throw new HttpError(403, "Signal target is not a participant in this active call", "INVALID_SIGNAL_TARGET");
    }
    if (message.type === "call.reconnect") await this.calls.reconnect(call.id, socket.userId);
    const outbound = { ...message, from: socket.userId } as Record<string, unknown>;
    delete outbound.to;
    delete outbound.requestId;
    const delivered = this.sendTo(peerId, outbound);
    this.send(socket, { type: "ack", action: message.type, callId: call.id, requestId: message.requestId, delivered });
  }

  private async sendPresenceSnapshot(socket: Client, requestId?: string): Promise<void> {
    const state = await this.store.read();
    const friendIds = state.friendships.filter((item) => item.status === "accepted" && [item.requesterId, item.addresseeId].includes(socket.userId))
      .map((item) => item.requesterId === socket.userId ? item.addresseeId : item.requesterId);
    this.send(socket, { type: "presence.snapshot", requestId, users: friendIds.map((userId) => ({ userId, online: this.isOnline(userId) })) });
  }

  private async broadcastPresence(userId: string, online: boolean): Promise<void> {
    const state = await this.store.read();
    const peers = state.users.filter((user) => areAcceptedFriends(state, userId, user.id)).map((user) => user.id);
    for (const peer of peers) this.sendTo(peer, { type: "presence", userId, online, at: new Date().toISOString() });
  }

  private sendTo(userId: string, payload: unknown): boolean {
    const sockets = this.clients.get(userId);
    if (!sockets?.size) return false;
    let delivered = false;
    for (const socket of sockets) {
      if (socket.readyState === WebSocket.OPEN) { this.send(socket, payload); delivered = true; }
    }
    return delivered;
  }

  private send(socket: WebSocket, payload: unknown): void {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
  }

  private sendError(socket: WebSocket, code: string, message: string): void {
    this.send(socket, { type: "error", code, message });
  }

  private checkHeartbeats(): void {
    for (const sockets of this.clients.values()) for (const socket of sockets) {
      if (!socket.alive) socket.terminate();
      else { socket.alive = false; socket.ping(); }
    }
  }
}
