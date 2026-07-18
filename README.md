# XinPlayerMonitor

XinBot plugin for 2b2t.xin that permanently records player login sessions, public chat, and `/stat <player>` snapshots.

The plugin writes UTF-8 player files to `playermonitor/<player>.json` beside the XinBot process. Runtime records are never pruned automatically.

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

## Game chat query

In the Game server, players can send `!player <name>` in public chat. Extra text after the player name is ignored, and every bot reply receives a random alphabetic suffix to avoid duplicate-message filtering. The bot replies with the
latest login, play duration, and (when available) logout time. XinPlayerMonitor accepts only one
such query every 60 seconds globally; all public chat is still recorded during the cooldown.

`playermonitor/settings.json` persists the stat interval (default `500ms`), whether a Game entry
starts a full-player stat scan, whether automatic stat scans are enabled, and whether stat output
is hidden in the chat log (default `true`). Disabling automatic stat scans does not disable
`playermonitor stat scan`.

Each full-player scan opens a login session only for players not yet observed in the current Game;
their later leave event closes that same session while chat and stat records continue normally.
Automatic stat scans skip players whose latest successfully recorded stat is less than 24 hours old.
Manual `playermonitor stat scan` bypasses this cooldown; login, logout, and chat recording are not affected.
