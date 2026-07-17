# XinPlayerMonitor

XinBot plugin for 2b2t.xin that permanently records player login sessions, public chat, and `/stat <player>` snapshots.

The plugin writes UTF-8 player files to `playermonitor/<player>.json` beside the XinBot process. Runtime records are never pruned automatically.

## Console commands

```text
player <name>
player <name> stat
player <name> lastlogin
player <name> recentlogin
```

Only activity observed after XinBot enters the `Game` server state is recorded.
