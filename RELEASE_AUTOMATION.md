# Automatic APK builds for No-Limit-Music

The Android source is in `android/`, on the `main` branch. GitHub Actions runs independently of the phone and ChatGPT; it continues when the user changes apps or locks the screen.

## Pipelines
- `.github/workflows/android-ci.yml` assembles a **DEBUG** APK for compile verification. Do **not** distribute this as a production update.
- `.github/workflows/android-release.yml` automatically runs for Android code and release-configuration changes pushed to `main`, and can also be started manually with **Run workflow**. It only accesses the signing secret on trusted `main` runs.
- A successful workflow status **does not** necessarily mean a signed APK exists. With an absent signing secret, the release build is intentionally **skipped** and a warning is emitted.

## One-time setup by repository owner
1. Obtain the private **encryption recovery passphrase** stored separately in `No-Limit-Music-SIGNING-RECOVERY-SECRET.txt`. Never commit or paste it into an issue or chat.
2. Open GitHub repository **Settings → Secrets and variables → Actions → New repository secret**.
3. Create repository secret `NLM_SIGNING_BACKUP_PASSPHRASE` containing only that passphrase.
4. Visit **Actions → Android signed release APK → Run workflow**, select `main`.
5. Open the successful run and download the `No-Limit-Music-signed-release` artifact. The ZIP includes a signed APK and `SHA256SUMS.txt`.

## Safety checks
The job decrypts the existing encrypted keystore in a temporary directory, never generates a substitute certificate, verifies the Android package is `il.nolimits.music`, verifies the APK certificate SHA-256 is `a6b2d7f54643caae83dfac40ac9349c98767deabf2aede778e4500fe2674f643`, and generates a file checksum. The signing key and its password are not uploaded to artifacts. See `SIGNING_POLICY.md` for recovery details.

This is a **build pipeline**, not evidence that a release is functionally ready. The feature branches for Android 1.4–1.7 are not merged into `main`, and YouTube song recording, physical-device tests, and cloud deployment remain independent product blockers. Verify these before distributing any APK. The release pipeline does not automatically upload to Google Drive or create a GitHub Release.
