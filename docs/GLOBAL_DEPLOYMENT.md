# Global deployment

The LAN APK is only a development build. A globally usable Calling App needs a public backend domain, TLS/WSS, persistent storage, TURN relays, Firebase Cloud Messaging, and a signed Android release.

## 1. Deploy the API and signaling service

The `backend/Dockerfile` is ready for a long-running Node/WebSocket service. Railway is the prepared deployment target because it supports Docker/Node services, public HTTPS domains, environment variables, health checks, and persistent volumes.

Create a Railway service from this repository with `backend` as its root directory, then:

1. Generate a public domain in **Settings → Networking**.
2. Set the health-check path to `/health`.
3. Attach a persistent volume at `/data`.
4. Add these service variables:

```text
NODE_ENV=production
HOST=0.0.0.0
JWT_SECRET=<at-least-48-random-bytes>
JWT_ISSUER=calling-app-production
ACCESS_TOKEN_TTL=15m
REFRESH_TOKEN_TTL_DAYS=30
DATA_FILE=/data/calling-app.json
TRUST_PROXY=true
CORS_ORIGINS=https://your-future-admin-domain.example
STUN_URLS=stun:stun.l.google.com:19302
# Preferred managed-provider option. This backend-only HTTPS URL returns the
# complete ICE server array and may contain a credential-scoped API key.
TURN_CREDENTIALS_URL=https://YOUR_TURN_PROVIDER/credentials?apiKey=YOUR_KEY

# Alternative static credential option:
TURN_URLS=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:443?transport=tcp
TURN_USERNAME=<turn-username>
TURN_CREDENTIAL=<turn-credential>
```

Generate `JWT_SECRET` in a password manager or with a cryptographically secure generator. Do not commit it. The JSON volume supports one production pilot instance only; migrate the `Store` abstraction to PostgreSQL and shared presence/rate limiting before running multiple replicas.

Verify:

```text
https://YOUR_DOMAIN/health
wss://YOUR_DOMAIN/ws
```

Railway provides automatic certificates for its public domains. Its current deployment documentation is at <https://docs.railway.com/guides/express>, public networking at <https://docs.railway.com/networking/public-networking>, and volumes at <https://docs.railway.com/volumes>.

## 2. Provision TURN

STUN alone is not reliable across carrier-grade NAT, office networks, and restrictive Wi-Fi. Use a managed TURN provider or deploy coturn on a public VM that permits UDP/TCP 3478, TLS 5349, and a bounded UDP relay range. Configure authentication, quotas, abuse monitoring, and TLS. Prefer short-lived credentials issued by the backend rather than permanent APK credentials.

Set either `TURN_CREDENTIALS_URL` or all three static TURN values in the backend service variables. The Android client fetches the authenticated ICE configuration from `/api/config/ice` when it creates a peer connection. The response must report `relayReady: true`; `/health` reports `mediaRelay: "configured"` only when a relay source is present, without claiming that an external provider is reachable or exposing credentials. A variable name with a blank value is not a configured relay.

The official coturn container documentation is at <https://github.com/coturn/coturn/blob/master/docker/coturn/README.md>.

## 3. Configure Firebase incoming-call delivery

1. Create a Firebase project and register Android application ID `com.drivercall`.
2. Download `google-services.json` into `app/google-services.json`.
3. Keep that file environment-specific. The build automatically enables the Google Services plugin when the file exists.
4. Configure server-side FCM credentials through the hosting secret store, never inside the APK.
5. Complete the backend push-token registration/sending adapter before claiming that incoming calls can wake a terminated app.

Firebase's Android setup is documented at <https://firebase.google.com/docs/android/setup> and Android message handling at <https://firebase.google.com/docs/cloud-messaging/android/receive-messages>.

## 4. Build the global APK

Replace `YOUR_DOMAIN` with the deployed public hostname:

```powershell
.\gradlew.bat `
  -PCALLING_APP_BASE_URL=https://YOUR_DOMAIN/ `
  -PCALLING_APP_SIGNALING_URL=wss://YOUR_DOMAIN/ws `
  -PCALLING_APP_REQUIRE_TURN=true `
  :app:assembleDebug
```

The production release must use an Android App Bundle, a protected upload key, Play App Signing, a release version code, and the privacy/permissions declarations in `PLAY_RELEASE_CHECKLIST.md`. Never ship `http://`, `ws://`, LAN IPs, static TURN secrets, or the debug signing key.

## 5. Validate from separate networks

Use two physical devices and accounts. Put one device on Wi-Fi and the other on mobile data. Confirm registration/login, friend invitation, WebSocket presence, direct and TURN-relayed calls, app backgrounding, locked-screen notifications, network handover, cellular-call hold/restore, and call teardown. A test where both phones share one Wi-Fi does not prove global readiness.
