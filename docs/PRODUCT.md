# Product specification

## Product promise

Calling App gives a delivery driver a simple, audio-only call with another Calling App user and makes that session recover gracefully around short customer calls and network changes. “Recover gracefully” means retaining the logical call and signaling where the platform permits, clearly showing interruption state, and reconnecting without asking the user to start a new friend call. It does **not** mean that two audio calls can always remain audible at once.

## Primary journey

1. The driver signs in and connects with a trusted friend.
2. Either person starts a Calling App audio call.
3. The driver enables Driver Mode and can move Calling App to the background while using navigation or a delivery app.
4. The driver enters or selects a customer number and confirms **Call customer**.
5. Calling App snapshots the logical call ID, peer, elapsed time, media/signaling state, and route. The app yields audio focus and suspends app audio if required.
6. Android opens a normal customer call using supported phone/Telecom behaviour. The customer's view contains no information about the app call.
7. When Android reports that the mobile call is over, Calling App reacquires audio focus, verifies connectivity, restores media or requests an ICE restart, and returns to the call screen.
8. The UI reports success, continued reconnection, or a clear terminal failure with a manual retry action.

## MVP scope

This is the product target and release contract, not a claim that every item is complete in the current checkout. Use the test matrix and release checklist—not screen presence—as the source of release readiness.

### Included

- Email/password account flow and secure session-token handling.
- Friend invitation, acceptance, removal, blocking, presence, and one-to-one calls.
- Audio-only WebRTC negotiation over authenticated secure signaling.
- Incoming-call notification with accept and decline actions.
- Active-call foreground service and background/locked-screen call controls where Android permits.
- Mute and supported speaker, earpiece, wired-headset, Bluetooth-headset, and car-audio routing.
- Connectivity observation, quality labels, ICE restart, bounded exponential-backoff reconnection, and manual retry.
- Customer number entry and optional contact selection with just-in-time permission.
- Normal mobile-network call launch, supported call-state observation, app-call suspension, and restoration attempt.
- Separate app-call history and ephemeral customer-call context.
- Driver Mode with large, high-contrast controls and minimal interaction.
- Privacy, terms, help, and advanced diagnostics screens.
- Server-side abuse rate limits, block enforcement, and report intake.

### Deferred

- Video, group calls, call recording, transcription, end-to-end identity verification, and emergency calling.
- An admin UI; operational metrics and restricted admin APIs may precede it.
- Google/Apple sign-in and iOS client.
- Delivery-app integrations or access to their private data.

## UX requirements

Driver Mode must keep the most important actions in predictable positions, use touch targets of at least 48 dp, support TalkBack and dynamic type, distinguish state by text/icon as well as colour, and avoid destructive one-tap actions except **End**. Customer calling requires a visible confirmation because it can incur carrier charges. No task should require interacting with the phone while driving.

User-facing call quality is deliberately compact: **Excellent**, **Good**, **Weak**, **Reconnecting**, or **No internet**. Latency, jitter, packet loss, route, network, codec, and reconnection count belong under Advanced Call Diagnostics.

## Acceptance criteria

The MVP is accepted only after the evidence is recorded for the devices and networks in the testing matrix:

1. Two accounts can ring, accept, talk, mute, change route, and end a one-to-one audio call.
2. The active call continues while the screen is locked and other ordinary apps are foregrounded, subject to the device's disclosed battery restrictions.
3. Wi-Fi/mobile transitions retain or restore the same logical call through ICE restart.
4. Temporary loss shows the correct state, retries within a bounded policy, and restores without creating a duplicate call.
5. Customer calling uses supported Android behaviour and never captures mobile-call audio.
6. The app detects the mobile-call lifecycle only with permissions/capabilities granted to the installed build and behaves safely when detection is unavailable.
7. App audio is suspended or preserved according to Android's focus decision and restored after the mobile call where possible.
8. Bluetooth connect/disconnect changes route without unnecessarily ending the call.
9. Incoming-call actions behave correctly in foreground, background, killed-process, and locked-screen tests allowed by the Android version.
10. Blocked users cannot ring or discover presence through the normal API; server rate limits constrain invitation and calling spam.
11. No passwords, bearer tokens, customer numbers, SDP, ICE credentials, push tokens, or private call metadata appear in production logs.
12. The privacy disclosure, Data safety form, account deletion route, permissions, and foreground-service declaration match the shipped build.

## Product metrics

Collect only pseudonymous, coarse operational measures needed to make calling reliable: call setup success, time to connect, unexpected end reason, reconnection count, quality bands, and crash/ANR rates. Never collect audio. Avoid full IP addresses, raw SDP, customer numbers, contact books, or message content. Document retention and obtain consent where local law requires it.

## Safety copy

Recommended first-run message:

> Set up calls before driving. Calling App may pause while you make a customer call and will try to reconnect afterward. Network, phone, Bluetooth, Android, and carrier restrictions can prevent automatic restoration.
