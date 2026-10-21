# Android APK signing policy — permanent build procedure

This repository stores **encrypted** recovery material for the Android app `il.nolimits.music`. Never commit any unencrypted signing keystore or passwords to this public repository.

## Current app: Music Without Limits (מוזיקה ללא גבולות)

- **Application ID:** `il.nolimits.music` (do not change on future updates).
- **Release signing key alias:** `nolimitmusic`.
- **Certificate SHA-256 (replacement v2 key):** `264eb910af0bc129a9847a68128c08199a13949af4001234e4044fdd24ae55fe`.
- **Encrypted recovery in Git:** [signing-backups/il.nolimits.music.v2.p12.encrypted.json](signing-backups/il.nolimits.music.v2.p12.encrypted.json).\n- **Prior v1 key:** kept in its own encrypted archive for historical recovery, not used to sign the 1.7.1 APK.
- **Private decryption passphrase:** NOT in Git. Stored in the owner's private Drive folder `No-Limit-Music-private-signing` as `No-Limit-Music-v2-SIGNING-RECOVERY-SECRET.txt`. CI automation needs an explicitly configured secret.
- **Secret name for CI:** `NLM_SIGNING_BACKUP_PASSPHRASE`.

### Replacement signing identity (October 2026)\n\nThe owner explicitly approved generating a NEW signing key for v1.7.1. The old app must be uninstalled before installing v1.7.1 (after backing up any local-only files). The v1.7.1 release was signed offline with the new key and verified under APK Signature Schemes v2 and v3. The private keystore and passphrase must never be committed or uploaded as an unencrypted artifact.\n\n### One-time GitHub secret setup

Go to repository **Settings → Secrets and variables → Actions → New repository secret**. Set the name to `NLM_SIGNING_BACKUP_PASSPHRASE` and paste **only the private encryption recovery passphrase** from `No-Limit-Music-v2-SIGNING-RECOVERY-SECRET.txt` (not the full file).

Only after this setup can GitHub Actions decrypt the archived replacement v2 signing identity to build the normal `il.nolimits.music` **release** package automatically. If this secret is missing, the build must NOT silently create another release key. Debug builds can still be compiled. Ensure secret access is restricted to trusted production builds.

### Manual recovery

Use Node.js 20+:

```bash
node tools/signing-backup.mjs decrypt \
  signing-backups/il.nolimits.music.v2.p12.encrypted.json \
  recovered-signing-archive.zip \
  /path/to/No-Limit-Music-v2-SIGNING-RECOVERY-SECRET.txt
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
