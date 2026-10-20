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
| `GET /api/available_filters?library=&query=&tags=&tags_exclude=&sites=&sites_exclude=&favorites_only=&length_filter=` | `{tags: {name: count}, sites: {name: count}}` among the videos the other filters leave (names lower-case) |
| `GET /api/collections?library=` | `{success, collections: [{id, name, kind: "collection"/"smart", description, count, cover}]}` (all of them for All Videos) |
| `GET /api/collections/{id}/items?page=&limit=` | `{success, collection, items: [Video], total}` |
| `GET /api/movies?library=&q=&sort=title&page=&limit=` | `{success, movies: [{id, title, display, year, studio, count, has_cover, cover, favorite}], total}` |
| `GET /api/movies/{id}` | `{success, movie: {…, description, director, runtime (min), tags}, items: [Video with movie_scene]}` |

| `GET /api/movies/filters?library=&q=&tags=…&favorites=` | `{success, tags: {name: count}, sites: {name: count}}` among the movies the filters leave |
| `POST /api/toggle_fav` | body `{path}` → `{favorite: true/false}` |
| `GET /api/cast/all` | `{cast: [{name, count, is_favorite, has_photo, gender, category, aliases}]}` (everyone at once) |
| `GET /api/cast/{name}/bio` | `{name, aliases, is_favorite, has_photo, gender, category, exact_matches: [Video], library_distribution, appearance: {hair: [...]}, stats}` |
| `GET /api/cast/photo/{name}?thumbnail=true` | the photo (404 when there is none) |
| `POST` / `DELETE /api/cast/favorites/{name}` | mark / unmark a favorite performer |
| `GET /api/extdb/cast?name=` | `{linked, link}`: the stored StashDB / ThePornDB profile (no network call) |
| `POST /api/tv/pair/start`, `/api/tv/pair/poll` | code sign-in (lustr 0.8+, see SERVER_CHANGES.md §1) |
| `GET` / `POST /api/settings/display-filter` | the orientation filter (`{types}`); TVs have their own on lustr 0.8+ |

`/api/videos` and `/api/available_filters` take the same filter parameters: comma-separated
`tags` / `tags_exclude` / `sites` / `sites_exclude`, `favorites_only=true`, and `length_filter`
as comma-separated `from-to` second ranges (`0-120,2400-28800`). Sorts: `Title`, `Filename`,
`Date Created`, `Length`, `File Size`, `Rating`, `Recently Added`, `Most Played`, `Last Played`,
`Time Watched`, `Random` (with `seed` for stable paging).

### Sidebar (index.html: `visibleLibraries`)

`/api/init` → Home, All Videos (unless in `hidden_libraries`), then `library_order`: a group
without a `parent` is shown with its `libraries` (libraries and sub-groups, recursively); a
library that isn't in any group is shown at the top level; libraries missing from the order come
last. Hidden libraries are left out, and while the display filter is on (`display_filter` shorter
than `display_types`) so are libraries whose `library_types` entry isn't selected (`mixed` always
shows), and groups left empty.

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
- `/api/cast/{name}/videos`, aliases, attributes editing, `/api/extdb/*` searches and linking.
- `/api/watch/...` – `continue_watching`, `recently_watched`, `log`, `stats`.
- `/api/toggle_fav`, `/api/metadata/update` – editing (favorite, tags, …).
- `/api/player/markers`, `/api/player/sprites/*`, `/api/captions/*`, `/api/clips/*`.
- Admin: `/api/library/*`, `/api/scan`, `/api/thumbnails/*`, `/api/tools/*`, `/api/extdb/*`, `/api/files/*`.
