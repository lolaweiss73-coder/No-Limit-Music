# Production signing key (encrypted Git backup)

The production Android signing certificate is preserved in:
`aroma-release-key.p12.enc.b64`.

**This is not a plain signing key.** It is an AES-256-GCM authenticated
encryption of the production `.p12` keystore, using a scrypt-derived key
(N=32768, r=8, p=1) from the existing strong signing password.
The password, and the unencrypted private key, are never committed to Git.
The backup was checked against the original signing keystore byte-for-byte.

To restore outside GitHub, on a trusted computer with Python 3:
```sh
python -m pip install cryptography
export AROMA_KEYSTORE_PASSWORD='YOUR_PRIVATE_PASSWORD'
python signing/decrypt_signing_key.py --output ./release-signing.p12
```
Do not type the password into a GitHub issue, commit, public workflow,
or chat message. Prefer an environment variable populated from a
secrets manager, and delete temporary unencrypted files after signing.

To enable **automatic signing** in GitHub Actions, the repo owner must add
a repository Actions secret named `AROMA_KEYSTORE_PASSWORD`, using the same
password as their original production signing key. GitHub's connected tools
in this chat cannot configure repository secrets, and currently the existing
CI workflow still publishes an unsigned (non-installable) release build.
The app can be installed only after signing the output with the restored
key. Use the same key for all future releases so Android updates work.
