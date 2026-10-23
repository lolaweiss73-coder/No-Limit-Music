# מוזיקה ללא גבולות — Android source (1.3.0)

Native Android application backed by the existing public music catalog at https://avi-music-player.lolaweiss73.chatgpt.site/ . The existing website is unchanged.

## Implemented
- Hebrew RTL native interface, 27 bundled playlists with 686 entries, searchable refreshed catalog.
- Media3 ExoPlayer hosted in MediaSessionService: playback lifecycle independent of the Activity, media notification and lock-screen / headset controls, audio focus and headphone-disconnect handling.
- Continuous server-audio queue, shuffle, repeat one/all, seeking; queue continues when browsing playlists.
- Explicit server-only ad-free filter, enabled by default. YouTube-only entries open externally and never enter the native audio queue.
- DownloadManager background downloads of existing server MP3s, completed-download verification, offline library and deletion. No broad storage permission.
- Bundled catalog and atomic local refresh cache; downloaded tracks work without network. Cache is app-specific and removed on uninstall.

## Build
JDK 17, Android SDK platform 35/build tools 35, Gradle 8.11.1.
Run `gradle :app:assembleDebug` from this directory, or use the generated wrapper.
The debug APK is an installation/testing build, not a Play Store release.
The signing key must be retained separately for future updates to this exact installed build.

## Limits and validation
The project compiles against SDK 35, targets Android 15, and supports Android 8+.
Build and APK signing verification passed; catalog matches the live server and a server audio byte-range request returned MP3 audio.
Physical Samsung A17 playback/lock-screen and download verification remain required; no device execution is claimed.
The catalog snapshot has 527 unique track IDs, of which 146 have server audio. External tracks are not promised ad-free/background playback.
Automatic YouTube import/acquisition, recording/splitting, user accounts, and lyrics display are not implemented in this Android preview. Existing site and server ingest endpoint are preserved.
Official implementation reference: https://developer.android.com/media/media3/session/background-playback

## 1.1.0 — opening clip (2026-10-05)
- IntroActivity is the launcher; app-owned local HTML embeds YouTube video nRfo1NdTN7k. It does not change the catalog, playlists, downloads, or service queue.
- Automatic playback attempt with YouTube controls, permanent native Skip button, and end-of-video transition to MainActivity. Back also skips.
- A per-video SharedPreferences checkbox is saved immediately on this device. Fresh launches respect it; restoring an in-progress introduction does not unexpectedly dismiss it.
- Settings provides immediate replay (independent of the preference) and a separate reset for the next fresh launch. Returning from replay preserves the existing main screen. Playback is paused before replay.
- Introduction pauses when leaving the screen. Resume preserves manually paused playback. Network/player errors display a message while Skip remains available; a 15-second loading hint does not interrupt a long video.
- App identity/referrer follows YouTube WebView guidance: https://developers.google.com/youtube/terms/required-minimum-functionality . Events follow https://developers.google.com/youtube/iframe_api_reference .
- The video streams from YouTube, requires internet, and is not an offline bundled MP4. YouTube controls video availability, embedding, autoplay restrictions, and advertisements.
- Validation: Java/Android debug build; signed APK certificate compared with version 1.0; isolated JavaScript event/lifecycle checks. No Android device/emulator is attached, so actual video playback and device UI require on-device verification. Android lint could not finish offline because its Kotlin stdlib 1.7.10 dependency was unavailable.
- Install this signed versionCode 2 APK over versionCode 1 to retain application data. Both use the existing development signing key, backed up separately; the source archive deliberately excludes signing secrets and machine-local build paths.

## 1.2.0 — in-app playback for YouTube-only tracks (2026-10-14)
- Added YouTubePlayerActivity and `youtube_player.html` using the official YouTube IFrame Player API. Selecting a YouTube-only track no longer opens an external browser.
- All 27 playlists and 686 listed tracks display by default; users can explicitly filter to existing server-hosted tracks.
- Server-hosted MP3 playback, download, background service, shuffle and repeat remain on Media3. YouTube-only video playback uses a separate in-app WebView with YouTube's own playback controls.
- Intro page no longer automatically launches an external browser. The embed origin matches the catalog site to provide API client identity.
- Same Android application ID `il.nolimits.music`; versionCode is 3.
- Verified a successful debug compile in GitHub Actions. This is not a signed production upgrade.
- Still to test: actual YouTube IFrame playback and autoplay restrictions on an Android phone, intro completion, playlist interaction, offline MP3s and downloads.
- YouTube-only media can still contain advertisements and impose playback restrictions. No downloading, recording, ad removal or server import from YouTube is implemented.
- The signing keystore used by the currently installed APK is required for an in-place update. Creating a new signing key would require uninstalling the older signed app.

## 1.3.0 — authorized first-play local saving
- For audio files served by the app's authorized MP3 server, playback now starts a parallel Android DownloadManager job on first play.
- Later playback uses the downloaded local file, if complete. The user can turn this feature off in settings or delete a saved track.
- A background MediaCodec job analyzes local audio for significant silence at its edges. Playback uses Media3 clipping bounds after analysis, without modifying the MP3.
- The heuristic only removes substantial silence, not speech or embedded introductions.
- YouTube IFrame playback is separate and does not provide local audio copies through this feature.
- Android versionCode 4 (1.3.0), application ID unchanged.
- Requires device tests and release signing before distribution.

## 1.3.1 — user-owned playlist downloads and LRC sidecars (pending device test)
- Authorized server-hosted MP3s now download to public `Downloads/NoLimitMusic/<playlist name>/` instead of the app-private directory, so downloads are retained after uninstall.
- Each filename includes the song title and a stable YouTube ID to avoid collisions; one copy per song ID is shared when the song belongs to multiple playlists, placed under the first playlist from which it was saved.
- First-play and manual downloads use the same destination. Legacy private downloads stay available to the app unless the app is removed, and new downloads do not migrate old files automatically.
- After download completion, the Android app checks `https://no-limit-music-sync.floot.app/_api/lyrics?youtubeId=<id>` and saves an approved `.lrc` next to the MP3 if lyrics exist. Timed and untimed lyric lines use the same LRC extension.
- The public lyrics registry currently contains **no lyrics**. Only original, public-domain or licensed content may be added.
- Android 10+ accesses saved public MP3s through DownloadManager content URIs for playback and silence analysis; Android 8/9 request the legacy storage permission.
- Android versionCode 5, versionName 1.3.1, application ID remains `il.nolimits.music`.
- GitHub CI compilation and real-device download/offline/uninstall tests are required. The signed release key remains separate and must not be changed.
