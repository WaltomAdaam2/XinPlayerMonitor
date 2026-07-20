# XinPlayerMonitor

XinBot plugin for 2b2t.xin that permanently records player login sessions, public chat, and `/stat <player>` snapshots.

## Storage layout

Each player gets its own directory: `playermonitor/players/<PlayerName>/`, containing:

- `profile.json` — pretty-printed JSON with the player's canonical name, first-seen time, and current online session.
- `sessions.jsonl` — one JSON object per completed login/logout session (JSON Lines, append-only).
- `chat.jsonl` — one JSON object per recorded public chat message (JSON Lines, append-only).
- `stats.jsonl` — one JSON object per captured `/stat` snapshot (JSON Lines, append-only).

Plugin-wide files live alongside the `players/` directory: `playermonitor/settings.json` for configuration, and `playermonitor/log/playermonitor-<yyyy-MM-dd>.log` for one log file per calendar day.

**No player directory, `settings.json`, or log file is ever automatically deleted** — they are meant to persist forever. The only files the plugin ever removes on its own are its own incomplete scratch files (named with a plugin-specific prefix such as `xpm-profile-`, `xpm-settings-`, or `xpm-migration-`, always ending in `.tmp` or left over as an abandoned `xpm-migration-` staging directory) left over from an interrupted write, plus the original legacy file after it has been successfully migrated into its new player directory (see below). A user's own `*.tmp` files are never touched — only files and directories matching the plugin's own naming scheme are cleaned up.

Player names are matched case-insensitively (`Steve`, `steve`, and `STEVE` all resolve to the same directory and record) while the original casing is preserved for display. If two legacy directories differ only by case, or a player's directory name doesn't match the `playerName` stored in its own `profile.json`, the mismatched data is quarantined (renamed aside, never merged or silently renamed) and a warning is logged.

## Migration from older layouts

On startup, any player JSON files found in the pre-v1.3.0 flat-file layout — either directly under `playermonitor/<name>.json` or under `playermonitor/players/<name>.json` — are automatically migrated into the new per-player directory format. Migration is done in a temporary staging directory and validated before being promoted into place, so a failure partway through never leaves a partial player directory behind. If a directory already exists at the destination, migration never overwrites it — both the legacy file and the existing directory are left in place and a warning is logged. Once a legacy file's data has been converted, validated, and successfully promoted into its new player directory, the legacy file is deleted and no backup copy is kept. If migration fails or is skipped for any reason, the legacy file is always left untouched in its original location.

## Corrupted data handling

If a player file or `settings.json` is found to contain corrupted/unreadable JSON, the plugin first attempts to quarantine it by renaming it aside (e.g. `profile.json.corrupted`, or `.corrupted-2` if that name is already taken) rather than deleting or silently overwriting it. Only after quarantine succeeds does the plugin fall back to a fresh record or default settings, with a warning logged that names the exact file and the reason it was corrupted. If quarantine itself fails (e.g. the filesystem is read-only), the plugin blocks all further writes to that file rather than risk overwriting the corrupted data, and logs a warning. When only one line of a `.jsonl` file is corrupted, the remaining valid lines are preserved.

## Cache eviction

Player records are cached in memory after first access. A record that has been idle (no reads or writes) for 30 minutes or more is evicted from the cache on a check that runs every 5 minutes, freeing memory for inactive players. Eviction never touches on-disk data — it only affects what's held in memory, and a record is reloaded from disk on its next access. A player is never evicted while online, mid-migration, mid-write, or while a stat request is in flight for them. The eviction scheduler is stopped and its resources released when the plugin is disabled.

## Console commands

```text
playermonitor setting stat
playermonitor stat scan
playermonitor setting stat interval <ms>
playermonitor setting stat autoscan <true|false>
playermonitor setting stat enabled <true|false>
playermonitor setting stat outputhide <true|false>
playermonitor <name> stat
playermonitor <name> latestlogin
playermonitor <name> recentlogin
playermonitor <name> chat
```

Only activity observed after XinBot enters the `Game` server state is recorded.
Player-name completion starts after one typed character and uses an in-memory player-name index plus XinBot's current Game player list. It never parses player JSON files while completing.

`playermonitor/settings.json` persists the stat interval (default `500ms`), whether a Game entry
starts a full-player stat scan, whether automatic stat scans are enabled, and whether stat output
is hidden in the chat log (default `true`). Disabling automatic stat scans does not disable
`playermonitor stat scan`.

Each full-player scan opens a login session only for players not yet observed in the current Game;
their later leave event closes that same session while chat and stat records continue normally.
Automatic stat scans skip players whose latest successfully recorded stat is less than 24 hours old.
Manual `playermonitor stat scan` bypasses this cooldown; login, logout, and chat recording are not affected.

## Stat request retries

Each `/stat <player>` request is sent at most 4 times total per scan cycle (1 initial send plus up to 3 retries) before the plugin gives up on that player for the cycle. A command-send failure counts as a failed attempt just like a response timeout, and never permanently blocks the retry queue — the next queued player is always processed regardless of how the previous one resolved. Once a player's retries are exhausted, a warning is logged and their retry state is cleared; a later manual or automatic scan starts a fresh retry cycle for them.

## Logging

The plugin writes one log file per calendar day to `playermonitor/log/playermonitor-<yyyy-MM-dd>.log`. When the day rolls over, a new file is created automatically; previous days' files are never deleted or overwritten. Successful chat recording is not logged (to avoid flooding the log with routine chat traffic), but chat write failures, migrations, logins, logouts, stat results, quarantine actions, and other warnings/errors are always logged.

