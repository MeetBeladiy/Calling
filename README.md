# Calling App

Calling App is an Android-first, audio-only calling product for delivery drivers. It keeps a one-to-one app call recoverable while the driver briefly places a normal mobile-network call to a customer. It uses its own WebRTC session; it does not control, inspect, or integrate with calls from WhatsApp, Messenger, Telegram, Instagram, or other third-party apps.

> **Safety and platform reality:** Calling App cannot guarantee simultaneous mobile and internet-call audio. Android, the phone manufacturer, the carrier, Bluetooth hardware, and current network coverage decide what is possible. The supported fallback is to preserve signaling when possible, suspend app audio, observe the mobile call through permitted Android APIs, then restore or reconnect the app call.

## Repository guide

The project is split into an Android client and a signaling backend.

- `app/` — Kotlin/Java 17, Jetpack Compose, Material 3, Hilt, Room, Retrofit, AndroidX Core-Telecom 1.0.1, WebRTC, Firebase Messaging, and Android platform adapters. It uses `minSdk 26` and `compileSdk/targetSdk 36`. The package/application ID is `com.drivercall`; the visible product name is Calling App.
- [`backend/`](backend/README.md) — Node 20+, TypeScript/ESM, Express 5 REST, `ws` signaling, Zod validation, JWT sessions, scrypt password hashes, presence, friendship/abuse controls, and call metadata.
- [`docs/PRODUCT.md`](docs/PRODUCT.md) — MVP scope, user journeys, and acceptance criteria.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components, trust boundaries, call-state machines, and Android limitations.
- [`docs/SETUP.md`](docs/SETUP.md) — local configuration, Firebase, STUN/TURN, and backend environment.
- [`docs/TESTING.md`](docs/TESTING.md) — automated tests, physical-device matrix, and failure-injection scenarios.
- [`docs/PRIVACY_AND_PERMISSIONS.md`](docs/PRIVACY_AND_PERMISSIONS.md) — data handling and permission rationale.
- [`docs/PLAY_RELEASE_CHECKLIST.md`](docs/PLAY_RELEASE_CHECKLIST.md) — security, policy, quality, and release gates.
- [`docs/GLOBAL_DEPLOYMENT.md`](docs/GLOBAL_DEPLOYMENT.md) — public backend, TURN, Firebase, and global APK deployment.

## Quick start

Prerequisites: Android Studio with the repository's required Android SDK/JDK, Node.js as declared by the backend, a Firebase project, and reachable STUN/TURN infrastructure for real calls.

1. For local Windows testing, double-click `Start-Calling-App-Backend.bat` and keep its window open. Alternatively, in `backend/`, copy `.env.example` to `.env`, replace `JWT_SECRET`, then run `npm install` and `npm run dev`. The default backend port is `8080`.
2. Set `CALLING_APP_BASE_URL=http://10.0.2.2:8080/` and `CALLING_APP_SIGNALING_URL=ws://10.0.2.2:8080/ws` in your user-level Gradle properties for the standard emulator. A physical phone normally needs the development computer's LAN address.
3. Run `gradlew.bat assembleDebug` on Windows, or open the repository in Android Studio, sync, and run the `app` configuration.
4. Use two devices/accounts for call tests. Configure a real TURN server before treating the result as production-like.

The full sequence, environment-variable contract, and production requirements are in [`docs/SETUP.md`](docs/SETUP.md).

## Current maturity

This repository is an MVP implementation and production-oriented architecture, not a certified production release. The Android project targets API 36. The backend's current JSON-file store is suitable for local/single-instance evaluation only; push delivery still needs a deployed FCM adapter/configuration; TURN must be provisioned externally; and the device/carrier behaviours below require physical-device evidence. These boundaries are intentionally documented rather than hidden behind a “production-ready” label.

## Definition of done for the MVP

A release candidate must demonstrate on supported physical Android devices that two users can establish an audio call; the call survives app switching through an active-call foreground service; network loss produces bounded reconnection attempts; Wi-Fi/mobile handover attempts an ICE restart; a customer call launches only through supported Telecom/phone intents; and app audio restoration is attempted after the mobile call ends. Bluetooth route changes, locked-screen behavior, notification actions, abuse controls, and privacy disclosures must also pass the matrix in [`docs/TESTING.md`](docs/TESTING.md).

Passing unit tests or showing the call UI is not evidence that these device-, carrier-, and network-dependent behaviours work. Firebase credentials/push delivery and TURN provisioning are external deployment responsibilities; empty placeholders do not provide those services. A Play release also requires the gates in [`docs/PLAY_RELEASE_CHECKLIST.md`](docs/PLAY_RELEASE_CHECKLIST.md).

## Non-goals

- Recording, transcribing, or intercepting any call.
- Accessing customer-call audio or third-party app audio.
- Root, accessibility-service automation, hidden APIs, or OEM-specific bypasses.
- Emergency calling or replacement of the device's default phone app.
- Video calling in the MVP.
- Guaranteeing a call when the device has no usable internet connection.

## Contributing safely

Never commit credentials, Firebase service-account keys, signing keys, user data, customer numbers, or production logs. Test cellular interruption only while parked or with a second tester; the app UI is not a substitute for safe-driving practices.
