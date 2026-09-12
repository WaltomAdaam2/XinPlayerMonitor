# XinPlayerMonitor

XinPlayerMonitor 是一个用于 XinBot 的玩家数据记录插件。机器人进入 `Game` 状态后，插件会记录玩家登录、登出、公共聊天和 Stat，并将数据持续写入 SQLite。

当前版本：**v1.5.8**

## 主要功能

- 使用 SQLite + WAL 保存玩家、会话、聊天和最新 Stat；
- 使用单独 writer 线程和有界队列批量提交数据库事务；
- 记录玩家登录、登出和在线会话；
- 保存玩家公共聊天的**原始消息**，不再在入库前删除 emoji、符号或合并空格；
- 自动或手动获取在线玩家 Stat；
- 一条指令汇总玩家聊天数、KD、会话、游玩时间、付费权限、优先队列到期时间和最近 5 条发言；
- 查询玩家最新 Stat、最近登录和最近聊天；
- 自动备份、手动备份、备份列表和备份完整性验证；
- 提供数据库健康状态和记录数量；
- 自动检测并修复连接状态卡死、Bot/监控名单漂移和会话差异；
- 记录聊天流水线、SQLite 队列增长、writer 恢复和各类最近写入时间；
- 从旧版 JSON/JSONL 玩家目录迁移数据；
- 对数据库写入失败和队列拒绝事件写入 `failed-events.jsonl`；
- 启动时幂等重放可恢复的失败事件；
- 冷启动进入 `Game` 时修复上次异常退出遗留的未关闭会话。

## 运行要求

- Java 17
- XinBot 2.3.0

## 安装与升级

1. 停止 XinBot。
2. 备份完整的 `playermonitor/` 目录。
3. 将 `XinPlayerMonitor-v1.5.8.jar` 放入 XinBot 插件目录并替换旧版本。
4. 启动 XinBot。
5. 执行 `playermonitor status` 检查数据库、writer、失败事件和备份状态。
6. 执行 `playermonitor backup now` 创建一份升级后的人工备份。

插件默认在运行目录创建 `playermonitor/` 数据目录。

## 指令

XinBot 控制台中直接输入指令，不需要 `/`。所有 `playermonitor` 指令都可以缩写为 `xpm`，两者使用相同的执行和 Tab 补全逻辑。

### 帮助与设置

```text
playermonitor
playermonitor setting
xpm
xpm setting
```

### 手动扫描在线玩家 Stat

```text
playermonitor scan stat
playermonitor scan uuid
```

手动扫描只会在机器人处于 `Game` 状态时执行。它不受 `stat-enabled` 和自动扫描冷却限制影响；已有批次运行时会合并、去重并提高尚未完成目标的优先级。

### 数据库健康状态

```text
playermonitor status
playermonitor status full
```

普通 `status` 使用较精简的中文状态输出，`status full` 显示完整诊断字段。输出包括：

- Game Active、Reconnect Pending、Roster Reconciling、Reconnect Generation 和最近断线时间；
- Bot roster 与 PlayerMonitor roster 数量及漂移；
- Chat pipeline 计数，包括 system received、public parsed、monitor accepted、db committed、db failed、parse failed；
- Stat 扫描状态、在线人数、排队数、待发送数和活动周期数；
- 总体健康状态；
- SQLite writer 生命周期、线程状态、恢复中/卡住状态；
- 写入队列当前长度、容量、峰值和 1m/5m 增长；
- 最近一次成功提交、最近 Chat/Session/Stat/UUID 写入和最近一次失败；
- `failed-events.jsonl` 总行数、已重放、待处理和损坏数量；
- 主数据库、WAL 和 SHM 文件大小；
- 玩家、聊天、会话、Stat 和未结束会话数量；
- 自动备份调度、最近备份和下次备份时间。

旧指令 `playermonitor db-stat` 仍作为兼容别名保留，但不会出现在帮助和 Tab 补全中。

### 备份管理

```text
playermonitor backup now
playermonitor backup status
playermonitor backup list
playermonitor backup verify <filename>
playermonitor backup limit <count>
```

说明：

- `backup now`：立即 flush writer，使用 SQLite `VACUUM INTO` 创建备份，并在发布前执行完整性检查；
- `backup status`：显示调度器、备份进行状态、间隔、最近备份和下次备份；
- `backup list`：按时间从新到旧列出备份，最多显示 20 个；
- `backup verify`：只允许验证数据目录中的合法 `xinpm-auto-backup-YYYYMMDD-HHMMSS.db` 文件，并执行 `integrity_check` 和 `foreign_key_check`。
- `backup limit`：设置最多保留的数据库备份数量，默认 `3`；下一次成功备份后删除最旧的超额备份。

### 查询玩家

```text
playermonitor <玩家名>
playermonitor <玩家名> playerinfo
playermonitor <玩家名> stat
playermonitor <玩家名> uuid
playermonitor <玩家名> latestlogin
playermonitor <玩家名> recentlogin [count]
playermonitor <玩家名> chat [count]
```

示例：

```text
playermonitor Steve
playermonitor Steve stat
playermonitor Steve recentlogin 10
playermonitor Steve chat 20
```

直接输入玩家名或使用 `playerinfo` 会立即显示 SQLite 中缓存的综合玩家资料，包括已记录的服务器 UUID、发言次数、击杀、死亡、KD、首次记录、最近上下线、最近一次游玩时长、特殊付费权限、优先队列及预计到期时间、最近 5 条发言、总游玩时长、近 30 天游玩时长和加入游戏次数。启用 UUID 记录后，缺失或过期的身份资料会在后台刷新，不阻塞命令输出。`uuid` 子命令同样先读取 SQLite/cache，不等待远程身份服务。

`recentlogin` 和 `chat` 的临时查询数量范围为 `5–50`。不填写时，分别使用 `recentlogin-count` 和 `chat-count` 的当前设置。

## 可修改设置

`playermonitor setting` 显示当前设置；`playermonitor setting <option> <value>` 修改设置。设置修改后会立即写入 `playermonitor/settings.json`。

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
| `display-timezone` | 查询结果显示时间使用的 UTC 时区 | `UTC` |
| `recentlogin-count` | 最近登录默认显示数量 | `15`，范围 `5–50` |
| `chat-count` | 最近聊天默认显示数量 | `10`，范围 `5–50` |
| `uuidRecordEnable` | 是否为在线玩家定期刷新并记录 UUID 身份 | `false` |
| `uuidRecordCooldown` | 每名玩家两次成功 UUID 检查的最短间隔 | `168 h`；`0` 关闭定期复查 |
| `thirdPartyYggdrasilBaseUrl` | 第三方标准 Yggdrasil API 根地址 | `https://littleskin.cn/api/yggdrasil`；绝对 HTTP/HTTPS URL |
| `backup-interval` | 自动数据库备份间隔 | `168 h`，必须大于 `0` |

设置示例：

```text
playermonitor setting stat-enabled false
playermonitor setting stat-cooldown 12
playermonitor setting display-timezone UTC+08:00
playermonitor setting uuidRecordEnable true
playermonitor setting uuidRecordCooldown 168
playermonitor setting thirdPartyYggdrasilBaseUrl https://littleskin.cn/api/yggdrasil
playermonitor setting backup-interval 24
```

### 旧缓存设置兼容

`cache-idle` 和 `max-cached-history` 字段仍可保留在旧版 `settings.json` 中，以避免升级时破坏配置文件，但 SQLite 后端不使用旧版玩家历史缓存。

v1.5.0 起：

- 它们不再出现在 `playermonitor setting` 帮助和 Tab 补全中；
- 手动输入旧设置名时会明确提示该设置在 SQLite 后端无效；
- 它们不会删除或限制数据库历史数据。

## 数据目录

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
- `xinpm.db-wal`、`xinpm.db-shm`：SQLite WAL 运行文件；
- `settings.json`：插件设置；
- `failed-events.jsonl`：无法提交或无法进入 writer 队列的事件；
- `xinpm-auto-backup-*.db`：自动或手动创建的数据库备份；
- `migration-reports/`：旧数据迁移报告；
- `legacy-json-backup/`：迁移成功后归档的旧 `players/` 目录；
- `log/`：按日期生成的插件警告和错误日志。

不要在插件运行时删除、移动或替换 `xinpm.db`、`xinpm.db-wal` 或 `xinpm.db-shm`。

当前版本默认最多保留 `3` 个备份；可通过 `playermonitor backup limit <count>` 修改。只有在新备份成功并通过完整性检查后，才会删除最旧的超额备份。

## SQLite 写入与失败恢复

主要数据表：

- `players`：玩家身份、首次/最后出现和在线状态；
- `sessions`：登录与登出会话；
- `chat_messages`：公共聊天原始消息；
- `stat_snapshots`：每个玩家最新的 Stat 快照；
- `replayed_failed_events`：已经成功重放的失败事件 ID；
- `migration_state`、`legacy_migration_players`：旧数据迁移状态。

玩家名使用 `Locale.ROOT` 小写形式作为内部不区分大小写的 key，同时保留显示名称。

SQLite schema v4 在 `players` 中分别保存服务器、离线、Mojang 和第三方 UUID、身份分类、各外部服务检查时间，以及独立的 `uuid_last_checked_at` / `uuid_last_written_at`。Mojang 与第三方查询各自使用独立的 24 小时缓存；`uuidRecordCooldown` 只控制周期性数据库比较，设为 `0` 不创建循环任务，但服务器 UUID 变化仍会强制刷新。冷却以成功检查时间计算；只有身份值实际变化时才推进写入时间。

插件公开 `CompletableFuture<PlayerIdentity> resolve(String name)` API。相同玩家名的并发请求共享一次进行中的解析，外部 HTTP 查询限制为最多 4 路并发；在线玩家使用当前 GameProfile UUID，离线玩家回退到 SQLite 中最后记录的服务器 UUID。

### 队列与事务

- 所有正常写入通过有界队列交给单独的 SQLite writer；
- writer 按批次事务提交；
- 队列满、writer 进入 FAILED 或排队被拒绝时，事件不会只写日志后消失，而会追加到 `failed-events.jsonl`；
- writer 会对可恢复的 SQLite 连接错误执行 rollback、关闭旧连接、backoff 并重开连接；`CORRUPT`、`NOTADB`、`FULL` 才进入 terminal FAILED；
- watchdog 会告警 writer 卡住、队列长期积压和 chat 队列拒绝；
- `flush()` 会报告此前尚未确认的写入失败；
- 关闭时停止接收新事件，并尝试排空已经接受的事件。

### 失败事件自动重放

启动时，在 writer 线程开始前，插件会读取 `failed-events.jsonl`：

1. 解析每条失败记录；
2. 为记录生成稳定 SHA-256 事件 ID；
3. 检查 `replayed_failed_events`，跳过已经恢复的事件；
4. 在单独事务中重放事件并记录 event ID；
5. 保留原始 `failed-events.jsonl`，不自动删除或截断；
6. 将损坏或仍无法写入的记录保留为 pending，并在 `playermonitor status` 中显示。

该流程是幂等的：同一条已成功重放的事件在后续重启中不会再次写入。

### 异常退出会话恢复

正常关闭前未写入登出的 session 可能来自进程崩溃、强制关机或 JVM 被终止。冷启动后第一次进入 `Game` 时，插件会：

1. 关闭数据库中遗留的全部 open session；
2. 将玩家状态重置为离线；
3. 再根据当前完整 roster 为在线玩家建立新 session。

恢复时间使用本次进入 `Game` 的时间；这代表异常恢复边界，不应被当作精确的历史登出时刻。

### 连接、名单与聊天自愈

- 已经生成的 `PublicChatEvent` 不受 `gameActive` 限制，即使重连状态切换也会继续进入聊天写入流程；
- 连接 watchdog 会检测机器人已在 `Game`、网络正常但内部状态长期未恢复的情况，并重新同步名单；
- watchdog 会比较 Bot roster 与 PlayerMonitor roster，漂移持续超过宽限时间后自动补记缺失的登录或登出；
- 无法从 Bot roster 找到发送者时，聊天事件仍可使用回退身份继续记录；
- `playermonitor status` 可查看精简状态，`playermonitor status full` 可查看所有连接、名单、聊天和 writer 诊断字段。

## Stat 扫描与解析

- 进入 `Game` 后每 100 ms 观察 roster；非空名单连续稳定 500 ms 后开始扫描，最多等待 5 秒，旧 Game generation 的延迟任务会被丢弃；
- 自动入场与手动扫描共享一个有界批次。新加入者只有在从未成功记录 Stat 时才会扩充正在运行的批次；
- 优先级依次为从未成功记录 Stat、冷却已到期、冷却仍有效。自动扫描跳过冷却目标，手动扫描可将其放入最低优先级；
- 成功保存后会按 `last_stat_at + stat-cooldown` 安排在线重扫；冷却为 `0` 时不建立循环；
- Stat 重试发送前同时校验 Game generation、批次、终态和目标 token，过期任务静默丢弃；
- 正常完成只输出一次黄色汇总；断线、离开 Game、切服或关闭时取消批次且不输出完成信息；
- 判断新玩家只查询 `players` 表，不再读取其全部聊天和会话历史；
- Tab 补全使用内存玩家名快照，不再为了补全强制 flush；
- Stat 返回字段可以调整顺序；
- `特殊权限` 为可选字段，只要存在玩家名和至少一个可识别 Stat 字段，并遇到结束分隔线，即可完成解析；
- 只有系统返回的完整 `玩家不存在!` 消息会终止该玩家的 Stat 周期；玩家发出的同名聊天不会触发；
- 系统确认玩家不存在时会取消该玩家已排队的重试，并输出一条明确 WARN；
- StatQueue 关闭后拒绝新任务，并避免 executor 关闭期间继续 schedule。

自动扫描优先级：

1. 新出现玩家；
2. 普通加入玩家；
3. 进入 `Game` 时扫描到的在线玩家。

手动 `scan stat` 优先级最高；`scan uuid` 会忽略 UUID 冷却，按 10 ms 间隔逐个调度当前 Game 在线玩家，重叠的完整 UUID 扫描会被忽略。

## 自动备份

自动备份默认每 `168` 小时执行一次：

```text
playermonitor setting backup-interval <hour>
```

备份流程：

1. 等待 writer 当前写入完成；
2. 使用 SQLite `VACUUM INTO` 创建独立临时数据库；
3. 执行 `PRAGMA integrity_check`；
4. 校验通过后移动为正式备份文件；
5. 使用 UTC 时间生成文件名。
6. 删除超出保留上限的最旧备份。

同一时间只允许一个备份任务执行。

## 旧数据迁移

插件会检测：

```text
playermonitor/players/<玩家名>/profile.json
playermonitor/players/<玩家名>/sessions.jsonl
playermonitor/players/<玩家名>/chat.jsonl
playermonitor/players/<玩家名>/stats.jsonl
```

迁移流程：

1. 检查 SQLite 是否已有无法确认来源的数据；
2. 检查仅大小写不同的重复玩家目录；
3. 读取并校验旧 JSON/JSONL；
4. 在事务中写入 SQLite；
5. 核对玩家、会话、聊天和 Stat 数量；
6. 写入 `migration-reports/`；
7. 全部成功后将旧 `players/` 移到 `legacy-json-backup/`。

默认使用严格迁移模式。`database.allowPartialLegacyMigration=true` 可允许跳过损坏的 JSONL 行，但仅应在完整备份原始数据后使用。

## 高级数据库配置

以下字段位于 `settings.json` 的 `database` 对象中：

| 字段 | 默认值 | 允许范围或说明 |
|---|---:|---|
| `path` | `xinpm.db` | 相对路径以 `playermonitor/` 为基准，也可使用绝对路径 |
| `queueCapacity` | `50000` | `1000–500000` |
| `batchSize` | `250` | `1–5000` |
| `flushIntervalMs` | `250` | `10–5000` |
| `busyTimeoutMs` | `10000` | `1000–60000` |
| `cacheSizeKiB` | `32768` | `1024–262144` |
| `shutdownFlushTimeoutMs` | `30000` | `1000–120000` |
| `allowPartialLegacyMigration` | `false` | 是否允许旧迁移跳过损坏 JSONL 行 |

修改高级数据库配置前必须停止 XinBot，并备份完整数据目录。

## 构建与测试

```bash
mvn clean test package
```

生成文件：

```text
target/XinPlayerMonitor-v1.5.8.jar
```

升级发布前至少验证：

```text
playermonitor status
playermonitor backup now
playermonitor backup status
playermonitor backup list
playermonitor backup verify <刚创建的文件名>
```

## License

本项目使用仓库中的 `LICENSE` 文件所声明的许可证。
