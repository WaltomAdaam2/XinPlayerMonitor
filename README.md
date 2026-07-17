# XinPlayerMonitor

XinBot plugin for 2b2t.xin that permanently records player login sessions, public chat, and `/stat <player>` snapshots.

The plugin writes UTF-8 player files to `playermonitor/<player>.json` beside the XinBot process. Runtime records are never pruned automatically.

## Console commands

```text
player <name>
player <name> stat
player <name> lastlogin
player <name> recentlogin
playermonitor setting
playermonitor setting interval <ms>
playermonitor setting auto <on|off>
playermonitor setting enabled <on|off>
playermonitor stat scan
```

Only activity observed after XinBot enters the `Game` server state is recorded.
Player-name completion combines stored records with XinBot's current Game player list.

## Game chat query

In the Game server, players can send `!player <name>` in public chat. The bot replies with the
latest login, play duration, and (when available) logout time. XinPlayerMonitor accepts only one
such query every 60 seconds globally; all public chat is still recorded during the cooldown.

`playermonitor/settings.json` persists the stat interval (default `500ms`), whether a Game entry
starts a full-player stat scan, and whether automatic stat scans are enabled. Disabling automatic
stat scans does not disable `playermonitor stat scan`.

Each full-player scan opens a login session only for players not yet observed in the current Game;
their later leave event closes that same session while chat and stat records continue normally.
