# Aroma Playlist (Android)
Independent Android app to identify tracks continuously from the microphone and build an ordered playlist.

## Status and build
This is an **experimental alpha**. A GitHub Actions workflow builds an installable **debug-signed** APK. Song recognition and Android foreground-service behavior must be tested on a real device. The Shazam protocol is unofficial and may change without notice.

- Requires Android 7.0+ (API 24), microphone and Internet.
- Starts only by tapping **התחל האזנה**; foreground service shows a permanent notification and continues when the screen turns off.
- Captures 10-second microphone segments, processes a local Shazam-compatible fingerprint in Python (Chaquopy + NumPy), submits *fingerprints* to Shazam's unofficial recognition endpoint.
- Stores recognized title, artist, timestamp and link locally. Suppresses immediate consecutive duplicates for four minutes.
- Shares the session playlist as ordinary text, through Android's share sheet; this does **not** automatically create a YouTube playlist.
- Uses only a transient locally cached PCM segment during processing, then deletes it. Not intended for covert recording.

## Credits and caveats
The files in `app/src/main/python/legacy_shazamio` derive from [ShazamIO v0.6.0](https://github.com/shazamio/ShazamIO/tree/0.6.0), released under MIT (license included). The current ShazamIO package uses a Rust extension which is not available as a standard Android wheel; the historical Python fingerprint algorithm is used here instead. ShazamIO and this app are not endorsed by Apple or Shazam.

### Build locally
With Android SDK, JDK 17, Gradle 8.9, and Python 3.11:
```sh
gradle -p aroma-playlist :app:assembleDebug
```
CI workflow: `.github/workflows/aroma-playlist.yml` in the parent repository. Download the `aroma-playlist-debug-apk` artifact after a successful Actions run.
