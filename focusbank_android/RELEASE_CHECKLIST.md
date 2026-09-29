# FocusBank Release Checklist

## Build

- Set a unique release keystore and keep it outside source control.
- Configure `signingConfigs.release` with environment-provided keystore values.
- Build the signed Android App Bundle with `./gradlew bundleRelease`.
- Test the signed build on a physical Android 8+ device and on Android 15/16 (edge-to-edge, targetSdk 36).

## Permissions and disclosures

- [x] Explain Accessibility usage before opening Android Accessibility settings (in-app disclosure dialog with consent, 1.5.0).
- Request notification permission on Android 13+ before scheduling reminders.
- [x] `QUERY_ALL_PACKAGES` removed; package visibility uses `<queries>` for launcher and home intents (1.5.0).
- Explain `SYSTEM_ALERT_WINDOW`, Usage Access, and foreground monitoring in the privacy policy.
- Provide a visible way to disable protection and revoke app selections.

## Verification

- Test task reminders while the app process is closed.
- Test missed deadlines and random-app penalties.
- Test rerolls and active penalty countdowns.
- Test Room migration from an existing install.
- Test JSON export/import and reset confirmation.
- Test accessibility service recovery after device restart.
