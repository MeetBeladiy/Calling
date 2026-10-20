# Google Play release checklist

Use this as a release gate for every production track. Play policies and target-API deadlines change; re-check the current Play Console and Android developer requirements before submission. As of 6 October 2026, ordinary new phone apps and updates must target Android 16/API 36; the checked-in project meets that target level, but every remaining gate below still applies.

## Product and quality

- [ ] Final app name, package/application ID, icon, screenshots, short/full descriptions, support URL, and contact details are approved.
- [ ] Store text describes restoration as best effort and contains no unsupported simultaneous-call or “works without internet” promise.
- [ ] The signed release candidate passes the automated suites and physical-device/carrier matrix in `TESTING.md`.
- [ ] Direct and forced-TURN calls, push wake-up, background/locked-screen use, network switching, customer-call interruption, and Bluetooth recovery are evidenced.
- [ ] Accessibility checks cover TalkBack, large text/display, colour contrast, touch targets, focus order, and non-colour state labels.
- [ ] Crash-free/ANR, battery, data, thermal, call-setup, TURN capacity, and signaling latency targets are defined and monitored.
- [ ] Offline, maintenance, incompatible-version, expired-session, denied-permission, and backend-degraded states are understandable.

## Android platform declarations

- [ ] Validate the current `compileSdk`/`targetSdk` 36 migration, 64-bit/native library support, App Bundle, version code/name, and the current Play target-API deadline.
- [ ] Manifest permissions exactly match shipped paths; obsolete/duplicate permissions are removed.
- [ ] Android 13+ notification permission and Android 14+ foreground-service types/permissions are implemented and tested.
- [ ] Foreground-service declaration/use case in Play Console matches the persistent active-call notification and actual runtime behaviour.
- [ ] Full-screen intent, self-managed Telecom/phone-account, exact alarm, battery exemption, Call Log/SMS, or other special access is absent unless essential, eligible, declared, and reviewed.
- [ ] If `CALL_PHONE` is retained, the in-app disclosure and Play declarations explain its core customer-call function; otherwise use the system dialer.
- [ ] Backup/data-extraction and network-security configs exclude sensitive data and contain no release cleartext/debug certificate trust.
- [ ] Release minification/resource shrinking and native debug-symbol/mapping uploads are configured; call-critical reflection/JNI rules are verified.
- [ ] Notification channels, app links, task/lock-screen behaviour, process recreation, and service cleanup work on supported API levels.

## Privacy and policy

- [ ] Public privacy policy is reachable without login and accurately lists controller/contact, data, purpose/legal basis, processors, transfers, retention, security, rights, and deletion.
- [ ] Terms, acceptable-use rules, reporting, blocking, and safety guidance are available in-app.
- [ ] Play Data safety answers were derived from the final binary and every third-party SDK; data collection, sharing, encryption, and deletion claims are accurate.
- [ ] Account deletion is available in-app and through the required web route; sessions/push tokens are revoked and retention exceptions are disclosed.
- [ ] Prominent disclosures appear before sensitive permission/data access and are separate from the privacy policy where Play requires it.
- [ ] Customer numbers, contact books, call audio, SDP/ICE secrets, tokens, and credentials are absent from analytics and production logs.
- [ ] No call recording, third-party call manipulation, accessibility automation, hidden API, root, or misleading background behaviour is present.
- [ ] Families/age targeting, ads, consent, analytics, and regional privacy obligations are reviewed for the actual audience and countries.
- [ ] User-generated identity/profile content has block/report/moderation and support processes.

## Security and operations

- [ ] Production secrets are injected from a secret manager; no secrets or service-account/signing keys exist in source or the APK.
- [ ] API/WSS/TURN use production certificates; TLS validation and cleartext blocking are tested.
- [ ] Authentication, refresh rotation/revocation, password reset, verification, object/event authorization, and rate limits pass abuse tests.
- [ ] Short-lived TURN credentials, relay quotas, regional capacity, alerts, and failover are ready.
- [ ] Database migrations have forward/rollback plans; encrypted backup restore has been tested.
- [ ] Monitoring covers API/WebSocket health, push failures, auth abuse, call setup/end reasons, TURN saturation, crashes/ANRs, and privacy-sensitive log access.
- [ ] Incident response, vulnerability contact, dependency/secret scan, penetration-test findings, key rotation, and emergency rollback/feature-disable procedures are complete.
- [ ] Staging and production Firebase, database, TURN, analytics, endpoints, and signing are isolated.

## Play Console rollout

- [ ] App signing and upload-key custody/recovery are documented; Play App Signing configuration is verified.
- [ ] Internal testing uses production-like release builds and licensed test accounts/data.
- [ ] Pre-launch report findings and policy warnings are triaged, including devices without telephony/Bluetooth.
- [ ] Closed/open test feedback, release notes, tester instructions, and support escalation are ready.
- [ ] Countries/carriers/devices are limited to the tested support scope; tablets/Chromebooks/non-telephony devices have a defined experience or are filtered appropriately.
- [ ] Review notes provide test credentials and explain the genuine calling, microphone, notification, foreground-service, Telecom, and customer-dial flow.
- [ ] A staged rollout with stop criteria is configured; the previous stable bundle/backend protocol remains supported during rollback.
- [ ] Post-release owner watches vitals, policy inbox, support, auth abuse, signaling/push/TURN dashboards, and privacy incidents throughout rollout.

## Final sign-off

Record the app version/commit, backend version, protocol version, environment, migration, test report, Data safety revision, privacy-policy revision, approvers, rollout percentage, and rollback owner. A checklist without linked evidence is not a release approval.

## Official references

- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Android foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Play foreground-service and full-screen-intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Play restrictions on sensitive permissions and APIs](https://support.google.com/googleplay/android-developer/answer/16558241)
