# MorinMusic Media Server (Node.js 20+)

This directory contains a runnable backend, not a deployed service. The original `chatgpt.site` host is not confirmed to run this backend.

Run locally or on a VPS (no npm dependencies):

```bash
export PORT=8787
export MORIN_MEDIA_DIR=/var/lib/morinmusic
export MORIN_UPLOAD_TOKEN="$(openssl rand -hex 32)"
node index.mjs
```

Install a TLS reverse proxy before exposing the server to the Internet. In the Android app, enter its HTTPS URL and the same upload token in **שרת המדיה שלנו**. The token stays encrypted in Android Keystore, not in the GitHub repository.

The API:
- `GET /health`: reports availability
- `GET /playlists.json`: playlist catalog readable by the legacy music player
- `GET /media/<id>.m4a` and `GET /media/<id>.mp4`: serves audio/video with byte-range support
- `POST /api/media/<id>/audio?title=...`: stream M4A to server, requires bearer token
- `POST /api/media/<id>/video?title=...`: stream MP4 to server, requires bearer token
- `GET /api/catalog`: private catalog, requires bearer token

For each YouTube video ID, two separately addressable media files may exist. `playlists.json` includes only tracks with audio. No transcoding happens server-side: Android creates .m4a and .mp4 first.

Run tests:

```bash
node test.mjs
```

The media catalog is atomically written after successful uploads. Data persists under MORIN_MEDIA_DIR. This is a first backend implementation; production still needs actual deployment, persistent HTTPS hosting and backups, multi-user access control, resumable uploads for very large files, a quota/rate limit, media format validation, and monitoring.

No YouTube OAuth or Google Cloud credentials are stored on this server.
