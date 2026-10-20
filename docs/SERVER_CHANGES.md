# lustr server changes needed by the TV app (lstr)

For the Claude session that maintains the lustr server / web app (`adlerdark/lustr`).
Written against lustr **0.6.1** (commit `ab07065`). File and function names below refer to that tree.

The TV app is a separate Android TV client. It talks to the existing JSON API with the normal login
cookie (`video_library_session`). Everything it uses today already exists **except section 1**,
which it needs before its new login screen works. Sections 2–4 are optional improvements;
each is independent.

| # | Change | Needed? |
|---|---|---|
| 1 | TV login with a 4-digit code entered in the web app; TV sessions capped at 4 hours | **Required** |
| 2 | Surround sound (5.1) in HLS transcodes | Optional |
| 3 | Bitrate that fits the resolution for HLS "Original" / a max-bitrate parameter | Optional |
| 4 | A display filter per TV, separate from the web app's | Optional |

Please keep the existing behaviour of the web app unchanged unless a section says otherwise, add tests
next to the existing ones in `tests/` (e.g. `tests/test_auth.py`), and bump the version as you normally do.

---

## 1. TV pairing login (required)

### What the user sees

1. The TV app opens on a screen showing a 4-digit code, e.g. **4831**, and the text
   "Enter this code in the web app". (The TV deliberately doesn't show the server address or the
   app's name: other people in the house see this screen.)
2. In the web app, logged in as the owner, they open **Settings → General → Link a TV**
   (also reachable from the account menu, see "Web UI" below), type `4831` and press **Link**.
3. Within ~2 seconds the TV is logged in.
4. The TV login lasts **at most 4 hours**, whatever happens. After that the TV shows a new code.
   The normal inactivity timeout (Settings → General) also applies to TV sessions, as it does to
   browsers (any request – the TV saves progress every 10 s while playing – counts as activity).
5. The web app lists the TVs that are logged in, with a **Log out** button for each.

### Flow

```
TV                                   Server                                  Web app (owner)
POST /api/tv/pair/start  ───────►  new pairing: pair_id (secret), code
        ◄───────  {pair_id, code: "4831", expires_in: 300, poll_every: 2}
shows 4831
POST /api/tv/pair/poll {pair_id} ─►  {"status": "pending"}         (every 2 s)
                                                                    POST /api/tv/pair/approve {code: "4831"}
                                     pairing approved by owner  ◄──  (normal cookie, same-origin check)
                                                               ──►  {"success": true, "device_name": ...}
POST /api/tv/pair/poll {pair_id} ─►  {"status": "approved"} + Set-Cookie video_library_session=<token>; Max-Age=14400
TV uses the cookie for everything, exactly like a browser.
```

### New module `tv_pairing.py` (no FastAPI imports, like `auth_sessions.py`)

In memory only (a restart simply cancels pending pairings — the TV then asks for a new code):

```python
PAIR_TTL = 300          # a code is valid for 5 minutes
MAX_PENDING = 20        # oldest pending pairing is dropped beyond this
TV_SESSION_MAX_AGE = 4 * 3600

_pending = {}   # sha256(pair_id) -> {'code': '4831', 'created': t, 'device_name': str,
                #                     'approved_by': None | username, 'ip': str}
_lock = threading.Lock()

def start(device_name: str, ip: str) -> dict
    # pair_id = secrets.token_urlsafe(32); code = f'{secrets.randbelow(10000):04d}',
    # re-drawn until no other *pending, unexpired* pairing has the same code.
    # Purge expired entries first; if len(_pending) >= MAX_PENDING drop the oldest.
    # device_name: strip, max 60 chars, default 'TV'.
    # -> {'pair_id': pair_id, 'code': code, 'expires_in': PAIR_TTL, 'poll_every': 2}

def approve(code: str, username: str) -> Optional[dict]
    # The unexpired pending pairing with this code (exact 4 digits) -> set approved_by, return
    # {'device_name': ...}; None if there is none.

def claim(pair_id: str) -> tuple[str, Optional[str]]
    # -> ('pending', None) | ('expired', None) | ('approved', username).
    # 'approved' removes the pairing (a pair_id can be used once).
    # Unknown pair_id -> ('expired', None) (don't reveal whether it existed).
```

Codes are only 4 digits, so the security rests on: (a) only a logged-in owner can approve, (b) a code
expires after 5 minutes, (c) the TV side never submits a code – it holds a long random `pair_id`
that is never shown, (d) wrong codes in `approve` are rate-limited (below). Don't log `pair_id`s.

### `auth_sessions.py`: per-session lifetime and kind

Add two columns to `sessions` (migrate in `set_db_path` with `PRAGMA table_info` + `ALTER TABLE ... ADD COLUMN`, as other tables do):

```sql
kind     TEXT NOT NULL DEFAULT 'browser'   -- 'browser' | 'tv'
expires  REAL                              -- absolute end (epoch seconds); NULL = the usual MAX_AGE
device   TEXT                              -- for the list in the web app, e.g. 'SHIELD Android TV'
```

- `create(username, kind='browser', max_age=None, device=None)`: `expires = now + max_age` when given.
- `check(...)`: also end the session when `expires is not None and now > expires`
  (keep the existing MAX_AGE and inactivity rules).
- `purge(...)`: also delete rows with `expires < now`.
- New `list_sessions(username, kind=None) -> [{id, kind, device, created, last_active, expires}]`
  where `id` is the first 12 hex chars of `token_hash` (enough to identify; never the token).
- New `end_by_id(username, id) -> bool`.
- `end_all(...)` (password change / reset) must keep ending TV sessions too.

### Routes: new `routes/tv.py` (`APIRouter(prefix='/api/tv')`), registered in `web_server.py`

| Method & path | Auth | Body | Reply |
|---|---|---|---|
| `POST /api/tv/pair/start` | **public** | `{"device_name": "SHIELD Android TV"}` (optional) | `{"pair_id", "code", "expires_in": 300, "poll_every": 2}` |
| `POST /api/tv/pair/poll` | **public** | `{"pair_id": "..."}` | `{"status": "pending" \| "expired" \| "approved"}`; on `approved` also **sets the login cookie** (see below) |
| `POST /api/tv/pair/approve` | owner | `{"code": "4831"}` | `{"success": true, "device_name": "..."}`; wrong / expired code → `404 {"detail": "No TV is showing that code."}` |
| `GET /api/tv/sessions` | owner | – | `{"sessions": [{"id", "device", "created", "last_active", "expires"}]}` (TV sessions only, newest first; times as epoch seconds) |
| `POST /api/tv/sessions/logout` | owner | `{"id": "..."}` or `{"all": true}` | `{"success": true, "ended": n}` |

Details:

- **Cookie on approve-poll**: same as `routes/auth.py::_start_session` but with
  `max_age=TV_SESSION_MAX_AGE` (the browser cookie has none) and the session created with
  `auth_sessions.create(username, kind='tv', max_age=TV_SESSION_MAX_AGE, device=device_name)`.
  `httponly=True, samesite='lax', secure=request_is_https(request)`.
  (Add an optional `max_age` / `kind` / `device` parameter to `helpers.sign_token` or call
  `auth_sessions.create` directly.)
- **Public paths**: add `/api/tv/pair/start` and `/api/tv/pair/poll` to `public_endpoints` in
  `web_server.py::auth_middleware`. Nothing else under `/api/tv` is public.
- **CSRF**: `same_origin()` already lets requests without `Origin`/`Referer` through, which is what
  the TV sends. `approve` / `sessions/logout` come from the web page and pass the check normally.
- **Rate limits** (reuse `helpers.LoginLimiter`, separate instances):
  - `start`: 10 per 5 minutes per IP → `429` with `Retry-After`.
  - `poll`: no limit needed (the pair_id is unguessable), but ignore bodies > 1 KB.
  - `approve`: 5 wrong codes per 5 minutes per **user** → `429 {"detail": "Too many wrong codes. Try again in N minutes."}`.
- `GET /api/check_auth` should keep working for TV sessions (it does if `check` handles `expires`).

### Optional: password login from the TV, also 4 hours

The TV keeps a "use password instead" fallback. Accept an optional field in `POST /api/login`:
`{"username", "password", "client": "tv"}` → create the session with `kind='tv', max_age=4h`
and set the cookie with `max_age=14400`. Without `client`, behave exactly as today.

### Web UI (`templates/index.html`)

- **Settings → General**, new section **"Link a TV"** (near Account):
  - a 4-digit input (`inputmode="numeric"`, `maxlength="4"`, auto-submit on the 4th digit or a **Link** button),
  - on success: "Linked SHIELD Android TV. It stays logged in for up to 4 hours." and refresh the list,
  - on 404 / 429: show the `detail`,
  - **Logged-in TVs** list (`GET /api/tv/sessions`): device, "logged in 14:05, ends 18:05",
    a **Log out** button per row and **Log out all TVs** (`POST /api/tv/sessions/logout`).
- A shortcut **"Link a TV…"** in the header's account / settings menu that opens the same section
  (the TV screen only says "Enter this code in the web app", so it should be easy to find).
- The inactivity countdown / auto-logout in the web page is unaffected.

### Tests (`tests/test_tv_pairing.py`)

- start → poll pending → approve (as owner) → poll approved sets a cookie whose session has `kind='tv'`
  and `expires ≈ now + 4h`; a second poll with the same pair_id → expired.
- approve with a wrong code → 404; 6th wrong code in 5 min → 429.
- code expires after 300 s (pass `now` like `LoginLimiter` does, or monkeypatch time).
- a TV session past `expires` is refused by `check` even when active; `end_all` (password change) ends TV sessions.
- `/api/tv/pair/approve` without a cookie → 401 (middleware); the two public paths work without one.
- two pending pairings never share a code.

---

## 2. Surround sound in HLS (optional)

Today `transcoder.build_args` always outputs `-c:a aac -ac 2 -b:a 160k`. On a TV with a receiver,
5.1 is lost whenever the TV has to use HLS (e.g. a file the Shield can't direct-play, or a lower
quality picked in the TV player).

Suggested: an optional `audio` query parameter on `/api/player/hls/{res}/index.m3u8` and the segment
URLs (carry it into the segment URLs the playlist lists, like `path`):

- `audio=stereo` (default, today's behaviour)
- `audio=surround` → `-c:a aac -ac 6 -b:a 384k` when the source has ≥ 6 channels, else stereo
- `audio=copy` → `-c:a copy` when the source audio is AAC / AC-3 / E-AC-3 (TS can carry them), else surround

Make it part of the stream key in `SegmentManager` so a change restarts ffmpeg. Expose the source's
channel count / codec in `/api/player/streams` (`audio_channels`, `audio_codec` from `probe`).
The TV app will add a "Surround sound" setting once this exists.

## 3. HLS bitrate (optional)

`BITRATE_K['original'] = 8000` is low for 4K / high-bitrate sources. Suggested:

- for `original`, pick the bitrate from the source height (e.g. ≥ 2160 → 25000k, ≥ 1440 → 16000k,
  else 8000k), capped at the source's own bitrate when known;
- accept an optional `max_kbps` query parameter on the HLS URLs (like Plex's quality presets) that
  caps `-b:v` / `-maxrate`; include it in the stream key.

## 4. Display filter per TV (optional)

`display_filter` keeps one selection per **username** (`metadata['display_filter'][username]`), so
changing the orientation filter on the TV (via `POST /api/settings/display-filter`, which the TV app
uses today) also changes it in the web app. If you'd rather keep them separate:
store the selection per session kind (`{username: {...}, username + '#tv': {...}}`) and pick the key
from the session's `kind` in the auth middleware (where `CURRENT_USER` is set). The TV app needs no
change for this.

---

## 5. Group names dropped from `library_order` at start-up (optional, small)

`model.py` (load, ~line 152) does `self.library_order = [l for l in self.library_order if l in self.libraries]`,
which removes every **group** name from the saved order when the server starts. The web app hides this
by appending missing top-level groups at load time (`index.html`, "FORCE GROUPS INTO ORDER"), and
the TV app now does the same, so nothing is broken — but the owner's position of the groups among
the libraries is lost on each restart (they always end up last). Suggested: keep names that are
either a library or a group (`if l in self.libraries or l in self.library_groups`), in both the SQLite
and the JSON branch.

## Already fine (no change needed) – for reference

- Sort by **Cast** in `/api/videos` (`sort_by=Cast`) works for libraries, groups and All Videos.
- Direct play `/files/...` with range requests; HLS transcoding on demand (`transcoder.py`) with
  segments in `cache/player_hls`, removed after 30 s idle — the TV uses these as they are.
- Movies (`/api/movies`, `/api/movies/filters`, `/api/movies/{id}`), collections, cast
  (`/api/cast/all`, `/{name}/bio`, `/{name}/videos`, `/photo/{name}`, favorites), `/api/toggle_fav`,
  `/api/video` (File Info) and `/api/settings/display-filter` are used as they are.
- `/api/health` stays as it is (the TV's network scan looks for it on port 8008).
