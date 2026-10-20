# lustr server API (as used by the TV app)

Mapped from lustr 0.6.1 (`web_server.py`, `routes/*.py`, `database.py`). FastAPI, port 8008, JSON.

## Login

- `POST /api/login` `{"username", "password"}` → sets the cookie **`video_library_session`**
  (a random token; the server keeps its SHA-256 in the `sessions` table). No expiry on the cookie.
- Every other request needs that cookie, **including media**: `/files/...` (direct play),
  `/cache/...` (thumbnails, captions, sprites), `/clips/...` and HLS. Without it: `401`.
- A session ends after the inactivity timeout (default 15 min, Settings → General; 0 = never),
  after 30 days whatever happens, on logout, or when the password changes. Any authenticated request
  counts as activity (progress posts keep it alive while playing).
- 5 wrong passwords from one IP within 5 min → `429` with `Retry-After` (seconds).
- CSRF check: non-GET requests with an `Origin`/`Referer` from another host are refused (`403`).
  Requests with neither header (like this app's OkHttp calls) are allowed.
- Public (no cookie): `/api/health`, `/api/check_auth`, `/api/login`, `/api/signup`, `/api/max_users`.
- One account per install (`MAX_USERS = 1`); signup only works while there is none.

Errors are FastAPI-style `{"detail": "..."}`. Some endpoints instead reply `200` with
`{"success": false, "error": "..."}` (home, player, collections, clips, movies).

## Identity

Videos are addressed by their **file path** (e.g. `/media/Studio/scene.mp4`), passed as a `path`
query parameter or JSON field. Internally: `videos.video_id` (one logical video, e.g.
`TestLib_Alpha_Scene`) has one or more `file_versions` (one per file; `is_preferred` marks the one
listed). Metadata lives on the video; codec/size/length on the file version.

## Endpoints used by the app

| Endpoint | Returns |
|---|---|
| `GET /api/health` | `{status: "ok", version}` |
| `GET /api/check_auth` | `{authenticated, username}` (doesn't count as activity) |
| `POST /api/login` / `POST /api/logout` | cookie set / cleared |
| `GET /api/init` | `libraries {name: [folders]}`, `library_order`, `hidden_libraries`, `library_groups`, `tags`, `sites`, UI config |
| `GET /api/home?library=` | `{success, rows: [{key, title, items: [Video], see_all}]}` (cached 60 s server-side; progress posts invalidate it) |
| `GET /api/videos?library=&query=&sort_by=&desc=&page=&limit=` | `{items: [Video], total, page, limit, letters}` |
| `GET /api/video?path=` | one `Video` (+ `library`, `versions`) |
| `GET /api/player/streams?path=` | `{duration, width, height, codec, sources: [{kind, label, res, likely, type, src}], start_source, sprite: {ready, vtt}, captions: {tracks: [{lang, label, src}]}}` |
| `GET /files/<path>` | the original file, with HTTP range support (206) |
| `GET /api/player/hls/{res}/index.m3u8?path=` | VOD HLS playlist (2 s segments, `#EXT-X-ENDLIST`); `res` = `original`, `1080`, `720`, `480`, `240` |
| `POST /api/watch/progress` | body `{path, current_time, duration, play_id, played, log_only}` – resume point + Play History |

A `Video` item has: `path, filename, title, year, rating, favorite (0/1), resolution, codec, extension,
length ("HH:MM:SS"), total_bitrate, file_size, date_added, cache_id, video_id, thumb (relative URL or
null), supported (browser-playable), watch_progress / watch_duration (seconds, only if started)`,
the list fields `cast, site, tags, ethnicity, hair, body, outfit, theme, …` (arrays), and
`movie, movie_scene, movie_part`.

### Progress rules (same as the web player)

- `play_id`: one random id per opening of a video. `played`: seconds really played in this opening
  (running total; the server keeps the maximum). A play counts once `played` passes a minimum.
- Closing within the first 5 s sends `log_only: true`, so the old resume point is kept.
- Past 90 % the video is marked completed and leaves Continue Watching.

## Other route groups (not used yet)

- `/api/playlists` – list, `/{id}/items`, create / rename / add / remove / reorder; a built-in **Watchlist**.
- `/api/collections` – hand-picked or saved-filter collections, `/{id}/items`.
- `/api/cast/...` – performers: `/all`, `/{name}/videos`, `/{name}/bio`, `/photo/{name}`, favorites.
- `/api/movies` – full movies made of scenes; `/{id}`, `/{id}/cover`.
- `/api/watch/...` – `continue_watching`, `recently_watched`, `log`, `stats`.
- `/api/toggle_fav`, `/api/metadata/update` – editing (favorite, tags, …).
- `/api/player/markers`, `/api/player/sprites/*`, `/api/captions/*`, `/api/clips/*`.
- Admin: `/api/library/*`, `/api/scan`, `/api/thumbnails/*`, `/api/tools/*`, `/api/extdb/*`, `/api/files/*`.
