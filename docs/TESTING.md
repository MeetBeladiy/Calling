# Test strategy

## Evidence standard

Calling App combines WebRTC, connectivity, Android audio, Telecom, push, foreground execution, Bluetooth, and carrier behaviour. Most high-risk requirements cannot be proven by JVM tests or an emulator. Each release candidate needs automated results plus a signed physical-device matrix with device, Android version, OEM build, carrier/SIM mode, network path, Bluetooth device, app version, backend version, time, and observed result.

## Automated test layers

- **Domain unit tests:** exhaustive reducer transitions, stale/wrong call IDs, simultaneous events, retry/backoff boundaries, quality-band mapping, timer behaviour, and idempotent end/cleanup.
- **Property/concurrency tests:** shuffled network/WebRTC/Telecom event order never creates two active calls, negative durations, retries after terminal state, or cross-call callbacks.
- **Repository tests:** authentication refresh/revocation, friendship/block enforcement, call-history separation, minimal persistence, and redacted logging.
- **Backend tests:** schema validation, authorization on every signaling event, invite expiry, reconnect revision, idempotency, rate limits, block/report flows, push-token ownership, and account deletion.
- **Android instrumented tests:** permission grant/deny/don't-ask-again, notification actions, service lifecycle, process recreation, database migration, Compose accessibility semantics, and navigation restoration.
- **Protocol integration tests:** two clients negotiate direct and TURN-relayed calls, exchange trickle ICE, restart ICE, reconnect the WebSocket, reject stale revisions, and end exactly once.
- **Security tests:** dependency/static analysis, secret scanning, malformed/oversized WebSocket frames, auth bypass attempts, replay/stale events, brute-force controls, and log inspection.

Use virtual clocks and injected dispatchers for reducer/backoff tests. Tests must never depend on real sleeps or public STUN services.

## Network matrix

| Scenario | Action | Expected evidence |
|---|---|---|
| Wi-Fi → 5G/4G | Disable Wi-Fi during a stable call | Same logical call ID; reconnecting status if needed; ICE restart; no duplicate history row |
| Mobile → Wi-Fi | Join trusted Wi-Fi | Route changes; media resumes; quality recovers; no manual redial |
| 5G → 4G/3G where available | Move/force preferred network | Truthful transient status; bounded recovery |
| Internet → none | Disable all validated paths | No false “connected”; media stops; retries pause/back off; battery remains bounded |
| None → internet | Restore a path within grace window | Backend resume and ICE restart; one active session |
| Captive portal | Join Wi-Fi without validated internet | Treated as no internet, not usable Wi-Fi |
| High latency/jitter/loss | Apply controlled network impairment | Quality label degrades; call remains usable where possible; diagnostics match WebRTC stats |
| NAT requiring relay | Force relay-only ICE policy | TURN candidate selected; audio works; credential expires/refreshes correctly |
| Signaling disconnect only | Drop WebSocket without dropping media | Media may continue; signaling resumes with revision; hangup remains consistent |
| Backend restart | Restart one instance during call | Reconnect/resume or clear terminal error; no ghost presence |

Record setup time, selected candidate type, round-trip time, jitter, loss, concealment where available, reconnect duration/count, and end reason. Never copy raw SDP or IP addresses into shared test reports.

## Call and lifecycle matrix

| Scenario | Expected result |
|---|---|
| Outgoing/incoming accept, decline, cancel, timeout | Both clients converge on one terminal or connected state |
| Both users hang up simultaneously | Idempotent end and one history record per user |
| Incoming while app foreground/background/process absent/locked | Compliant notification; action validates current server state |
| App background/foreground and screen lock/unlock | Active call service/notification remains; UI reattaches to current state |
| Process killed by OS | No false live state; recovery or explicit ended state after authoritative check |
| Customer call while app call connected | Snapshot, safe audio suspension/focus handling, status visible to friend where supported, restoration attempt afterward |
| Customer call starts during app reconnection | Reducer serializes events and restores only the current call revision |
| Second mobile call/call waiting | Safe state; no audio interception; eventual restoration attempt |
| User cancels system dialer | App call remains/restores; no customer call falsely recorded as completed |
| Mobile-call state unavailable | Product explains limitation and offers an explicit return/retry path |
| Peer ends during customer call | No reconnection to ended call; terminal state shown on return |
| Mic/notification/contact/phone permission denied | Graceful reduced function; settings guidance only when necessary; no loop |
| Logout/account deletion during call | Call ends, service stops, tokens/data are cleared per policy |

## Audio-route matrix

Test earpiece, speaker, wired headset, Bluetooth earbuds, Bluetooth headset, and at least one car system where available. For each: connect before call; connect during call; disconnect during call; power off unexpectedly; reject route request; and transition through a customer call. Verify capture/playback, displayed route, mute, echo, and fallback. Android versions differ in whether modern communication-device APIs or legacy SCO behaviour applies, so record API level and selected route.

## Device matrix

At minimum, cover the minimum, middle, and latest target API levels plus current devices from Google, Samsung, and one additional OEM with aggressive battery management. Include small/large screens, gesture/three-button navigation, light/dark theme, TalkBack, large font/display size, and locale/time changes. Use at least two carriers or SIM profiles if the product launches across carriers, and test single-SIM plus dual-SIM selection where supported.

## Performance and endurance

- Measure cold incoming-call presentation and outgoing setup at the 50th/95th percentile.
- Run 1-hour and representative full-shift calls with route/network transitions; watch thermal state, battery, memory, CPU, wake locks, and data usage.
- Run repeated call cycles to find leaked PeerConnections, audio tracks, callbacks, services, database observers, or sockets.
- Verify retry budgets and timers remain correct through Doze, clock/time-zone change, and process recreation.
- Monitor Play pre-launch results, crash-free users, ANRs, foreground-service failures, and OEM-specific regressions.

## Manual release smoke test

1. Install the exact signed release candidate on clean devices.
2. Deny each optional/dangerous permission once, then grant it only in context.
3. Register two accounts, reset one password, add/block/unblock a friend, and test presence privacy.
4. Complete incoming/outgoing direct and relay calls; exercise mute and every available route.
5. Background, lock, open navigation, and return to the active call.
6. Run the network matrix's Wi-Fi/mobile/no-internet transitions.
7. Place and end a normal customer call while parked; inspect both participants' states.
8. Review history separation, diagnostics, privacy/terms, logout, and account deletion.
9. Inspect server/client logs and exported analytics for forbidden data.
10. Confirm services, microphone indicator, notifications, sockets, and retries stop after hangup/logout.

## Exit criteria

No open critical/high security or privacy issue; no reproducible crash or stuck foreground service in the acceptance path; all reducer/protocol tests pass; the physical-device matrix is signed off with documented exceptions; TURN capacity and fallback are proven; Data safety/permissions/foreground-service declarations match the binary; and rollback/incident procedures are ready.
