# Setup and configuration

This guide defines the configuration contract for local, staging, and production environments. Prefer the checked-in example files and package/Gradle tasks in the repository whenever their names differ from the placeholders below.

## Prerequisites

- Android Studio and the Android SDK/JDK versions selected by the Gradle project.
- Two Android devices or emulators for end-to-end calling; physical devices are required for meaningful Telecom, mobile-network, Bluetooth, lock-screen, and OEM testing.
- Node.js 20 or newer and npm.
- A Firebase project with an Android app registered for push notifications.
- A reachable STUN/TURN service. TURN over TLS on port 443 is strongly recommended for restrictive networks.
- HTTPS/WSS certificates for non-local environments.

## Backend environment

The current MVP backend uses a JSON-file store behind a `Store` abstraction and an in-memory store for tests. This is convenient for local/single-instance evaluation, but it is not a production datastore: it does not provide multi-instance coordination, relational constraints, durable transactional guarantees, managed backup/restore, or shared rate-limit/presence state. Replace it with a production database and shared coordination layer before horizontal scaling.

From `backend/`:

```powershell
Copy-Item .env.example .env
npm install
npm run dev
```

Other checked-in commands are `npm run build`, `npm start`, `npm test`, and `npm run typecheck`. Generate `JWT_SECRET` as directed in `.env.example`; never use the example value outside disposable local development.

Never commit a populated environment file. The implementation may split these values by service; the contract should cover the following concerns:

| Variable | Purpose | Production rule |
|---|---|---|
| `NODE_ENV` | Runtime profile | `production` in production |
| `HOST`, `PORT` | HTTP/WebSocket listener; defaults are documented in `.env.example` | Set by the platform where possible |
| `JWT_SECRET` | Access/refresh-token signing | At least 32 random characters; use a secret store and rotation plan |
| `JWT_ISSUER` | Expected token issuer | Unique, stable production value |
| `ACCESS_TOKEN_TTL` | Access lifetime | Short, typically minutes |
| `REFRESH_TOKEN_TTL_DAYS` | Refresh lifetime in days | Bounded and revocable |
| `DATA_FILE` | JSON persistence path | Local/single-instance MVP only; persistent protected volume |
| `CORS_ORIGINS` | Allowed browser origins for any admin surface | Exact allow-list; never `*` with credentials |
| `TRUST_PROXY` | Trust the deployment proxy's forwarded client address | Enable only behind a correctly configured trusted proxy |
| `FCM_PROJECT_ID` | Reserved push adapter project identifier | Separate per environment; adapter is not provisioned automatically |
| `FCM_CLIENT_EMAIL` | Reserved push service identity | Secret store |
| `FCM_PRIVATE_KEY` | Reserved push private key | Secret store; preserve newlines correctly |
| `TURN_URLS` | Comma-separated TURN URIs | Include TLS relay endpoint |
| `TURN_USERNAME`, `TURN_CREDENTIAL` | Current static TURN credentials | Development boundary only; replace with short-lived server-issued credentials for production |
| `TURN_CREDENTIALS_URL` | Backend-only HTTPS endpoint returning an `iceServers` array | Preferred for managed TURN; may contain a credential-scoped API key and must never be committed or logged |
| `STUN_URLS` | STUN URIs exposed to clients | Environment-specific |

If Firebase uses workload identity or an attached service account, omit raw credential variables. Never place a service-account JSON file in the Android client.

The server exposes `GET /health`, REST under `/api`, and authenticated signaling at `/ws`. The implemented REST surface is:

- `/api/auth`: register, login, refresh, logout, and password-reset request.
- `/api/users`: current profile read/update, search, and user profile.
- `/api/friends`: list, invite, accept/reject, remove, block/unblock, and report.
- `/api/calls`: list/start/read/end call metadata.
- `/api/config/ice`: authenticated ICE server configuration.

The WebSocket accepts bearer authentication (header preferred; query token is supported by the current server) and validates `ping`, presence, invite, offer, answer, ICE candidate, accept, decline, reconnect, and end messages. Query-string tokens are more likely to leak through URL logging; production clients/edges should use authorization headers or a short-lived WebSocket ticket and redact URLs.

## Android configuration

1. The Android project requires JDK 17, uses `minSdk 26` and `compileSdk/targetSdk 36`, and builds the `com.drivercall` application. Its calling stack includes AndroidX Core-Telecom 1.0.1 and the WebRTC SDK.
2. Configure `CALLING_APP_BASE_URL` and `CALLING_APP_SIGNALING_URL` in user-level Gradle properties or on the Gradle command line. Production must use `https://` and `wss://`.
3. Prefer ICE configuration from the authenticated backend endpoint. The current optional Gradle keys `CALLING_APP_TURN_URL`, `CALLING_APP_TURN_USERNAME`, and `CALLING_APP_TURN_CREDENTIAL` must not contain long-lived production secrets because APK values are extractable.
4. `CALLING_APP_REQUIRE_TURN` defaults to `true`, so a global build fails call setup clearly when the backend has no authenticated `turn:` or `turns:` relay. Set it to `false` only for deliberate local/LAN development.
5. Configure notification channel IDs, deep links, foreground-service types, application ID, versioning, and signing per environment.
6. Restrict backup/data-extraction rules so tokens, keys, and sensitive databases are excluded.
7. Use Android Keystore-backed encrypted storage for refresh/session tokens. Never store passwords.

Typical local addressing:

- Standard Android emulator to host computer: `10.0.2.2`.
- Physical phone to host computer: the computer's LAN address, with firewall access and a certificate the phone trusts.
- `localhost` on an Android device means the device itself, not the development computer.

The Android build and checked-in backend both default to port `8080`. Override both Gradle properties together when using a different host or port.

The checked-in build permits cleartext in debug for emulator development and disables it for release. Do not weaken the release manifest or trust all certificates.

## Firebase and incoming calls

- The repository provides Firebase Messaging/client and server adapter boundaries; actual Firebase project credentials and production push delivery still require deployment configuration.
- Create separate Firebase projects for development, staging, and production.
- Upload push tokens only after authentication; rotate/update them and delete them on logout/account deletion.
- Send only opaque call IDs and minimal display data. Do not put tokens, SDP, ICE candidates, customer numbers, or sensitive metadata in push payloads.
- Treat push as a wake-up hint. After receiving it, fetch the authorized current call state from the backend.
- Create the incoming-call and ongoing-call notification channels. Verify Android 13+ notification permission denial behaviour.
- Use high-priority push only for genuine time-sensitive incoming calls and comply with Firebase/Play policies.

## TURN configuration

STUN alone is insufficient for production. Operate TURN with authentication, UDP and TCP listeners, TLS on 443 where possible, appropriate relay port ranges/firewalls, quotas, abuse monitoring, and regional capacity. Generate short-lived credentials server-side. For a managed provider, store its credential-scoped HTTPS ICE endpoint in `TURN_CREDENTIALS_URL`; the backend validates, briefly caches, and proxies the returned configuration without exposing that provider URL to the APK. Verify `relayReady: true` from authenticated `/api/config/ice` and then verify relay-only calls during pre-release testing; a successful LAN/direct call does not prove TURN works.

## Local validation

1. Start the backend and verify `GET /health`.
2. Run `npm test`, `npm run typecheck`, and `npm run build` in `backend/`.
3. Run `gradlew.bat test assembleDebug` from the repository root, then install debug APKs on two devices.
4. Create two accounts and establish friendship.
5. Test an app call first on one Wi-Fi network, then force TURN/relay.
6. Background and lock the caller; verify notification/service continuity.
7. Switch Wi-Fi/mobile data and briefly remove all validated connectivity.
8. Place a customer call using a test number while parked; verify truthful suspension/restoration messaging.

## Production configuration gates

- TLS/WSS only, HSTS at the edge where applicable, no debug trust manager.
- Environment-isolated databases, Firebase projects, TURN credentials, analytics, and signing keys.
- Database backups and a tested restore procedure.
- Key/secret rotation runbook and incident contacts.
- Health, readiness, metrics, crash/ANR, push failure, TURN capacity, and signaling latency monitoring.
- Data-retention and account-deletion jobs enabled and tested.
- Server clocks synchronized because token expiry and time-limited TURN credentials depend on them.
