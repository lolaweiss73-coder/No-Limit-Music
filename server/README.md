# No Limit Music — cloud recording and public-playlist sync backend

This is a **standalone server component** for Android 1.6. It has not been
deployed or connected to the existing `chatgpt.site` website.

## Routes

- `GET /healthz` — service readiness and whether YouTube sync is configured
- `GET /api/library.json` — current entire catalog, including uploaded recordings
- `GET /api/library.json?sync=1` — authenticated fresh sync of all public playlists from `YOUTUBE_CHANNEL_ID`. Requires `YOUTUBE_API_KEY` (server-side only). Preserves existing hand-curated lists and hosted tracks.
- `PUT /api/recordings/{youtubeVideoId}.mp4` — authenticated complete MP4 upload, maximum 400MB. Private storage under `DATA_DIR/media`.
- `GET /api/recordings/{youtubeVideoId}.mp4` — authenticated streaming with byte range support for Media3.

All recording endpoints and force-sync need `Authorization: Bearer $UPLOAD_TOKEN`.
`UPLOAD_TOKEN` must be configured as a secret on the server and entered once in the Android app's cloud settings (never committed to Git).

## Operation

Requires Node.js 20+ and a **persistent private volume** for `DATA_DIR`. Configure
all variables in `.env.example` in your hosting platform's secret settings.

Install no dependencies. Run `node server/server.mjs` from repository root
(or `npm start` from `server/`). Expose port `PORT` over HTTPS with the hosting platform.

The Android app defaults to the existing public website. Until you actually
deploy this server and configure its HTTPS endpoint and access token in Android
settings, uploads cannot complete; the app retains its private staging file for retry.

## Security and release gaps

This is an owner-operated proof of concept: a single shared access token is
**not adequate for multi-user distribution**. Before general release, use
per-account authentication, per-user ownership/permission checks, upload
quotas, rate limits and a proper object-storage backend. Video recordings
consume substantial storage; Android currently encodes H.264/AAC MP4.
The existing website's HTML frontend remains unchanged. The website
must be integrated separately with this API before uploaded video can be
played directly from its browser interface. Recording success does not
guarantee Android allows capture of every source.

## MP4 video storage
The server now accepts authenticated `video/mp4` upload to
`PUT /api/recordings/{youtubeVideoId}.mp4`, stores it as MP4, and streams
it through `GET /api/recordings/{youtubeVideoId}.mp4` with HTTP Range support.
Android handles the optional local offline audio-only extraction. This
backend has not yet been deployed to the actual public music website.
