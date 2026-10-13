# מוזיקה ללא גבולות — Android preview 1.0

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
