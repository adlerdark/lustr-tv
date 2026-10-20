# Lustr Android TV Client

An Android TV app (built for the NVIDIA Shield) for a [lustr](https://github.com/adlerdark/lustr)
server: log in with the owner account, browse the Home rows, and play videos with resume.

## What's in here

```
core/   Talks to the lustr server: data models, login cookie, API calls, playback rules.
        Plain Kotlin (no Android), so it has fast unit tests.
app/    The Android TV app: screens (Jetpack Compose for TV) and the player (Media3 ExoPlayer).
docs/   API.md - the lustr endpoints this app uses, and how login works.
        SERVER_CHANGES.md - what the lustr server needs to add for this app (code login, ...).
third_party/  Licence of the bundled Tangerine font (the lustr logo font, SIL Open Font License).
```

Screens so far: **Server address → Sign in → Browse (sidebar + tabs) → Player / Settings**.

- **Privacy**: on the TV the app is called **lstr**. Nothing before sign-in names it or hints at
  what it is. The sign-in screen only says "Enter this code in the web app".
- **Sign in**: the app shows a 4-digit code; enter it in lustr's web app (Settings → General →
  Link a TV) and the TV signs in. This needs the server change in
  [`docs/SERVER_CHANGES.md`](docs/SERVER_CHANGES.md) §1; until then use **Use password instead**.
  The TV stays signed in (also after closing the app) until the server ends the session – its
  limit for TV sessions, the inactivity timeout, or **Log out**. The server address is remembered;
  **Search network** finds the server by itself.

- **Sidebar** (left): the lustr logo, **Home**, **All Videos**, then your libraries and groups in the
  same order as the web app (hidden libraries and the display filter are respected). Groups start
  collapsed:
  - a collapsed group: OK expands it **and** opens it;
  - an expanded group that isn't open: OK opens it;
  - the open, expanded group: OK collapses it (it stays open). So a double press always ends collapsed.
  - **Back** from anywhere on the right jumps to the sidebar.
- **Home** shows the same rows as lustr's web Home (Continue Watching, Recently Added, Because you
  watched…, Favorites, …). It refreshes whenever you come back to it.
- Every library / group has the web app's tabs:
  - **Recommended** – that library's Home rows.
  - **Library** – every video, paged as you scroll. **Search** (with lustr's search syntax, e.g.
    `cast:jane*`), **Sort** (pick the same sort again to reverse it), and **Filters**: favorites only,
    length, and tags / sites with counts – press once for ✓ (must have), again for ✕ (must not have),
    again to clear.
  - **Collections** – hand-picked and smart collections; open one to see its videos.
  - **Movies** – movies built from scenes; open one for its details and scenes in order.
- **Player** plays the original file directly (the Shield decodes MKV / HEVC / most formats itself, so
  the server does no work). If a file won't play, it switches to the server's HLS transcode
  automatically. It resumes where you left off, and saves progress every 10 seconds and when you
  leave, so Continue Watching and Play History stay in step with the web app. On the remote:
  - **◀ / ▶** (controls hidden, or on the progress bar): jump back / forward 10 s; **hold** to seek
    faster and faster – it jumps when you let go. ⏪ / ⏩ do the same. **Play/Pause** any time.
  - **OK / ▲ / ▼** show the controls: progress bar, Play/Pause, −/+, **CC**, **⚙ Settings**
    (quality – direct play or a transcode at original / 1080p / 720p / … –, captions, audio track,
    speed, picture zoom). **Back** hides them, Back again leaves the player.
- **Settings** (sidebar): the Library tab's default length filter (by default videos under 2 minutes
  are hidden), the orientation filter (the server's display filter, shared with the web app), start
  quality, seek step, resume (always / ask / never), captions on/off and size, picture zoom, frame-rate
  matching, privacy screen (no preview in recent apps, no screenshots), server, log out.

### Transcoding

The server already transcodes the way Jellyfin does, so nothing Plex-like is needed: when the
player asks for an HLS quality, lustr makes 2-second segments **on demand** with ffmpeg (GPU / VAAPI
if available) into a temporary folder (`data/cache/player_hls`), a little ahead of what's playing,
and deletes them 30 s after the player stops asking. Seeking restarts ffmpeg at the new spot.
ExoPlayer plays those HLS streams fine. Limits today (see `docs/SERVER_CHANGES.md` §2–3 for
suggested server improvements): transcodes have **stereo** sound only, and "original" is capped at
8 Mbps. Direct play has neither limit, and is what the player uses unless a file won't play.

## Step by step: run it on your Shield

### 1. Install Android Studio (once)

1. Download Android Studio from <https://developer.android.com/studio> and install it with the
   default options. The first start downloads the Android SDK; let it finish.

### 2. Open the project

1. Get this repository onto your computer, e.g. in a terminal:
   ```sh
   git clone <this repository's URL>
   ```
2. In Android Studio: **File → Open…** and pick the cloned folder (the one with `settings.gradle.kts`).
3. Wait for **Gradle sync** to finish (progress bar at the bottom; the first time takes a few minutes
   while it downloads libraries).
   - If it offers to update the Android Gradle plugin or libraries, you can accept.

### 3. Get the Shield ready (once)

1. On the Shield: **Settings → Device Preferences → About**, scroll to **Build** and press OK on it
   **7 times** until it says you're a developer.
2. Go back to **Device Preferences → Developer options** and turn on **Network debugging**
   (on some versions it's called **ADB debugging** — turn that on).
3. Note the Shield's IP address: **Device Preferences → About → Status → IP address**
   (e.g. `192.168.1.30`).

### 4. Connect Android Studio to the Shield

1. In Android Studio, open the **Terminal** tab (bottom of the window) and run, with your Shield's IP:
   ```sh
   adb connect 192.168.1.30:5555
   ```
   - If `adb` isn't found, use the full path instead: on Windows
     `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb connect 192.168.1.30:5555`, on macOS
     `~/Library/Android/sdk/platform-tools/adb connect 192.168.1.30:5555`.
2. The Shield asks **"Allow network debugging?"** — tick *Always allow from this computer* and press OK.
3. The Shield now appears in the device list at the top of Android Studio (next to the green ▶ button).

### 5. Run the app

1. With the Shield selected, press the green **▶ Run** button. The app builds, installs and opens.
2. Enter your lustr server's address (the same one you use in the browser, e.g. `192.168.1.20:8008`)
   and press **Connect** – or press **Search network**. The address is remembered.
3. Sign in: type the 4-digit code shown on the TV into the web app (Settings → General → Link a TV),
   or choose **Use password instead**.

After the first time, the app is on the Shield's home screen (in the apps row) as **lstr**.

> The app's package changed from `com.lustr.tv` to `com.lstr.tv` (a neutral name in system
> screens), so Android installs it as a new app. Uninstall the old "lustr" one: Shield
> **Settings → Apps → lustr → Uninstall** (in the emulator: long-press its icon → Uninstall).

## Running the tests (optional)

The `core` tests check the API code against responses recorded from a real lustr server. In Android
Studio's Terminal:

```sh
./gradlew :core:test            # Windows: gradlew :core:test
```

To also run them against **your** server (read-only, apart from logging in and out):

```sh
LUSTR_TEST_URL=192.168.1.20:8008 LUSTR_TEST_USER=you LUSTR_TEST_PASSWORD='...' ./gradlew :core:test
```

## Ideas for next steps

- Next session: movie posters + movie filters, long-press menu (favorite, video info, file info),
  cast browser and cast bio pages.
- Playlists / Watchlist (`/api/playlists`), play the next scene / collection video automatically.
- Follow the theme chosen in lustr's settings (the app uses lustr's default dark theme).
- Seek-bar previews from the server's sprite sheets (`sprite.vtt` in `/api/player/streams`).
- A media session (play/pause from the Google TV / Shield remote apps). Not added yet on purpose: it
  would show the video's title outside the app, so it should follow lustr's "Lock screen" privacy setting.
