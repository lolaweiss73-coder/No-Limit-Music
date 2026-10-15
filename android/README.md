# מוזיקה ללא גבולות — Android source (1.7.0)

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

## 1.4.0 — user-approved screen and playback-audio recording (2026-10-14)
- A new button in the embedded player requests Android RECORD_AUDIO permission and MediaProjection full-screen permission for the selected entry. Android 14+ requires approval each new projection session.
- While recording, an Android foreground service records a temporary whole-display MP4 and (independently) eligible internal playback audio through AudioPlaybackCaptureConfiguration. It does not record audio through the microphone.
- Once consent has completed and both capture paths have started, the embedded player attempts to seek back to the start. The recording stops when the embedded player reports that the media ended, or manually via the in-app/notification Stop control.
- Captured PCM is screened for usable non-silent audio. Significant silent edges are trimmed, a standalone stereo WAV is written to app-specific local storage, and temporary screen video is deleted. The resulting WAV can be selected from the existing library on subsequent plays, including offline. Users can delete the local copy.
- Capture follows Android's standard playback permissions and policies. If the media player forbids capture, the file remains silent and the app reports failure. It cannot override protected players/surfaces or forcibly capture muted playback.
- Screen capture can include notifications, messages and other applications on the display during the active session. The temporary screen recording is not retained, but users should avoid displaying sensitive information while recording.
- Silence-trim logic is amplitude-based; it does not identify speech, ad breaks, or music segmentation. No microphone fallback.
- This is an initial implementation, not a production-tested feature. Physical-device testing must verify foreground service, screen capture, WebView playback, stop signaling, audio eligibility and WAV output.
- The production update signing key remains necessary to deliver an installable update over an existing app without data loss.

## 1.5.0 — automatic song capture and cloud-first storage (2026-10-14)
- On YouTube IFrame ready, the app automatically requests required Android capture permissions (no Record button). Android still shows the system capture-consent dialog for each new MediaProjection session.
- Upon successful capture start, playback seeks back to 0; the app stops capturing on IFrame 'ended' or when leaving. Screen-capture temporary video is deleted after creating the audio staging file.
- A new CloudUpload worker uploads the completed WAV to a configured authenticated HTTPS service. It reports success only after a successful server response. Failed uploads remain private and pending for retry.
- Offline recording retention is opt-in and disabled by default. Once a successful upload has been acknowledged, WAV staging is deleted unless the user checked the offline option.
- Settings allows optional offline copies and secure server address/access-token setup; there is no hardcoded service secret. Without deployed upload backend, recordings remain pending.
- The full playlist Refresh button requests a complete dynamic catalog, using a YouTube Data API based public-playlist sync backend when configured. The existing static website currently publishes only 27 playlists and does not discover new YouTube playlists by itself.
- Cloud recordings can stream in the native Media3 player using server-scoped authorization. The standalone Node server source is in /server; it is NOT yet deployed or integrated into the existing website UI.
- This branch needs Android on-device testing and production signing; a successful CI build does not validate actual playback capture or cloud persistence.

## Side-by-side QA builds

The Debug build adds the application ID suffix `.dev` and shows the launcher
label "מוזיקה ללא גבולות · בדיקה". It installs **beside** the existing
production-signed app, without uninstalling or overwriting its data.
This suffix applies ONLY to Debug; the Release applicationId remains
`il.nolimits.music`. Production upgrade still requires the original
release signing key. The two app installations have independent local data.

## 1.6.0 — video-first MP4 capture and optional audio-only offline
- The existing Android MediaProjection pipeline now combines temporary H.264 video with internally captured AAC audio and saves MP4 before cloud upload. The separate temporary screen recording and PCM audio are deleted after processing.
- Cloud storage remains video-with-audio (MP4) for every successful recording.
- Settings: "שמירה לאופליין" → No / Yes. When Yes: "שמירה כ־" → "וידאו עם שמע (MP4)" or "שמע בלבד (M4A)". Defaults to No.
- After confirmed cloud upload, Yes+Audio extracts an M4A audio track from the recorded MP4 (lossless stream copy, no audio re-encoding), then removes the local MP4. Yes+Video retains MP4. No removes the local recording.
- Any incomplete upload retains the MP4 privately for retry, regardless of offline preference; server acknowledgement is required before removing it.
- Added a Media3 PlayerView activity so video recordings (online or local) display pictures with sound. Audio-only M4A uses the original audio player.
- No server deployment or device capture verification has occurred; capture of YouTube WebView audio or video cannot be guaranteed on all Android devices.

## 1.7.0 — continuous listening and capture, single Android consent per session

The mixed playlist screen now plays existing self-hosted MP3s, uploaded MP4s
(with video), locally saved MP4/M4A, and YouTube iframe video tracks in order,
with in-session Next/Previous, shuffle and repeat-all controls. Existing
server-hosted playback in the primary Media3 player is preserved.

The new `CaptureSessionService` owns exactly one Android MediaProjection and
one VirtualDisplay for the entire **foreground** listening session. Each song
boundary rotates the recorder output Surface/temporary MP4 and PCM file
**without requesting a new MediaProjection token or calling
`createVirtualDisplay` again**. Segment finalization/CloudUpload happens
in a separate worker so playback can advance without waiting for upload.
A visible foreground notification and in-app Stop Recording control allow
ending recording independently of listening. The app keeps the screen awake
during live capture; Android may refuse video/audio capture depending on
source protections and device policy.

Clips are keyed by YouTube video ID. Tracks hosted on our server or already
recorded are captured only temporarily then discarded; they are not uploaded
again. The set of IDs already seen in a session also avoids duplicate uploads
when the same video appears multiple times. Audio/video MP4 formation remains
subject to successful Android playback capture. For newly captured material
the default is cloud-first; the 1.6 offline No / MP4 / M4A preference is
unchanged. If the server is not connected and offline saving is disabled,
playback still works, but recording does not start because there is no valid
upload destination. A failed upload keeps the source MP4 for later retry.

Playlist refresh retains stable playlist ID selection and matches the latest
`GET /api/library.json?sync=1` backend where configured. The backend
periodically discovers YouTube channel playlists when a server-side YouTube
Data API key and channel ID exist; a manual sync with missing credentials now
returns a failure rather than falsely reporting fresh results. **The live
`chatgpt.site` static site has NOT been connected to this server.**
Until it is connected, Android sees only the 27 playlists already published
by `playlists.json`. The user's two new public YouTube playlists cannot
yet be verified in the live library.

### Release gate / QA checklist
- [x] Android compile and signed debug APK via GitHub Actions.
- [x] Backend Node syntax and MP4 upload/range HTTP smoke test.
- [ ] Physical-device test of one consent covering multiple song transitions.
- [ ] Verify short transition gaps, synchronized picture/sound, skip behavior,
      shuffle, next/previous, stop recording while music continues.
- [ ] Deploy authenticated backend with durable private storage, YouTube API
      credentials/channel and integrate actual public site's data source.
- [ ] Verify that the two newly public playlists appear and existing playlists
      update correctly. Check that sync failures never erase cached lists.
- [ ] Production signing with the *existing* production signing key; debug APK
      intentionally uses the `.dev` package suffix and can coexist safely.

This is a **test build**, not a fully validated public release.
