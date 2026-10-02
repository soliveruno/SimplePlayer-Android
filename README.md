# SimplePlayer for Android

A simple music player with a built-in yt-dlp downloader.

## Features
- **Library**: search, play, shuffle, "play next", delete, import songs from your phone
- **Player**: mini player + full-screen player with cover art, seek bar, shuffle and repeat
- **Background playback** with lock-screen / notification controls; pauses when headphones unplug
- **Download**: paste a link (or Share → SimplePlayer from any app), choose MP3 or M4A, optional whole playlist
- Downloads keep running in the background with a progress notification; cancel and retry
- MP3s get the thumbnail embedded as square cover art, plus title/artist tags
- **Update yt-dlp** button inside the app, no rebuild needed

## Build the APK (no Android Studio needed)
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. The "Build APK" workflow runs on every push (or press **Run workflow**).
3. When it finishes (~5–8 min), download the **SimplePlayer-apk** artifact and unzip it.
4. Install `app-arm64-v8a-release.apk` (almost every phone from the last ~7 years).
   Use `app-armeabi-v7a-release.apk` only on very old 32-bit phones.
   On a PC emulator (BlueStacks, LDPlayer, MEmu, Android Studio emulator…) use `app-x86_64-release.apk`,
   or `app-x86-release.apk` if the emulator says it only supports x86.
5. Allow "Install unknown apps" for your browser/file manager when Android asks.

New builds install as updates over the old one (they share the same signing key in `app/release.jks`).

## Notes
- Songs are stored in the app's own Music folder (no storage permission). Uninstalling the app deletes them.
- First launch takes a few seconds while the downloader unpacks.
- If YouTube downloads start failing, tap **Update** next to the engine version first.
- Only download content you have the right to.
