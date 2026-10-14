# Phase 4 — personal library with YouTube, Spotify and Apple Music
Status: architecture/acceptance specification, NOT implemented.

## User-facing behavior
- Every user starts with an empty **personal** playlist library. Another user's 27 YouTube playlists must never appear as their personal playlists.
- Top-level screen: "הספרייה שלי"; provider tabs: "הכול", "YouTube", "Spotify", "Apple Music" and a separate optional "גלה מוזיקה" area.
- Connection cards: "חבר YouTube", "חבר Spotify", "חבר Apple Music". Each uses provider authorization, never account passwords inside our app.
- On successful connection fetch all playlists owned/followed by that user **for which provider APIs and consent grant access**, across paginated endpoints. Do not limit to the first page.
- Label each playlist with its origin/service and original playlist ID. Keep per-account ownership, pagination and sync checkpoints.
- Provider lists may include public and private lists when explicitly authorized. No unauthorized cross-account access; never assume user ID from a public channel ID.
- When no playlists are returned, show a useful empty state: "אין עדיין פלייליסטים בחשבון הזה" and buttons "צור פלייליסט", "חבר שירות נוסף", "רענן". Do not silently substitute developer playlists.
- When a provider has playlists and another does not, only that provider's tab shows the empty state; the aggregated "הכול" combines actual connected account playlists.
- A user can connect more than one music service. Keep original source IDs and display duplicates clearly, optionally allow explicit cross-service matching. No silent duplication into a provider account.
- "רענן" checks list creation, deletion, title/order changes and track additions/removals, preserving legitimate user-created local lists and preferences.
- A user's offline choice remains "שמירה לאופליין? לא/כן", and Yes reveals "שמירה כ־ וידאו / שמע בלבד" for content that the app has rights and technical means to save.
- The online site video-first format can use a video player for video sources. Audio-only tracks display album artwork/cover with ordinary playback controls; no need to synthesize a movie file just to show static art.

## Provider adapters

| Provider | Authentication | Read user's playlists | Playback/display |
| --- | --- | --- | --- |
| YouTube | Google OAuth with YouTube readonly permission | YouTube Data API v3 `playlists.list(mine=true)` + paginated playlistItems | Embedded YouTube player with video; playlist metadata and access stay scoped to user |
| Spotify | Spotify OAuth Authorization Code with PKCE | `GET /me/playlists` with playlist-read scopes, paginate | Licensed Spotify playback/embedding; audio tracks show album artwork |
| Apple Music | MusicKit developer token + subscriber Music User Token | Apple Music API `/v1/me/library/playlists` and pagination | MusicKit authorized playback; album artwork for audio and music video only where offered |

Provider stream access and offline export are separate from metadata/list access; do not assume API playlist permission grants access to raw audio/video files.

## Privacy and multi-user backend contract

The current proof-of-concept Node service uses one `UPLOAD_TOKEN`, a global playlist JSON and globally keyed `{youtubeVideoId}.mp4` objects. **It must not be deployed as a multi-user production service.** A complete system requires:
- Session/account auth tied to stable internal user IDs, provider connection state and encrypted/revocable OAuth tokens.
- `user_id` and `provider_account_id` on imported playlists and sync jobs; per-user access-control checks on each request.
- Unique storage path `users/{userId}/recordings/{provider}/{providerItemId}` or content-addressed dedup with strict ACLs; never overwrite one user's recording with another.
- Pagination, sync jobs, error reporting, rate limiting, storage quotas and deletion/export controls.
- Source-specific players and user rights/permissions for playback/offline content. Never represent content as downloaded if the service merely returned metadata.

## Real operational prerequisites
1. Locate/access the publishing source for the existing `avi-music-player.lolaweiss73.chatgpt.site` frontend. Current GitHub repo contains only Android + standalone server code, not its site source.
2. Deploy an authenticated backend on a supported host with persistent private storage and HTTPS; wire the real site's API proxy/URL to it. Existing live `/api/library.json` currently returns 404.
3. For the public-playlist refresh step, set up YouTube Data API key and channel ID (or use user OAuth for individual account playlists).
4. Set up OAuth apps, redirect URIs, scopes and provider approval for each of YouTube, Spotify and Apple Music. Spotify development mode is limited to five permitted users with a Premium app owner; check provider requirements for public rollout.
5. Device-test Android MediaProjection consent and capture eligibility. Do not claim all protected playback audio/video can be recorded.

## Acceptance scenarios
- Developer adds two public playlists on YouTube; after configured sync they appear (without manually re-uploading every track).
- New user with no provider connected sees zero personal playlists, with explicit connect options.
- User connects an existing Spotify account with three playlists; exactly those accessible lists appear tagged Spotify, with cover art and no fake video content.
- User with no playlists on connected Apple Music sees an empty state in that provider only.
- Two users can connect accounts and save tracks without any cross-account data exposure.
- Offline preference No/Yes+Video/Yes+Audio remains the user's choice, independent of the provider and independent of online library access.
