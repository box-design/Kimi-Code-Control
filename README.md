# Kimi Code Control

**Kimi Code 原生 Android 控制台** —— 基于 Kotlin + Jetpack Compose (Material 3) 的伴侣应用，对接在 Termux（或同网段设备）中本地运行的 [MoonshotAI/kimi-code](https://github.com/MoonshotAI/kimi-code)（kap-server）服务器。

原生层通过 **REST `/api/v1` + WebSocket `/api/v1/ws`** 直连本地服务器，把 Kimi Code 的任务状态、审批请求、套餐用量带进 Android 系统层：**通知、桌面小组件、悬浮窗、前台服务**——全程零 OAuth / API key 处理，凭据始终保存在服务器端。

- 当前版本：**v0.5.10**（versionCode 17）
- 环境要求：Android 8.0（API 26）及以上

---

## 目录

- [功能一览](#功能一览)
- [快速开始](#快速开始)
- [界面结构](#界面结构)
- [架构与数据流](#架构与数据流)
- [服务器协议要点](#服务器协议要点)
- [桌面小组件](#桌面小组件)
- [悬浮窗](#悬浮窗)
- [通知系统](#通知系统)
- [设置项](#设置项)
- [从源码构建](#从源码构建)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [技术亮点](#技术亮点)
- [已知限制](#已知限制)
- [版本历史](#版本历史)

---

## 功能一览

| 功能 | 说明 |
|------|------|
| **审批强提醒** | Kimi Code 需要执行命令 / 写文件等审批时，高优先级横幅通知（震动 + 提示音、锁屏可见），通知上或应用内可直接 **允许 / 拒绝 / 本会话均允许** |
| **问题提醒** | Kimi Code 需要你的回答时（`event.question.*`）同构提醒，一键打开应用回答 |
| **任务完成提醒** | 任务结束（`turn.ended` / `task.terminated`）发送结果摘要通知；默认防打扰：应用前台时静默、仅提醒运行超过 5 分钟的任务 |
| **任务状态横幅** | 任务运行时屏幕顶部横幅（heads-up）实时展示执行状态，划走后常驻通知栏，任务结束自动移除；多任务并行时最早的任务上横幅 |
| **忙碌状态常驻通知** | 前台服务持有 WebSocket，任意会话忙碌时显示「正在工作 · 已运行 X 分钟」；全部空闲且无待交互 60 秒后自动撤除 |
| **控制台单页** | 连接状态 → 套餐用量（5 小时 / 7 天 / 本月配额进度条 + 加油包余额）→ 最近任务（进行中 / 已完成两栏） |
| **任务详情页** | Token 用量（本轮 / 累计 / 上下文窗口占比、模型与思考强度）、AI 实时状态（官方英文文案 + 动态模糊切换动画）、实时思考流、Todo 三态列表、Goal 目标（暂停 / 恢复 / 取消）、Plan / Swarm 模式开关与子代理实时列表 |
| **工作状态悬浮窗** | 系统级悬浮窗（灵动岛式胶囊 ↔ 卡片），任务运行时实时展示状态，详见 [悬浮窗](#悬浮窗) |
| **桌面状态小组件** | Glance 3×2 胶囊，6 种状态实时刷新，详见 [桌面小组件](#桌面小组件) |

---

## 快速开始

### 1. Termux 侧：启动 Kimi Code 服务器

```sh
# 安装 Kimi Code（官方脚本，免 Node.js）
curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash

# 启动服务器（--no-open 避免调用浏览器）
kimi web --no-open
# 可选：固定端口 kimi web --no-open --port 58627
# 可选：免 Token 模式（仅 loopback，风险自担）
#   kimi web --no-open --dangerous-bypass-auth

# 获取访问令牌（App「高级选项」中填写；--dangerous-bypass-auth 时留空）
cat ~/.kimi-code/server.token
```

### 2. 服务器侧：完成模型登录

模型登录（Kimi OAuth / API key）等服务器侧配置，请在**其他设备的浏览器**中打开 `http://<host>:<port>` 完成一次即可，凭据保存在服务器端。

> v0.4 起 App 内不再承载 Web UI，App 侧连接不受影响。

### 3. App 侧：连接

打开 App → 连接页填写 Host / 端口（默认 `127.0.0.1:58627`）→ 连通性测试失败时会**自动扫描端口**（58627+1 … +9）→ 需要鉴权时在「高级选项」填入 Token → 连接成功进入控制台。

---

## 界面结构

```
连接页 ConnectScreen
  │  Host/端口编辑 · 连通性测试 · 端口自动扫描 · Token 高级选项 · 防打扰选项
  ▼
控制台 ConsoleScreen（单页）
  │  顶部固定「Kimi Code Control」标题栏 + 设置/刷新/更改服务器
  │  连接状态卡片 → 套餐用量 → 最近任务（进行中 / 已完成）
  ├──────────────────────────────┐
  ▼                              ▼
任务详情页 TaskDetailScreen    设置 SettingsScreen（主页）
  Token / AI 状态 / 思考流 /      ├─ 通知子页
  Todo / Goal / Plan / Swarm     └─ 样式子页
```

页面转场：部分位移 + 交叉淡入淡出 + MD3 emphasized 曲线（低版本降级），子页返回行为与根导航一致。

---

## 架构与数据流

```
kap-server (Termux, 默认 127.0.0.1:58627)
   │  REST /api/v1（Bearer token）        WS /api/v1/ws（长连接）
   ▼                                      ▼
KimiApi（OkHttp REST 客户端）      KimiWs（OkHttp WS 客户端）
   └──────────────┬──────────────────────┘
                  ▼
        Repository（状态聚合：会话 / 审批 / phase / 详情 / 用量）
                  ▼
   ┌──────────────┼──────────────────────────┐
   ▼              ▼                          ▼
MonitorService  Compose UI                Glance 小组件
（前台服务 dataSync：
 持有 WS / 驱动通知 / 任务横幅 /
 悬浮窗调度 / 用量周期刷新 / 空闲退出）
   │
   ▼
FloatingWindowService（悬浮窗，TYPE_APPLICATION_OVERLAY）
```

- **MonitorService**：前台服务（`foregroundServiceType="dataSync"`）持有 WebSocket 长连接，是所有事件 → 通知 / 小组件 / 悬浮窗的中枢；空闲且无待交互一段时间后自动退出，App 打开时重新拉起。
- **Repository**：聚合连接状态、会话列表、审批 / 问题、小组件状态、会话详情、思考流、实时任务与用量，UI 与后台共享同一数据源。
- **小组件更新链路**：WS 事件 → Repository 聚合（去重）→ Glance 即时重绘；服务被系统回收后由小组件 30 分钟周期更新做 REST 快照自愈。

---

## 服务器协议要点

### REST（`/api/v1`，除 `healthz` 外均需 `Authorization: Bearer <token>`）

| 端点 | 用途 |
|------|------|
| `GET /healthz` | 连通性探测（免鉴权） |
| `GET /meta` | 服务器元信息 / 版本 |
| `GET /sessions` / `GET /workspaces` | 会话 / 工作区列表 |
| `POST /sessions` · `POST /sessions/{sid}/prompts` | 建会话 / 发提示 |
| `GET /sessions/{sid}/approvals?status=pending` · `POST /sessions/{sid}/approvals/{aid}` | 拉取 / 决策审批 |
| `GET /sessions/{sid}/questions?status=pending` | 拉取待回答问题 |
| `GET /oauth/usage?provider=managed:kimi-code` | 套餐用量（5h / 7d / 月度配额 + 加油包） |
| `GET /sessions/{sid}/status` · `/transcript` · `/goal` · `/tasks` · `POST /sessions/{sid}/profile` | 会话状态 / 终端记录 / 目标 / 任务列表 / 模式开关 |

### WebSocket（`/api/v1/ws`）

- 握手：连接后等服务端 `server_hello` → 发送 `client_hello`（携带 `client_id`）→ `subscribe` 订阅全部会话
- 心跳：服务端每 10s 发 `ping`，客户端必须回 `pong`（携带 nonce）；断线指数退避重连，收到 `resync_required` 时全量重新同步
- 消费的主要事件：
  - 审批 / 问题：`event.approval.requested / resolved`、`event.question.requested / answered / dismissed`
  - 会话与任务：`event.session.created`、`event.session.work_changed{busy, pending_interaction, last_turn_reason}`、`turn.started / ended`、`task.started / terminated`
  - 执行状态：`agent.status.updated{phase, usage, planMode, swarmMode, contextTokens}`、`tool.call.started{display}`、`thinking.delta`（思考流）
  - Goal / Swarm：`goal.updated{snapshot}`、`subagent.spawned / started / suspended / completed / failed / cancelled`

---

## 桌面小组件

Glance 3×2 胶囊「Kimi 状态」，共 **6 种内置状态**，判定优先级从高到低：

| 状态 | 主文案 | 颜色 | 触发条件 |
|------|--------|------|---------|
| 未连接 | 未连接 · Offline | 中性灰 | WS 未连接 |
| 等待审批 | 等待审批 · Approval | 醒目橙 | `pending_interaction=approval` 或 `phase=awaiting_approval` |
| 执行命令 | 执行命令 · Shell | Kimi 靛蓝 | `phase=tool_call` 且 `display.kind=command` |
| 写文件 | 写文件 · Write | Kimi 靛蓝 | `display.kind=file_io/diff` |
| 执行工具 | 执行工具 · {工具名} | Kimi 靛蓝 | `phase=tool_call/retrying` 其他 kind |
| 思考中 | 思考中 · Thinking | Kimi 靛蓝 | 会话忙碌且 `phase=running`（或无细粒度信息） |
| 空闲 | 空闲 · Idle | 深灰蓝 | 已连接且全部空闲、无待审批 |

副文案：等待审批时为「点按打开控制台」；否则显示「N 个任务进行中」或「没有进行中的任务」。任一状态点击胶囊均打开 App。

---

## 悬浮窗

**工作状态通知的「详细模式」**（v0.5.4+）：任务运行时以 `TYPE_APPLICATION_OVERLAY` 系统级悬浮窗展示（需授予「显示在其他应用上层」权限，设置页有授权入口）。

- **胶囊形态（灵动岛式）**：「任务正在进行」+ 右侧 AI 实时执行状态——与详情页同款文案与动画（braille / moon spinner、Thinking… / Working… / Using X (y) 官方原文、状态切换动态模糊）
- **卡片形态**：完整信息（状态 + 相位细节 + Todo 进度条 + Swarm / Goal 摘要 + 多任务列表），点击直达任务详情；标题栏可拖动，位置 / 形态持久化
- **提示卡**：审批 / 问题 / 完成提醒可直接在悬浮窗内处理（允许 / 拒绝 / 本会话均允许），完成后自动缩回
- **交互细节**：每次出现从胶囊起始；点击胶囊 spring 非线性展开；点卡片外任意空白自动收回；最后一个任务完成后先展示「任务完成」卡 5 秒再丝滑退场
- **架构**：窗口尺寸即挡位（紧贴内容小窗 + `FLAG_NOT_TOUCH_MODAL` 天然穿透），不吞下层触摸、不触发 Android 12+ untrusted-touch 拦截；Android 12+ BLAST 原子换挡无闪烁

---

## 通知系统

6 个通知通道，可在系统设置中分别调整声音与行为：

| 通道 | 优先级 | 用途 |
|------|--------|------|
| `approval` | 高 | 审批强提醒（横幅 + 震动，锁屏可直接操作） |
| `question` | 高 | 问题提醒（Kimi Code 需要你的回答） |
| `task_banner` | 高（静音） | 任务运行实时横幅（划走后常驻，结束自动移除） |
| `completion` | 默认 | 任务完成摘要 |
| `work` | 低 | 忙碌状态常驻通知（极简模式：「正在工作 · 已运行 X 分钟」） |
| `task_bg` | 低 | 其他并行任务（静默常驻） |

通知上的审批快捷操作（允许 / 拒绝 / 本会话均允许）由 `ApprovalActionReceiver` 直接提交服务器决策，无需打开 App。

---

## 设置项

**设置主页**（`设置 → …` 分类子页结构，底部显示应用名与版本号）：

- **通知**
  - 任务状态横幅：开 / 关
  - 工作状态通知模式：极简通知 / 悬浮窗面板（详细模式，需悬浮窗权限）
  - 提醒方式（审批 / 问题 / 完成）：悬浮窗提示卡 / 系统通知
  - 审批提醒 / 问题提醒 / 任务完成提醒：各自独立开关
- **样式**
  - MD3 动态取色（Material You 壁纸取色）：默认开启；关闭后可在 **靛蓝（Kimi 品牌色）/ 翠绿 / 琥珀 / 玫瑰** 四种经典配色中自选，均含明暗两套 MD3 方案
  - 减少模糊：开启时关闭全局附加模糊（页面转场动态模糊、毛玻璃顶栏高斯模糊等）

**连接页**：防打扰（应用前台静默 / 仅提醒运行超 5 分钟的任务）。

---

## 从源码构建

环境：Android Studio（Ladybug+）、JDK 17。

```sh
# Debug 构建
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 构建
./gradlew assembleRelease
# 需要项目根目录 keystore.properties（storeFile / storePassword / keyAlias / keyPassword）
# 未提供时 release 不签名（可随后用 apksigner 补签）
```

或用 Android Studio 打开项目根目录，Sync 后 Run。

> **签名密钥不入库**：`keystore.properties` 与 `*.keystore` 已被 `.gitignore` 排除，请自备签名配置。

---

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.0.21 |
| AGP | 8.5.2 |
| Compose BOM | 2024.10.01（Material 3） |
| Glance（小组件） | 1.1.1 |
| OkHttp（REST + WS） | 4.12.0 |
| kotlinx.serialization | 1.7.3 |
| DataStore Preferences | 1.1.1 |
| Lifecycle | 2.8.7 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |

---

## 项目结构

```
app/src/main/java/com/kimicode/companion/
├── KimiApp.kt                    # Application + 依赖容器 + 前台检测 + 全局协程异常兜底
├── MainActivity.kt               # 单 Activity 入口
├── data/
│   ├── Models.kt                 # kap-server 线协议模型（snake_case + camelCase 双命名域）
│   ├── Settings.kt               # DataStore：host / port / token / 通知 / 样式设置
│   ├── KimiApi.kt                # REST 客户端（探测 / 会话 / 审批 / 用量 / 详情 / 模式）
│   ├── KimiWs.kt                 # WS 客户端（握手 / 心跳 / 重连 / 事件解析）
│   └── Repository.kt             # 状态聚合（会话 / 审批 / phase / 详情 / 用量 → UI 与小组件）
├── service/
│   └── MonitorService.kt         # 前台服务：事件→通知→小组件；悬浮窗调度；用量刷新；空闲退出
├── notify/
│   ├── Notifications.kt          # 通知中心（6 通道）
│   └── ApprovalActionReceiver.kt # 通知上的审批快捷操作
├── overlay/
│   ├── FloatingWindowService.kt  # 悬浮窗服务（三态窗口）
│   └── FloatingWindowController.kt
├── widget/
│   ├── StatusWidget.kt           # Glance 3×2 小组件
│   ├── StatusWidgetReceiver.kt
│   └── WidgetStore.kt
└── ui/
    ├── AppRoot.kt                # 根导航：连接 ↔ 控制台 ↔ 详情 ↔ 设置
    ├── ConnectScreen.kt          # 连接页（探测 / 端口扫描 / Token）
    ├── ConsoleScreen.kt         # 控制台单页（毛玻璃顶栏）
    ├── TaskDetailScreen.kt       # 任务详情页
    ├── SettingsScreen.kt         # 设置（主页 + 通知 / 样式子页）
    ├── FloatingWindowContent.kt  # 悬浮窗内容（胶囊 / 卡片 / 提示卡）
    ├── KimiStatus.kt            # AI 状态机（官方文案移植 / spinner 动画）
    ├── StatusUiShared.kt         # 状态 UI 共享组件
    ├── Glass.kt                 # 毛玻璃（RenderEffect 高斯动态模糊）
    ├── Motion.kt                 # MD3 emphasized 动效曲线 / 弹簧
    └── Theme.kt                  # MD3 主题（动态取色 / 四配色）
```

---

## 技术亮点

- **真实高斯毛玻璃顶栏（Android 12+）**：共享 `GraphicsLayer` 实时录制滚动内容，在顶栏范围内取样叠加 `RenderEffect` 高斯模糊——滚动内容在玻璃后真实雾化，模糊半径随滚动状态动态过渡（16dp 轻雾 ↔ 28dp）；低版本自动退化为半透明蒙层
- **悬浮窗三态架构**：「窗口尺寸即挡位」——紧贴内容小窗 + `FLAG_NOT_TOUCH_MODAL`，规避 Android 12+ untrusted-touch 事件拦截（穿透不可信覆盖层会被系统丢弃），下层应用点按 / 滑动全部正常；`FLAG_WATCH_OUTSIDE_TOUCH` + `ACTION_OUTSIDE` 实现「点窗外收起」
- **无闪烁换挡**：窗口换挡与内容重排同帧生效（Android 12+ BLAST 原子应用），胶囊跨挡位逐像素同位
- **ViewTree Owner 完备**：自定义 `OverlayRootView` 根视图上补全 `ViewTreeLifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner` 三个标签，满足 Compose WindowRecomposer 从窗口根解析生命周期的机制（否则悬浮窗首次 attach 即崩溃）
- **AI 状态文案逐字移植**：主状态机 / 工具调用卡片 / Read 分组 / 审批头 / 重试 / 子代理状态等文案与 spinner 帧序列（braille 80ms / moon 120ms）逐字移植自 Kimi Code 官方 TUI 源码
- **状态切换动态模糊**：状态文案变化时旧文案带模糊滑出（0→9dp）、新文案从下方模糊滑入逐渐聚焦；Android 12+ 走 `RenderEffect`，低版本优雅降级
- **防崩溃加固**：主线程 DataStore 读取异常保护、全局协程异常兜底（DataStore 损坏不再炸进程）、widget 写入全链路 `runCatching`、WS 启动（URL / header 非法）不抛出

---

## 已知限制

- 会话终端 / 消息流式展示（assistant.delta）等模块在后续版本开发
- Goal 的创建需在 Termux 侧以 goal 模式发起，App 内支持暂停 / 恢复 / 取消
- 套餐用量依赖服务器 OAuth（managed:kimi-code），上游不可用时显示降级文案
- 监控服务长时间空闲退出后，服务器侧新触发的新任务要等下一次小组件周期刷新（≤30 分钟）或打开 App 才会收到提醒（Android 12+ 后台启动前台服务受系统限制）
- 服务器侧模型登录（OAuth / API key）需在浏览器中完成一次

---

## 版本历史

| 版本 | 要点 |
|------|------|
| **v0.5.10** | 顶栏毛玻璃重制为真实高斯动态模糊（共享 GraphicsLayer + RenderEffect，Android 12+）；移除 Aurora 光斑；「减少模糊」开关覆盖页面转场模糊 |
| v0.5.9 | 新增「样式」设置（MD3 动态取色 / 四种经典配色 / 减少模糊）；动效全面升级（emphasized 曲线、按压回弹、animateItem 弹簧重排、阶梯式入场）；顶栏固定置顶与 edge-to-edge 修正 |
| v0.5.8 | 修改服务器页标题统一；顶栏固定置顶；卡片操作按钮上移至顶栏，「更多」菜单替换为「更改服务器」直达 |
| v0.5.7 | 应用更名「Kimi Code Control」；设置页重构为「主页 + 分类子页」；通知设置按「运行状态 / 提醒 / 权限」重新分组并统一术语 |
| v0.5.6 | 根治「悬浮窗在场时下层应用点按失灵」（untrusted-touch 拦截）：全 API 26+ 统一「紧贴内容小窗」架构，废除全屏窗口 + setTouchableRegion 路由 |
| v0.5.5 | 悬浮窗交互重制（胶囊起始 / spring 展开 / 点外收回）；审批 / 问题 / 完成提醒进悬浮窗提示卡；提醒方式设置 |
| v0.5.4 | 工作状态悬浮窗（详细模式，胶囊 / 卡片两形态）；最近任务两栏（进行中 / 已完成） |
| v0.5.3 | AI 状态显示重制：Kimi Code 官方文案逐字移植 + 状态切换动态模糊动画；实时数据通道扩展 |
| v0.5.2 | 通知中心重制（6 通道）；任务实时横幅；工作通知极简 / 详细双模式；小组件与详情页增强 |
| v0.5.1 | UI 全面圆角化（MD3） |
| v0.5.0 | 修复连接后闪退（协程异常兜底）；控制台单页重制；任务详情页（Token / Todo / Goal / Swarm）；套餐用量 |
| v0.4 | 移除内置 Web UI，专注原生能力；ShareActivity 移除 |

---

## License

本项目仅供学习与个人使用。Kimi Code 是 Moonshot AI 的产品，本项目与其无官方关联。
