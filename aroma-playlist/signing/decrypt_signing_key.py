#!/usr/bin/env python3
"""Restore Aroma Playlist's AES-256-GCM encrypted release signing key.

The decryption password must be supplied through the environment variable
AROMA_KEYSTORE_PASSWORD (e.g. a protected GitHub Actions repository secret).
No password or unencrypted private key should ever be committed to Git.
"""
from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path
import sys

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

MAGIC = b"AROMAKEY1"
AAD = b"AROMA-RELEASE-KEY-1"
ENCRYPTED = Path(__file__).with_name("aroma-release-key.p12.enc.b64")


def restore(encrypted: Path, destination: Path, password: str) -> None:
    if not password:
        raise ValueError("AROMA_KEYSTORE_PASSWORD must be configured")
    payload = base64.b64decode(encrypted.read_text(encoding="ascii").strip(), validate=True)
    if not payload.startswith(MAGIC) or len(payload) < len(MAGIC) + 16 + 12 + 16:
        raise ValueError("Unsupported signing backup format")
    salt_start = len(MAGIC)
    salt = payload[salt_start:salt_start + 16]
    nonce = payload[salt_start + 16:salt_start + 28]
    ciphertext = payload[salt_start + 28:]
    key = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(password.encode("utf-8"))
    plaintext = AESGCM(key).decrypt(nonce, ciphertext, AAD)

    # Private permissions before any key bytes are written to the destination.
    destination.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(destination, flags, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(plaintext)


def main() -> int:
    parser = argparse.ArgumentParser(description="Decrypt the Aroma Playlist release key")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    password = os.environ.get("AROMA_KEYSTORE_PASSWORD", "")
    try:
        restore(ENCRYPTED, args.output, password)
    except InvalidTag:
        print("Error: signing key backup could not be decrypted", file=sys.stderr)
        return 1
    except (OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    print("Signing key restored with restricted permissions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
