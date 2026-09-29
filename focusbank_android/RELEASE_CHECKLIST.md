# FocusBank Release Checklist

## Build

- Create the upload keystore (command in `keystore.properties.example`) and keep it, with a backup, outside the repository.
- Copy `keystore.properties.example` to `keystore.properties` and fill it in (git-ignored), or set the `FOCUSBANK_*` environment variables.
- [x] `signingConfigs.release` reads those values; without them the release build is unsigned and Gradle warns.
- Build the signed Android App Bundle with `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- On first upload, enrol in Play App Signing (this key becomes the upload key).
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
