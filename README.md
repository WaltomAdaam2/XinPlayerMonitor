# XinPlayerMonitor

XinPlayerMonitor 是一个用于 XinBot 的玩家数据记录插件。插件在机器人进入 `Game` 状态后开始工作，并将玩家活动持续写入 SQLite 数据库。

当前版本：**v1.4.3**

## 主要功能

- 记录玩家登录、登出和在线会话；
- 记录玩家公共聊天；
- 自动或手动获取玩家 Stat；
- 查询玩家最新 Stat、最近登录和最近聊天；
- 使用 SQLite + WAL 保存数据；
- 自动备份数据库并校验备份完整性；
- 从旧版 JSON/JSONL 玩家目录迁移数据；
- 在数据库写入失败时将未写入事件追加到 `failed-events.jsonl`；
- 提供数据库记录数量统计。

## 运行要求

- Java 17
- XinBot 2.3.0

## 安装

1. 下载 `XinPlayerMonitor-v1.4.3.jar`。
2. 将 JAR 放入 XinBot 的插件目录。
3. 启动 XinBot。
4. 插件会在运行目录创建 `playermonitor/` 数据目录。

从旧版本升级前，建议先完整备份现有的 `playermonitor/` 目录。

## 指令

XinBot 控制台中直接输入指令，不需要 `/`。

### 查看帮助与设置

```text
playermonitor
playermonitor setting
```

### 手动扫描在线玩家 Stat

```text
playermonitor scan-stat
```

手动扫描只会在机器人处于 `Game` 状态时执行。它不受 `stat-enabled` 和自动扫描冷却限制影响。

### 查看数据库统计

```text
playermonitor db-stat
```

输出包括：

- 玩家数；
- 聊天记录数；
- 登录会话数；
- Stat 信息记录数；
- 未结束会话数。

### 查询玩家

```text
playermonitor <玩家名> stat
playermonitor <玩家名> latestlogin
playermonitor <玩家名> recentlogin [count]
playermonitor <玩家名> chat [count]
```

示例：

```text
playermonitor Steve stat
playermonitor Steve recentlogin 10
playermonitor Steve chat 20
```

`recentlogin` 和 `chat` 的临时查询数量范围为 `5–50`。不填写时，分别使用 `recentlogin-count` 和 `chat-count` 的当前设置。

## 可修改设置

设置修改后会立即写入 `playermonitor/settings.json`。

| 设置 | 说明 | 默认值与范围 |
|---|---|---|
| `scan-on-entry` | 进入 `Game` 时是否自动扫描当前在线玩家 | `true` |
| `disconnect-timeout` | 断线后等待多少分钟再确认登出 | `10`，必须大于 `0` |
| `stat-enabled` | 自动 Stat 扫描总开关 | `true` |
| `stat-send-interval` | 连续发送两条 Stat 指令的间隔 | `500 ms`，必须大于 `0` |
| `stat-output-hide` | 是否隐藏自动 Stat 返回内容 | `true` |
| `stat-cooldown` | 同一玩家两次自动 Stat 扫描的最短间隔 | `24 h`，范围 `0–168`；`0` 关闭冷却 |
| `stat-timeout` | 单次 Stat 响应等待时间 | `3000 ms`，范围 `1000–30000` |
| `stat-attempts` | 单个玩家一次 Stat 周期的最大总尝试次数 | `4`，范围 `1–10` |
| `scan-on-join` | 玩家加入时是否自动加入 Stat 队列 | `true` |
| `prioritize-join-stat` | 新加入玩家是否优先于进入 Game 时的在线名单 | `true` |
| `display-timezone` | 查询结果显示时间所使用的 UTC 时区 | `UTC` |
| `recentlogin-count` | 最近登录默认显示数量 | `15`，范围 `5–50` |
| `chat-count` | 最近聊天默认显示数量 | `10`，范围 `5–50` |
| `backup-interval` | 自动数据库备份间隔 | `168 h`，必须大于 `0` |

设置示例：

```text
playermonitor setting stat-enabled false
playermonitor setting stat-cooldown 12
playermonitor setting display-timezone UTC+08:00
playermonitor setting backup-interval 24
```

### 时区格式

`display-timezone` 支持 `UTC-12:00` 到 `UTC+14:00`，包括半小时和四十五分钟偏移，例如：

```text
UTC
UTC-03:30
UTC+05:30
UTC+05:45
UTC+08:00
UTC+12:45
UTC+13:00
UTC+14:00
```

也可以输入 `UTC+8` 等简写，保存时会规范为 `UTC+08:00`。

### 兼容性设置

`cache-idle` 和 `max-cached-history` 仍保留在设置文件和指令中，用于兼容旧版本配置。当前 SQLite 后端不依赖旧版玩家历史缓存，因此不要依赖这两个设置控制数据库中的历史记录数量。

## 数据目录

默认目录结构：

```text
playermonitor/
├── settings.json
├── xinpm.db
├── xinpm.db-wal
├── xinpm.db-shm
├── failed-events.jsonl
├── xinpm-auto-backup-YYYYMMDD-HHMMSS.db
├── migration-reports/
├── legacy-json-backup/
└── log/
    └── playermonitor-YYYY-MM-DD.log
```

并非所有文件或目录都会始终存在：

- `xinpm.db`：主 SQLite 数据库；
- `xinpm.db-wal`、`xinpm.db-shm`：SQLite WAL 模式运行文件，插件运行时可能存在；
- `settings.json`：插件设置；
- `failed-events.jsonl`：数据库写入失败时保存的事件诊断记录；
- `xinpm-auto-backup-*.db`：自动数据库备份；
- `migration-reports/`：旧数据迁移结果和错误报告；
- `legacy-json-backup/`：迁移成功后归档的旧版 `players/` 目录；
- `log/`：插件按日期生成的日志。

不要在插件运行时手动删除或替换 `xinpm.db`、`xinpm.db-wal` 或 `xinpm.db-shm`。

当前版本不会自动删除旧的自动备份文件。请根据磁盘空间定期自行归档或清理。

## SQLite 存储

XinPlayerMonitor 使用以下主要数据表：

- `players`：玩家身份、首次出现、最后出现和当前状态；
- `sessions`：登录与登出会话；
- `chat_messages`：公共聊天记录；
- `stat_snapshots`：成功获取的 Stat 快照；
- `migration_state`：旧数据迁移总体状态；
- `legacy_migration_players`：逐玩家迁移状态。

玩家名使用 `Locale.ROOT` 规则进行不区分大小写的内部索引，同时保留显示名称。

写入操作通过有界队列交给单独的 SQLite writer 线程，并批量提交事务。插件关闭时会停止接收新写入、等待已经接收的事件完成，并在超时或数据库致命错误时记录详细错误。

## 自动备份

自动备份默认每 `168` 小时执行一次，可通过以下指令修改：

```text
playermonitor setting backup-interval <hour>
```

备份流程：

1. 等待当前数据库写入完成；
2. 使用 SQLite `VACUUM INTO` 创建独立备份；
3. 对临时备份执行 `PRAGMA integrity_check`；
4. 校验成功后再移动为正式备份文件；
5. 备份文件名使用 UTC 时间。

如果上一次备份尚未完成，新的备份周期不会与它并发执行。

## 旧数据迁移

插件会检测旧版目录：

```text
playermonitor/players/<玩家名>/profile.json
playermonitor/players/<玩家名>/sessions.jsonl
playermonitor/players/<玩家名>/chat.jsonl
playermonitor/players/<玩家名>/stats.jsonl
```

迁移流程：

1. 检查 SQLite 中是否已有无法确认来源的玩家数据；
2. 检查仅大小写不同的重复玩家目录；
3. 按玩家读取并校验旧版 JSON/JSONL；
4. 在事务中写入 SQLite；
5. 核对迁移后的玩家、会话、聊天和 Stat 数量；
6. 写入 `migration-reports/`；
7. 全部成功后将旧 `players/` 目录移动到 `legacy-json-backup/`。

默认使用严格迁移模式。损坏的非空 JSONL 行会导致对应迁移失败，而不是静默丢弃。

高级配置 `database.allowPartialLegacyMigration=true` 可允许跳过损坏行，但只建议在已经备份原数据并理解数据缺失风险时使用。

旧版 `stats.jsonl` 中存在多个 Stat 快照时，迁移只保留 `capturedAt` 最新的一条；迁移完成后新获取的 Stat 会继续写入 `stat_snapshots` 表，玩家查询指令显示最新一条。

## 写入失败记录

当 SQLite writer 遇到无法完成的事件时，插件会将事件信息追加到：

```text
playermonitor/failed-events.jsonl
```

记录包括事件序号、时间、操作类型、玩家名、错误信息和事件 JSON。该文件用于诊断和人工恢复，不应在未确认数据已经处理前直接删除。

当前版本没有面向用户的 `recover-failed` 指令。

## Stat 队列

每名玩家的一次 Stat 周期最多发送 `stat-attempts` 次，包括第一次发送。发送异常和响应超时都会计入尝试次数。

达到上限后，插件会：

- 清除该玩家当前 Stat 周期的等待和重试状态；
- 写入警告日志；
- 继续处理队列中的下一个玩家；
- 避免同一周期无限重试。

自动扫描优先级：

1. 新出现的玩家；
2. 普通加入玩家；
3. 进入 `Game` 时扫描到的在线玩家。

手动 `scan-stat` 的优先级最高。

## 高级数据库配置

以下字段位于 `settings.json` 的 `database` 对象中，通常不需要修改：

| 字段 | 默认值 | 允许范围或说明 |
|---|---:|---|
| `path` | `xinpm.db` | 相对路径以 `playermonitor/` 为基准，也可使用绝对路径 |
| `queueCapacity` | `50000` | `1000–500000` |
| `batchSize` | `250` | `1–5000` |
| `flushIntervalMs` | `250` | `10–5000` |
| `busyTimeoutMs` | `10000` | `1000–60000` |
| `cacheSizeKiB` | `32768` | `1024–262144` |
| `shutdownFlushTimeoutMs` | `30000` | `1000–120000` |
| `allowPartialLegacyMigration` | `false` | 是否允许旧数据迁移跳过损坏 JSONL 行 |

修改高级数据库配置前应先停止 XinBot，并备份完整的 `playermonitor/` 目录。

## 构建

项目使用 Java 17 和 Maven：

```bash
mvn clean test package
```

生成文件：

```text
target/XinPlayerMonitor-v1.4.3.jar
```

## License

本项目使用仓库中的 `LICENSE` 文件所声明的许可证。
