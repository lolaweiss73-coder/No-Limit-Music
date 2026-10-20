# Android APK signing policy — permanent build procedure

This repository stores **encrypted** recovery material for the Android app `il.nolimits.music`. Never commit any unencrypted signing keystore or passwords to this public repository.

## Current app: Music Without Limits (מוזיקה ללא גבולות)

- **Application ID:** `il.nolimits.music` (do not change on future updates).
- **Release signing key alias:** `nolimitmusic`.
- **Certificate SHA-256:** `a6b2d7f54643caae83dfac40ac9349c98767deabf2aede778e4500fe2674f643`.
- **Encrypted recovery in Git:** [signing-backups/il.nolimits.music.v1.p12.encrypted.json](signing-backups/il.nolimits.music.v1.p12.encrypted.json).
- **Private decryption passphrase:** NOT in Git. Stored separately in the owner's personal secure recovery file and requires explicit configuration as a GitHub Actions secret.
- **Secret name for CI:** `NLM_SIGNING_BACKUP_PASSPHRASE`.

### One-time GitHub secret setup

Go to repository **Settings → Secrets and variables → Actions → New repository secret**. Set the name to `NLM_SIGNING_BACKUP_PASSPHRASE` and paste **only the private encryption recovery passphrase** from `No-Limit-Music-SIGNING-RECOVERY-SECRET.txt` (not the full file).

Only after this setup can GitHub Actions decrypt the archived original signing identity to build the normal `il.nolimits.music` **release** package automatically. If this secret is missing, the build must NOT silently create another release key. Debug builds can still be compiled. Ensure secret access is restricted to trusted production builds.

### Manual recovery

Use Node.js 20+:

```bash
node tools/signing-backup.mjs decrypt \
  signing-backups/il.nolimits.music.v1.p12.encrypted.json \
  recovered-signing-archive.zip \
  /path/to/No-Limit-Music-SIGNING-RECOVERY-SECRET.txt
```

The decrypted ZIP contains the Android PKCS#12 keystore and its internal password file. Keep this ZIP private, remove temporary decrypted copies, and never publish either file as a GitHub Actions artifact or release attachment.

### New applications — mandatory repeatable procedure

1. **New application:** choose a unique Android `applicationId`, create a NEW release signing key and alias; use the same key for all updates **of that application**.
2. Immediately after the first successful signed APK, package the keystore and its password into a private ZIP and create an encrypted archive using `tools/signing-backup.mjs encrypt`. Commit **only** the encrypted JSON to that application's Git repository.
3. Save the separately generated private recovery secret in at least two secure places outside public Git (for example a private password manager plus an offline encrypted backup).
4. Configure a protected GitHub Actions secret containing the recovery passphrase. CI must decrypt the existing key **at build time**, verify the APK signature and `applicationId`, and release only the APK. CI must never generate a new signing identity when one already exists.
5. After each future APK, **verify** that the signature fingerprint still matches the app's recorded fingerprint, and the Android versionCode increased for updates; re-verify the encrypted Git backup exists and remains readable.
6. **Different application:** different signing key and recovery secret; never reuse an unrelated application's private key.
7. For Google Play, distinguish the **upload key** from the **Play App Signing key** if Google's app-signing service manages the latter.

## Hard safety rules

- Never commit plaintext `.p12`, `.jks`, `.keystore`, plaintext password or private recovery secret.
- Never upload an unencrypted keystore **even as a temporary GitHub Actions artifact**: public repositories' artifacts may be accessible to others.
- Never put passphrases in workflow logs, issue comments, pull requests, app source code, or the public repository.
- A GitHub backup is useless if the separate recovery secret is lost. Store that recovery secret safely.
- This is **signing continuity**, not proof that the app is ready for Play Store or certified free of security or copyright issues.
