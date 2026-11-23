# MorinMusic Integrated Studio v0.4 preview

Implemented: Native Android cast editor, saved show lineups, original music player, official Google Identity OAuth scopes for real YouTube playlists, read-only import, optional explicit write permissions, playlist item CRUD and reorder, embedded YouTube iframe player. AI show runtime uses user-provided OpenRouter key with the free-model router, a generic on-device text-to-speech voice, and speech recognition intent.

Required setup: Register Google Cloud project, enable YouTube Data API v3, configure consent screen, and Android OAuth client matching preview package il.nolimits.music.studio and signed APK SHA-1. Debug signing is ephemeral on GitHub Actions; stable signing must be established for real sign-in. No client secrets should be embedded. Production distribution may need Google OAuth verification.

AI setup: Create personal key at https://openrouter.ai/keys. Service free tier has rate and availability limits. Key stored using Android Keystore AES-GCM, never committed.

Current limits: YouTube OAuth not tested against real credentials, AI responses not tested with real API key, and application not tested on physical device. AI chat is not yet connected to the YouTube song queue. No cloned celebrity voices, no multi-character TTS voice variations, no automatic sound mixing. No YouTube offline downloading, ad bypass or forbidden background playback. App lists owned YouTube playlists using mine=true, and can edit only owned lists. Tokens are stored in RAM and may need re-authorization. Existing hardcoded playlists inside old player are not live account data.

Built only on the feature branch, leaving main and release signing untouched.