# MorinMusic Studio | test build v0.3

This is an **offline-first Android studio/cast editor** on a separate branch and an isolated Android package. It preserves the existing No-Limit-Music main branch and its signing materials.

## Implemented in this build
- RTL Hebrew Studio launcher.
- Edit program name, topic, format (talk show / radio / panel / quiz / free), world rules and music-to-talk ratio.
- Add suggested figures from the user's proposed cast, or create fully custom characters.
- Give each character a role and personality notes; edit, reorder and remove them.
- Save, load, overwrite and delete multiple named casts using on-device SharedPreferences JSON.
- Preview a *static outline* for a show, clearly marked as not AI-generated.
- Link from Studio to the existing music player.
- Debug APK built by GitHub Actions in a separate application ID, with no production signing changes.

## Not yet implemented
- Actual AI-generated live dialogue or interactions between characters.
- Text-to-speech, distinct synthetic voices, microphone interruption, live mixing.
- YouTube Google OAuth account sign-in, importing owned/private playlists, bidirectional playlist sync.
- Ad-free/background/offline YouTube playback. The official embedded player has API and policy restrictions.
- A genuine offline/on-device model supporting this multi-character workload.

## Technical notes
- Code: android/app/src/main/java/il/nolimits/music/StudioActivity.java
- Studio preview workflow: .github/workflows/studio-preview.yml
- Studio data remains local; no cloud, API keys, user credentials, or advertising are used by the Studio editor.
- Existing bundled playlist catalog in the old player is legacy content, **not synced with the user's Google account**.
- Future AI provider architecture should use an interface so users can select local or remote models and avoid coupling to one vendor.
- For real public figure voices and protected fictional characters, production deployment requires rights review, disclosure and consent; the current application stores character names/ideas only.

## Priorities
1. Real YouTube account connection with user-granted OAuth scopes and read-only playlist import, then explicit permission for writes.
2. Engine adapter for a legitimate free inference option plus conversation state and turn-taking.
3. Speech synthesis (original voices), configurable host, interactive talk-show runtime, sound mixing/ducking.
4. Playlist edits/sync (owned playlists only), compliance/security, richer UI and testing.
