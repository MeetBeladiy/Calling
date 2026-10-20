# Privacy, security, and permissions

This document is an engineering and product checklist, not legal advice. Before release, adapt the user-facing privacy policy and terms to the actual controller, countries, processors, retention periods, lawful bases, and deletion/support contacts.

## Data inventory and minimisation

| Data | Purpose | Storage guidance |
|---|---|---|
| Account ID, name, verified email/phone where enabled | Account and identity | Server database; retain while account is active and only as legally needed afterward |
| Password verifier | Authentication | Modern salted password hash server-side; never store plaintext or send it back to clients |
| Session/refresh token | Keep user signed in | Keystore-backed encrypted app storage; hashed/revocable server record where applicable |
| Friendship, blocks, reports | Social and safety controls | Server-side with audited restricted access |
| Presence/last active | Call availability | Opt-in/visibility controls; short retention for raw presence events |
| Push token and device binding | Incoming-call wake-up | Server-side; remove on logout, invalidation, and account deletion |
| Call metadata | History, reliability, abuse handling | Participants, timestamps, outcome, duration, reconnection count, coarse quality only; documented retention |
| WebRTC diagnostics | Reliability | Aggregate/coarse values; avoid full IPs, SDP, ICE credentials, and persistent device fingerprinting |
| Customer name/number/reference | Initiate a carrier call | Keep on device only for the immediate task unless the user explicitly saves it; do not add to app-call history/server logs by default |
| Contacts | User-selected contact | Query locally after explicit action; do not upload the address book |
| Microphone audio | Live app call | Process/transport in real time; never record or persist by default |

Media uses WebRTC DTLS-SRTP in transit. Signaling and APIs use TLS/WSS. Encryption labels in the store/privacy policy must accurately describe the deployed system, including any relay and metadata processing.

## Permission rationale

Request a dangerous permission only at the feature boundary, after a short explanation. The manifest should contain only permissions used by the exact shipped implementation and API levels.

| Permission/capability | Why it may be needed | Least-privilege behaviour |
|---|---|---|
| `INTERNET`, network state | Signaling, WebRTC, connectivity status | Normal permissions; do not infer precise location from networks |
| `RECORD_AUDIO` | Send the user's voice in an app call | Ask when starting/accepting the first call; no recording; stop capture immediately when inactive |
| `POST_NOTIFICATIONS` (Android 13+) | Incoming and ongoing call notifications | Ask with context; explain that denial can make incoming calls unreliable; app remains usable where possible |
| `FOREGROUND_SERVICE` and API-specific microphone/phone-call service types | Continue an active call in background | Start only for a genuine active/ringing call as allowed; persistent notification; stop promptly |
| `BLUETOOTH_CONNECT` (Android 12+) | Discover/select a connected communication device | Ask when the user chooses Bluetooth if required; no scanning/location collection unless essential |
| Legacy Bluetooth permissions on older API levels | Route audio on supported old devices | Bound with `maxSdkVersion`; do not request modern/legacy permissions unnecessarily |
| Scoped system contact picker (no `READ_CONTACTS`) | Let the user select one customer number | Android grants temporary access only to the selected contact; offer manual entry; never upload the address book |
| `CALL_PHONE` | Directly initiate a customer call with `ACTION_CALL` if the product truly needs one-tap calling | Prefer `ACTION_DIAL` when acceptable because the system asks the user to confirm; do not request merely for phone-number links |
| Phone-state/call-log/default-dialer capabilities | Observe carrier-call state on implementations with a legitimate supported basis | Do not request restricted Call Log/SMS permissions for convenience; degrade gracefully; document Play eligibility |
| Self-managed Telecom account/`ConnectionService` | Integrate Calling App's own calls with Android Telecom where implemented | It manages only the app's calls; disclose/register correctly; never claim access to carrier-call audio |
| Full-screen intent | Urgent incoming-call UI where policy allows | Genuine calls only; provide standard notification fallback; meet Play/API requirements |

Permissions the MVP should not request without a new reviewed feature include location, camera, storage/media, accessibility service, SMS, Call Log, device admin, overlay, or broad package visibility. The app must not request accessibility access to automate the dialer or third-party calling apps.

## Permission-denial experience

- Microphone denied: explain that audio calling cannot work; keep browsing/account features available and offer a deliberate retry/settings route.
- Notifications denied: explain incoming-call limitations without repeatedly prompting.
- Contact picker cancelled or unavailable: preserve manual number entry.
- Bluetooth denied: keep earpiece/speaker routes available where supported.
- Phone permission denied: fall back to the system dialer flow where possible.
- Unsupported call-state access: do not invent status; ask the user to return to the app, observe only legitimate audio/lifecycle signals, and provide **Reconnect**.

## User controls and rights

Provide in-app access to the privacy policy and terms, presence/last-active controls, block/report, logout, export/access request instructions where required, and account deletion. Account deletion must revoke sessions and push tokens, remove or irreversibly de-identify data according to the published retention schedule, and explain any fraud/legal hold exceptions.

## Logging and analytics

Use structured event names and opaque IDs. Redact authorization headers, cookies, passwords, reset links/codes, email/phone where not necessary, customer data, push tokens, SDP, ICE candidates/credentials, IP addresses, and message payloads. Production logs should be access-controlled, encrypted, retention-limited, and audited. Crash reports need a sanitisation test before enabling automatic collection.

No analytics or diagnostics may sample microphone audio. Obtain consent for non-essential analytics where required, and make the store Data safety declaration match every SDK, not only first-party code.

## Security baseline

- Current TLS, secure WebSockets, certificate validation, and no release cleartext traffic.
- Password hashing with a modern adaptive algorithm and server-side rate limiting.
- Short-lived access tokens, rotated/revocable refresh tokens, server-side authorization on every object/event.
- Android Keystore-backed token protection; no embedded backend, Firebase admin, signing, or TURN shared secrets.
- Short-lived TURN credentials and capacity/abuse controls.
- Dependency and secret scanning, signed release builds, restricted signing-key access, and a vulnerability response route.
- Database least privilege, encrypted backups, restore tests, admin MFA, and audit trails.

## User-facing accuracy

Do not claim “always connected,” “works without internet,” “never drops,” “keeps both calls active,” “end-to-end encrypted” beyond the implemented/audited meaning, or “anonymous” while account/call metadata is retained. Say that Calling App attempts to preserve and restore the app call and that results depend on Android, the device, the carrier, Bluetooth, and the network.
