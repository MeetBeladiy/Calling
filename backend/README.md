# Calling App backend

Production-minded REST API and WebSocket signaling service for the Calling App Android client. It manages accounts, friend relationships, presence, call metadata and WebRTC negotiation. WebRTC audio remains peer-to-peer (or relayed through TURN); this service never receives or records call audio.

## Run locally

Requires Node.js 20 or newer.

```bash
npm install
cp .env.example .env
npm run dev
```

On Windows PowerShell, use `Copy-Item .env.example .env`. The default address is `http://localhost:8080`; health is available at `GET /health`.

```bash
npm run typecheck
npm test
npm run build
npm start
```

Data is written to `DATA_FILE` as an atomic JSON snapshot. The `Store` interface isolates persistence so PostgreSQL/DynamoDB can replace it without changing routes or signaling logic. The file store is appropriate for one development instance, not for horizontally scaled production deployments.

## Configuration

See `.env.example`. In production, set a random `JWT_SECRET` of at least 32 characters, serve only behind HTTPS/WSS, configure the correct `CORS_ORIGINS` and proxy setting, and supply a real TURN service. `STUN_URLS` and `TURN_URLS` are comma-separated. Static `TURN_USERNAME`/`TURN_CREDENTIAL` values are supported, while `TURN_CREDENTIALS_URL` is the preferred backend-only HTTPS endpoint for a managed provider's complete, rotating ICE-server array.

## Authentication

Passwords are hashed using Node's scrypt implementation with a unique random salt. Access tokens are short-lived signed JWTs. Opaque refresh tokens are stored only as SHA-256 digests and rotated atomically on every refresh. Send the access token as `Authorization: Bearer <token>` on protected REST requests.

### REST API

All request and response bodies are JSON unless the response is `204 No Content`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check |
| POST | `/api/auth/register` | Create account (`name`, `email`, `password`, optional `phone`) |
| POST | `/api/auth/login` | Sign in (`email`, `password`) |
| POST | `/api/auth/refresh` | Rotate a refresh token |
| POST | `/api/auth/logout` | Revoke a refresh token |
| POST | `/api/auth/password-reset/request` | Enumeration-safe reset request placeholder |
| GET/PATCH | `/api/users/me` | Read or update the current profile |
| GET | `/api/users/search?q=` | Search discoverable users |
| GET | `/api/users/:id` | Read a user profile |
| GET | `/api/friends` | List friends and pending invitations |
| POST | `/api/friends/invitations` | Invite `{ userId }` |
| POST | `/api/friends/invitations/:id/accept` | Accept an incoming invitation |
| POST | `/api/friends/invitations/:id/reject` | Reject an invitation |
| DELETE | `/api/friends/:userId` | Remove friendship/invitation |
| POST | `/api/friends/blocks` | Block `{ userId }` and remove friendship |
| DELETE | `/api/friends/blocks/:userId` | Unblock |
| POST | `/api/friends/reports` | Report abuse |
| GET | `/api/calls?limit=25&before=<ISO>` | Paginated call history |
| POST | `/api/calls` | Create VoIP/customer call metadata |
| GET | `/api/calls/:id` | Read participant call metadata |
| POST | `/api/calls/:id/end` | Finish a call and store quality summary |
| GET | `/api/config/ice` | Retrieve configured STUN/TURN servers |

For a VoIP record, post `{ "type": "voip", "receiverId": "uuid" }`. For a customer record, post `{ "type": "customer", "deliveryReference": "optional" }`. Customer phone numbers are intentionally ignored and never persisted.

## WebSocket signaling protocol

Connect to `wss://<host>/ws?token=<access JWT>`. An `Authorization: Bearer <token>` upgrade header is also accepted and is preferred where the client supports it because URLs may be logged by infrastructure. All frames are JSON text. The server validates size, schema, call membership, friendship/blocking rules, and applies a 120-message/minute connection limit.

Typical flow:

1. Caller sends `{"type":"call.invite","to":"USER_UUID","requestId":"optional"}`. Server replies `call.created`; callee receives `call.incoming`.
2. Callee sends `{"type":"call.accept","callId":"CALL_UUID"}` or `call.decline`.
3. Caller sends `call.offer`; callee sends `call.answer`; both exchange `ice.candidate` messages.
4. Either sends `call.reconnect` during an ICE restart or `call.end` on completion.

Client messages:

```json
{"type":"presence","requestId":"p1"}
{"type":"ping","requestId":"heartbeat-1"}
{"type":"call.invite","to":"USER_UUID"}
{"type":"call.accept","callId":"CALL_UUID"}
{"type":"call.decline","callId":"CALL_UUID"}
{"type":"call.offer","callId":"CALL_UUID","to":"USER_UUID","sdp":"..."}
{"type":"call.answer","callId":"CALL_UUID","to":"USER_UUID","sdp":"..."}
{"type":"ice.candidate","callId":"CALL_UUID","to":"USER_UUID","candidate":{"candidate":"...","sdpMid":"audio","sdpMLineIndex":0}}
{"type":"call.reconnect","callId":"CALL_UUID","to":"USER_UUID"}
{"type":"call.end","callId":"CALL_UUID","quality":"good"}
```

Relayed negotiation messages retain their `type`, `callId`, `sdp`/`candidate` and add `from`. Server-originated messages include `ready`, `ack`, `pong`, `call.created`, `call.incoming`, `peer.unavailable`, `presence`, `presence.snapshot`, and `error`. An error frame is `{ "type":"error", "code":"...", "message":"..." }`. Transport ping/pong heartbeats remove dead connections after roughly one minute.

The Android client may alternatively create the call first with `POST /api/calls`, then use the returned `call.id` directly in `call.offer`. `call.invite` combines record creation and incoming delivery.

## Production boundaries

- Run behind a TLS reverse proxy; plaintext HTTP/WS is for local development only.
- Replace `FileStore` with a shared database and presence/message broker before horizontal scaling.
- Deploy coturn and use expiring TURN credentials. Public STUN alone cannot connect all carrier-grade NAT combinations.
- Password-reset email and FCM incoming-call push delivery need provider adapters/credentials. WebSocket delivery works while connected, but cannot wake a terminated Android process.
- Add account verification, operational metrics, backups, retention rules, and an admin authorization role before public launch.
- In-memory rate limiting is per process. Use a Redis-backed limiter in a multi-instance deployment.

No cellular audio, customer number, SDP media, microphone recording, or private call audio is stored.
