# XinPlayerMonitor

## 介绍

XinPlayerMonitor 是一个用于 XinBot 的玩家记录插件。插件只在机器人进入 `Game` 状态后工作，主要记录：

- 玩家登录、登出和每次在线会话；
- 玩家公共聊天；
- 玩家 Stat 数据；
- 玩家首次出现、最后出现和当前在线状态。

玩家历史会永久写入硬盘。聊天和会话使用追加式文件保存；Stat 只保存每名玩家最新一次成功结果，新结果会原子替换旧结果。

## 指令

### 查看当前设置

```text
/playermonitor setting
```

### 手动扫描全部在线玩家

```text
/playermonitor scan-stat
```

手动扫描不受 `stat-enabled` 和自动扫描冷却限制影响，但只能在机器人处于 `Game` 状态时使用。

### 设置指令

| 指令 | 作用 | 默认值与范围 |
|---|---|---|
| `/playermonitor setting scan-on-entry <true\|false>` | 进入 `Game` 时是否自动扫描当前全部在线玩家 | 默认 `true` |
| `/playermonitor setting disconnect-timeout <minute>` | 断线后等待多少分钟再确认并保存登出 | 默认 `10`，必须大于 `0` |
| `/playermonitor setting stat-enabled <true\|false>` | 自动 Stat 扫描总开关 | 默认 `true` |
| `/playermonitor setting stat-send-interval <ms>` | 连续发送两条 Stat 指令之间的间隔 | 默认 `500`，必须大于 `0` |
| `/playermonitor setting stat-output-hide <true\|false>` | 是否隐藏自动 Stat 返回内容 | 默认 `true` |
| `/playermonitor setting stat-cooldown <hour>` | 同一玩家两次自动 Stat 扫描的最短间隔 | 默认 `24`，范围 `0–168`，`0` 表示关闭冷却 |
| `/playermonitor setting stat-timeout <ms>` | 发送 Stat 后等待响应的时间 | 默认 `3000`，范围 `1000–30000` |
| `/playermonitor setting stat-attempts <count>` | 单个玩家一次 Stat 周期的最大总尝试次数 | 默认 `4`，范围 `1–10` |
| `/playermonitor setting scan-on-join <true\|false>` | 玩家加入时是否自动加入 Stat 队列 | 默认 `true` |
| `/playermonitor setting prioritize-join-stat <true\|false>` | 加入玩家是否优先于进入 Game 时的在线名单 | 默认 `true` |
| `/playermonitor setting display-timezone <timezone>` | 查询命令显示时间使用的 UTC 时区 | 默认 `UTC` |
| `/playermonitor setting recentlogin-count <count>` | 近期登录默认显示条数 | 默认 `15`，范围 `5–50` |
| `/playermonitor setting chat-count <count>` | 聊天记录默认显示条数 | 默认 `10`，范围 `5–50` |
| `/playermonitor setting cache-idle <minute>` | 玩家缓存空闲多久后可以释放 | 默认 `30`，范围 `5–1440` |
| `/playermonitor setting max-cached-history <count>` | 每名玩家每类历史在内存中最多缓存多少条 | 默认 `200`，范围 `50–10000` |

`display-timezone` 支持 Tab 补全，从 `UTC-12:00` 到 `UTC+14:00`，包括半小时和四十五分钟时区，例如：

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

也可以输入简写，例如 `UTC+8`，保存时会自动规范为 `UTC+08:00`。

### 玩家查询

```text
/playermonitor <player> stat
/playermonitor <player> latestlogin
/playermonitor <player> recentlogin [count]
/playermonitor <player> chat [count]
```

`recentlogin` 和 `chat` 的 `count` 可以临时覆盖默认显示数量，允许范围为 `5–50`：

```text
/playermonitor Steve recentlogin 10
/playermonitor Steve chat 20
```

不填写数量时，分别使用 `recentlogin-count` 和 `chat-count`。Tab 补全会给出当前配置中的默认值。

## 注意事项

1. 所有设置都会立即写入 `playermonitor/settings.json`，不需要重启插件。
2. 时区、查询数量和缓存设置会立即生效。
3. 如果当前已有 Stat 请求正在执行，新的 Stat 相关设置会先保存，等当前 Stat 请求周期结束后再切换，避免同一次请求中途改变超时、尝试次数或发送规则。
4. 自动扫描优先级为：新出现的玩家 > 普通加入玩家 > 进入 `Game` 时扫描到的在线玩家。手动 `scan-stat` 优先级最高。
5. 关闭 `stat-enabled` 只关闭自动扫描，不影响手动 `scan-stat`、玩家查询或已有历史数据。
6. `stat-cooldown` 只影响自动扫描，手动扫描会忽略冷却。
7. 玩家名目前按不区分大小写的方式建立内部索引。已有目录的原始大小写会保留；发现仅大小写不同的两个目录时，插件会阻止自动写入并要求人工处理。
8. 迁移成功后旧版单文件 JSON 会被删除，不额外保留备份。正式升级前建议自行备份整个 `playermonitor` 目录。
9. 日志、玩家目录和正常历史文件不会被插件自动删除；插件只会清理自己创建但未完成的临时文件和迁移临时目录。

## 数据目录

```text
playermonitor/
├── settings.json
├── players/
│   └── Steve/
│       ├── profile.json
│       ├── sessions.jsonl
│       ├── chat.jsonl
│       └── stats.jsonl
└── log/
    └── playermonitor-2026-07-20.log
```

- `profile.json`：玩家身份、首次和最后出现时间、在线状态及未结束会话；
- `sessions.jsonl`：每行一条已结束的登录会话；
- `chat.jsonl`：每行一条公共聊天；
- `stats.jsonl`：只保存最新一次成功解析的 Stat 快照，文件始终最多一条有效记录；
- `settings.json`：插件设置；
- `log/`：按日期生成的运行日志。

普通 JSON 用于保存小型当前状态。`sessions.jsonl` 和 `chat.jsonl` 用于持续增长的历史记录，只在末尾追加；`stats.jsonl` 仍采用一行 JSON 的格式，但每次成功获取 Stat 时会原子替换旧内容，只保留最新一条。

## 旧数据迁移

插件启动时会自动识别以下旧结构：

```text
playermonitor/<玩家名>.json
playermonitor/players/<玩家名>.json
```

迁移流程：

1. 读取并解析旧玩家记录；
2. 在 `xpm-migration-*` 临时目录生成四个新文件；
3. 检查玩家名和各类记录数量；旧格式中若有多条 Stat，只迁移 `capturedAt` 最新的一条；
4. 完整验证后再移动到正式玩家目录；
5. 正式目录建立成功后删除旧 JSON。

如果目标目录已经存在、迁移验证失败或移动失败，旧文件会保留，正式目录不会被覆盖，也不会留下半成品目录。

## 损坏文件处理

- 损坏的 JSON 会先重命名为 `.corrupted` 或 `.corrupted-2` 等隔离文件；
- 隔离失败时会阻止继续写入，避免用空数据覆盖原文件；
- JSONL 中单独损坏的一行会被跳过，其余合法记录会按原顺序保留；
- 修复文件替换失败时会尝试恢复原文件；恢复也失败时会阻止该玩家继续自动写入；
- `.corrupted` 和 `xpm-migration-*` 目录不会出现在玩家列表或 Tab 补全中。

## Stat 队列

每名玩家的一次 Stat 周期最多发送 `stat-attempts` 次，包括第一次发送。发送异常和响应超时都会计入尝试次数。

达到上限后，插件会：

- 清除该玩家的等待、重试和进行中状态；
- 写入警告日志；
- 继续处理队列中的下一个玩家；
- 不会在同一周期中无限重新加入队列。

## 构建

项目使用 Java 17 和 Maven：

```bash
mvn clean test
mvn package
```

生成文件：

```text
target/XinPlayerMonitor-v1.3.1.jar
```
