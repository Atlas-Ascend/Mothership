# ASS-LIGHT Physical Android Device Receipt

This gate must be completed on a real Android phone. Emulator success does not verify speakers, rear flashlight hardware, locked-screen timing, or manufacturer battery management.

## Device

- Manufacturer/model:
- Android version:
- App version: 1.0.0-rc1
- APK SHA-256:
- Test date/time:
- Tester:

## Required checks

- [ ] APK downloads and installs without package or signature error.
- [ ] App launches and displays the SECA preflight.
- [ ] All twelve Test Gust sounds are audible and materially different.
- [ ] Volume remains app-local and does not mutate Android system volume.
- [ ] Every chaos profile produces events inside its documented interval and volume bounds.
- [ ] Active session remains visible in notifications.
- [ ] Notification SEAL PORTAL stops audio, timers, torch, wake lock, and service.
- [ ] In-app SEAL THE PORTAL performs the same cleanup.
- [ ] Manual flashlight works only after permission and turns off when the app leaves the foreground.
- [ ] Optional event pulse is brief, non-repeating, and approximately 180 ms.
- [ ] No flashlight path exists on devices without rear flash hardware.
- [ ] Session behavior is tested with the screen locked.
- [ ] OEM battery optimization behavior is recorded.
- [ ] No crash, stuck notification, stuck flashlight, or uncommanded restart occurs.

## Classification

- [ ] VERIFIED ON THIS DEVICE
- [ ] PARTIAL
- [ ] FAILED / REQUIRES REPAIR

Notes:
