# zcode-mobile 交接报告

> 交接日期：2026-09-26
> 项目路径：`E:/open_trae_m/zcode-mobile`
> 交接时状态：**协议面全部落地，87 个单元测试全绿，真机验证通过**

---

## 1. 这是什么

**zcode-mobile** —— ZCode（Z.ai 的编码 agent）的**非官方第三方 Android 客户端**。

连接方式支持三种：自建直连、Z.ai 托管中继链接（`zcode.z.ai/remote/v4?...`）、桌面端扫码配对。

架构选择（已定，不要重开讨论）：**原生 Kotlin + Compose，协议自己实现**。不是 WebView 套壳，也不是复刻 Trae Solo APK（那条路已论证不可行，见 `docs/TRAE-SOLO-ANALYSIS.md`）。

包名刻意用 `dev.zcodemobile.app`，**不在 zcode.ai 命名空间下** —— 仓库是 Apache-2.0，但授权不含商标权。

---

## 2. 模块布局

```
zcode-mobile/
├── protocol/         纯 Kotlin JVM，零 Android 依赖（可在 JVM 上跑测试和验证）
│   ├── Vql.kt                 通道载荷二进制编解码
│   ├── Json.kt                无依赖 JSON codec
│   ├── RelayTransport.kt      中继 WebSocket：鉴权 / bootstrap / bridge / rpc-frame
│   ├── ChannelClient.kt       RPC 复用层（call / listen）
│   ├── Commands.kt            ★ 34 个命令构造器 + CAS 编译期强制
│   ├── ConversationApi.kt     ★ zcode-agent 通道全部方法
│   ├── ConversationReducer.kt ★ 快照/增量 → 客户端投影（纯函数）
│   ├── SessionsIndex.kt       ★ 会话列表 topic
│   ├── WorkspaceConfig.kt     ★ 工作区配置 topic
│   ├── SettingsService.kt     ★ 全局设置（白名单投影）
│   ├── ModelCatalog.kt        模型目录（含密钥投影）
│   ├── ContextUsage.kt        上下文用量解析
│   ├── FileChanges.kt         文件改动本地推导
│   ├── GitInfo.kt             仓库/分支
│   └── TaskParsing.kt         bootstrap 任务列表
├── verify/           JVM 实机验证脚手架（连真实中继）
├── app/              Android/Compose 客户端
│   ├── session/ZCodeSession.kt        编排 + 状态（StateFlow）
│   ├── MainActivity.kt                ViewModel + 导航 + 列表投影
│   ├── data/LinkStore.kt              链接存储（Keystore 加密）
│   ├── ui/conversation/               会话页 / 渲染器 / 队列面板 / 行操作
│   ├── ui/sessions/                   会话列表 / 添加链接 / 行模型
│   ├── ui/settings/                   设置页
│   ├── ui/scan/                       二维码解码
│   └── ui/theme/, ui/components/      主题 / Markdown 渲染
├── tools/relay-probe/  Node 参考实现 + 抓包工具（见 §6）
└── docs/               协议文档（见 §5）
```

★ = 2026-09-26 这轮新增或大改。

---

## 3. 当前状态

### 3.1 已验证可用（不是"能编译"，是"跑通了"）

**协议层**
- 鉴权握手（`auth_init` → `auth_challenge` → `auth_response` → `auth_ack`），HMAC 证明与线上网页端逐字节一致
- bootstrap（5 工作区 / 47 会话）、workspace bridge
- 会话订阅：快照 + 实时增量（`row.upserted` / `row.delta` / `state.updated` 实测收到过）
- 历史翻页（`conversationRowsRangeV4`，实测 120/1740 行）
- 会话列表实时流（`subscribeSessionsIndexV4`）
- 工作区配置流（`subscribeWorkspaceConfigV4`）
- 模型目录（`model-selection.getView`，实测加载出 4 个供应商）
- 全局设置读取（`setting.get`，43 个键，投影后无凭据泄漏）
- 附件上传三步事务（返回真实 `zcode-artifact://` ref）
- **写路径**：`setAssistantFeedback` 真机发出并被 `accepted`

**UI（真机截图/层级确认）**
- 首页任务列表：项目分组 / 全局时间线两种视图，更新时间 / 创建时间排序，置顶优先，搜索；多会话并行（含跨文件夹并行）时顺序稳定，项目分区不会互相顶替
- 会话页：Markdown 渲染、流式增量、历史翻页、粘底滚动
- 状态面板：目标、队列、后台任务（实测渲染出 "失败" / "结果待投递" 状态）
- 模型 / 思考档位 / 模式 / 分支 / 后续模式选择器
- 消息行操作菜单（编辑 / 编辑并回退 / 重试 / 分叉 / 评价）
- 会话管理：新建、重命名（含"手动命名后不再自动改标题"提示）、关闭
- 设置页：描述符驱动，只读信息区
- 断开恢复：错误提示 + "重新连接"按钮

### 3.2 构建产物

| 产物 | 大小 | 说明 |
|---|---|---|
| `app-debug.apk` | 24.9 MB | 未混淆 |
| `app-release-unsigned.apk` | **2.48 MB** | R8 + 仅 arm ABI + ZXing（不用 ML Kit） |

Release **未签名** —— 要发布得自己配 signingConfig。

### 3.3 测试

**109 个单元测试全绿**：`protocol` 90 个 + `app` 19 个（含首页列表投影 `HomeProjectionTest` 14 个）。
另有 `app/src/androidTest/` 的仪器测试（`RelayInstrumentedTest` 需真实中继链接）。

---

## 4. 怎么构建 / 怎么跑

### 4.1 ⚠️ 没有 Gradle Wrapper

项目根目录**没有 `gradlew`**，PATH 里也没有 `gradle`。必须用本机缓存的发行版：

```bash
export GRADLE="C:/Users/lswlc/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle"

cd E:/open_trae_m/zcode-mobile
"$GRADLE" :protocol:test --console=plain --offline
"$GRADLE" :app:testDebugUnitTest --console=plain --offline
"$GRADLE" :app:assembleDebug --console=plain --offline
"$GRADLE" :app:assembleRelease --console=plain --offline
```

**接手第一件事建议：生成 wrapper**（`gradle wrapper --gradle-version 8.10.2`），否则每个接手的人都要自己找这个路径。

### 4.2 `--offline` 很重要

`dl.google.com` 和 `repo1.maven.org` 在这台机器上**不可达**，依赖走腾讯/阿里镜像且已缓存。加 `--offline` 既快又不会因为拉不到元数据而失败。

### 4.3 `:app` 模块的条件包含

`settings.gradle.kts` 只在**能发现 Android SDK** 时才 `include(":app")`（读 `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `local.properties` 的 `sdk.dir`）。所以纯 JVM 环境仍可构建 `:protocol` 和 `:verify`。

`local.properties` 已存在，`sdk.dir=C:\Users\lswlc\AppData\Local\Android\Sdk`。

### 4.4 安装到设备

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n dev.zcodemobile.app/.MainActivity
```

---

## 5. 协议文档（重要资产）

| 文档 | 内容 |
|---|---|
| **`docs/WS-API.md`** | ★ **完整客户端协议规范**（约 1300 行，13 章）。连接/鉴权、控制平面、VQL 编码、34 个命令全集、快照/增量模型、逐项操作手册、错误码目录、坑列表，以及 §12 协议面 ↔ 实现文件对照 |
| `docs/_mine/WIRE-TRANSPORT.md` | 线缆/鉴权/VQL/快照/增量的逐字段 schema 与字节细节（696 行） |
| `docs/_mine/COMMAND-SURFACE.md` | 34 个命令的完整 payload、ack 判定管线、reasonCode 目录（539 行） |
| `docs/_mine/SETTINGS-MODELS-SESSIONS.md` | 会话/设置/模型/供应商通道全表（603 行） |
| `docs/_mine/REMOTE-WEB-CLIENT.md` | 线上网页端行为反查 + bundle 原文证据（618 行） |
| `docs/PROTOCOL.md` | 早期版本，**部分内容已被 WS-API.md 取代**，只作历史参考 |

### 5.1 权威源码

`E:/open_trae_m/ZCode_full` 是官方开源仓库（Apache-2.0），**version 恰好是 3.14.3，与线上链接的 `app_version` 一致** —— 这就是为什么它的 Zod schema 可以直接当作线上协议的定义。

关键位置：
```
packages/shared/src/zcode-protocol-v4/command.ts     命令全集 / 信封 / CommandAck
packages/shared/src/zcode-protocol-v4/rows.ts        行 schema（含 row.actions）
packages/shared/src/zcode-protocol-v4/delta.ts       增量 op 权威定义
packages/shared/src/zcode-protocol-v4/snapshot.ts    快照 schema
packages/services/src/zcode-agent/zcodeAgent.ts      通道方法签名与参数接口
```

---

## 6. 抓包 / 探针工具

`tools/relay-probe/` 下的 Node 工具，**零依赖**（Node ≥ 22）：

| 脚本 | 用途 |
|---|---|
| `capture.mjs` | 记录**完整双向线缆报文**（打补丁 `globalThis.WebSocket`），按脚本调用方法并落盘 transcript |
| `gen-plan.mjs` | 生成探测计划（`plan-full.json`，覆盖全部 34 命令） |
| `decode-transcript.mjs` | 把 transcript 解码成可读的 header/body 对照表 |
| `scrub.mjs` | **脱敏**：剥离 transcript 里的明文供应商密钥 |
| `zcode-client.mjs` | 参考实现（RelayTransport / ChannelClient / VQL） |
| `probe.mjs` / `e2e.mjs` / `read-conversation.mjs` | 早期验证脚本 |

用法：

```bash
cd tools/relay-probe
node capture.mjs --url "<链接>" --out transcript.json
node gen-plan.mjs && node capture.mjs --plan plan-full.json --out transcript-probe.json
node decode-transcript.mjs transcript-probe.json
```

**两个坑**：
- `ChannelClient` 在构造时会安装自己的 `onChannelPayload`。记录器必须**链式包装**，直接覆盖会让所有 RPC 响应丢失（这个坑踩过，表现为所有调用挂死）。
- transcript 里**含用户真实的明文供应商 API Key**。落盘前必须走 `scrubSecrets`；`scrub.mjs` 用于清洗历史产物。

---

## 7. 必须知道的坑（踩过的）

### 7.1 传输层

1. **中继路径没有 13 字节 SocketProtocol 头**。`rpc-frame.dataBase64` 装的是裸通道载荷 `serialize(header)+serialize(body)`；那套头只用于桌面直连 socket。
2. **调用参数必须位置数组化**，单参数也要 `[arg]`（宿主用 `target.apply(handler, args)`）。**`listen` 例外** —— 它的 body 是裸值，不能数组化。
3. 每收到一个 `rpc-frame` **必须回 `rpc-frame-ack`**。
4. **中继不对大响应分片**：实测 117 KB 的 `rpc-frame` 仍是 `fragmentCount: 1`。所以"大响应超时"**不要**去改分片重组 —— 方向是错的（我差点为此重写正常代码）。
5. `clientHello.capabilities` 是 `.strict()` 且单向：只能回显宿主声明过的键，多一个整个握手失败。
6. 链接的 `t` 参数是**签发时间**，不是过期时间。

### 7.2 会话层

7. **先 `listen` 再 `subscribe`**，否则紧随 ack 的首帧快照会丢。
8. `subscribeConversationV4` 的参数是 **`workspacePath`**，不是 `workspaceKey`。
9. `envelope.clientId` 必须与 clientHello 的 `clientId` **逐字相同**，否则 `fault.command.clientMismatch`。
10. `row.upserted` 是**整行替换**（按 rowId）；当追加处理会复制出重复行。
11. `row.removed` 删除 `fromRowId` **及其之后所有行**。
12. `row.delta` 的 `rowId`/`path`/`append` 在**顶层**，无包装对象。
13. `state.updated` 是**浅补丁、按 key 整体替换**，绝不深合并。`pendingInteractions` 清空用"键存在 + 空数组"，要按**键是否存在**判断。
14. `availability.*.allowed === false` **只是 UI 提示**，不是服务端闸门。
15. 快照 `sessionConfigStateSchema.mode` 有 `default("build")`，复用会把工作区默认 `yolo` 覆盖掉 —— 创建会话必须用独立的 `createSessionRequestedConfigSchema`。
16. `deleteSession` 语义是 **close**，不是删记录。
17. **行定位命令必须同时带 `rowId` 和 `entityId`**；`rowId` 只在一个投影代际内有效。
18. **行能力由宿主声明**（`row.actions` 的 `canEdit`/`canFork`/`canRetry`/`canRewindFiles`/`editDisposition`），**不要按 kind 猜** —— 猜就会发出宿主必然拒绝的命令。
19. `assistantText.text` 是 **Markdown**，必须渲染。
20. 推理档位在 `models[].config.optionSpecs.reasoningLevel.values`，**不在模型顶层**。读错路径会静默得到空列表。

### 7.3 CAS / 幂等

21. 15 个命令需要 `baseRevision`，其中 5 个行定位命令还需要 `baseLogEpoch`（**先于** `baseRevision` 校验）。
22. `commandId` 是**幂等键**，跨重试必须稳定。
23. **`stale` 重试要复用同一个 `commandId`** —— 第一次其实已落地的命令会被答 `duplicate`，而不是执行两次。
24. 被拒命令**仍然返回 ack**，不抛异常；只有传输失败才抛。

### 7.4 断线

25. **被踢下线（`KICKED`）后客户端会卡死**，之后所有 RPC 只是静默超时。中继同时只允许一个控制端，所以这是**正常事件**（用户在网页端打开、或桌面点了"停止"）。
26. **不做自动重连是刻意的**：自动重连会和刚接管的那一端互相踢。正确做法是明确报错 + 提供"重新连接"。

### 7.5 构建 / 语言

27. **Kotlin 一个类只能有一个 `companion object`**。加第二个会让原有成员全部报"未解析"（这个坑踩了两次）。
28. **Android XML 注释里不能出现 `--`**。
29. Gradle 会警告 "Kotlin plugin loaded multiple times" —— 目前无害，但清理方式是给子项目加 `apply false`。

### 7.6 UI

30. 渲染要**确定性验证**（Compose 截图测试），别靠滚模拟器碰运气。
31. "颜色/样式不对"多数不是调色板问题：先查**没铺底** → 再查**内容色继承**（`LocalContentColor` 默认黑）→ 再查是不是**扁平大圆角**（该去描边）。
32. 改 UI 要**改一处验一处**。批量 `sed` 改 Kotlin 反复改坏文件；编译通过 ≠ 能用。
33. 首页列表**不能用原始 `updatedAt` 排序**。会话运行期间 `task.upserted` / `session.upserted` 持续推送，时间戳每次都在变：多会话并行（尤其跨文件夹）时行和整个项目分区会互相顶替，`animateItem` 再把每次换位渲染成可见滑动，读列表时行还会从手指底下跑掉。排序统一走 `HomeProjection`：活动时间**按分钟分桶** + 创建时间 / 会话 id 做固定兜底（桶内顺序不再变）、置顶优先、分组顺序同规则、`时间` 视图是真正的全局时间线（不是项目分块拼接）。改排序只改这一处，`HomeProjectionTest` 守着。

---

## 8. 凭据与安全

- **中继链接等同密码**。`hash` 参数是所有鉴权的 HMAC 密钥。不要进仓库、不要进日志。`capture.mjs` 已在落盘前打码 `hash` 和 `proof`。
- **`model-selection.getView` 和 `provider-settings.getView` 的原始返回带明文供应商 API Key**（`providers[].config.access.apiKey` 等）。客户端**必须做白名单投影**后才能落盘/上屏。`ModelCatalogService` / `SettingsService` 已实现，并且有单元测试守住（断言渲染结果里不含 `sk-`）。
- 抓包 transcript 曾含 10 个真实密钥，已用 `scrub.mjs` 清洗；**不要提交未脱敏的 transcript**。

---

## 9. 还没做的（别当成已完成）

**有 API 支持、没有界面**：
- `conversationPlansV4` 计划目录（已能拉取，从未渲染）
- 工作流运行的产物 / 事件日志 / transcript
- 工作区 hooks 审核流程（`respondWorkspaceHookReview` 等）
- `cancelBackgroundWork` 只在运行中的后台任务行上有入口
- `startSavedWorkflow` / `amendWorkflowRunSettings` / `discardSharedContext`

**完全没做**：
- 真正的并排 diff（文件改动目前是摘要 chip，展开显示的是转义后的工具输入，不是彩色 diff）
- 终端视图
- 语音输入（麦克风按钮是**禁用**状态，不是假的）
- 二维码扫描器**从未见过真实打印的二维码**（解码路径有单测，CameraX 取帧没验过）

**其他**：
- 空状态页缺少参考 App 那种"账号 / 仓库 / 分支"上下文选择行（composer 层的 git 行有）
- Release APK 未签名
- 没有 Gradle wrapper

---

## 10. 环境状态（交接时）

| 项 | 状态 |
|---|---|
| Android SDK | `C:\Users\lswlc\AppData\Local\Android\Sdk`，adb / sdkmanager / avdmanager 可用 |
| SDK `emulator` 包 | **未安装**，也没有任何 AVD |
| MuMu 模拟器 | 写这份报告的过程中一度掉线，随后恢复，现在有**两台**在线：`127.0.0.1:16416` 和 `127.0.0.1:7555`。此前所有真机验证都在 `16416` 上完成（型号 `23117RK66C`） |
| Gradle | PATH 里没有；用 §4.1 的缓存发行版 |
| JDK | 17（Microsoft OpenJDK） |
| Node | v24.13.1（跑 relay-probe 工具用） |
| 浏览器工具 | 见下 |

### 关于浏览器操控

**✅ 已可用并完成实战验证（2026-09-26）。** `node-repl-host` 已从 `plugins.suppressedBuiltins`
移除、`browser-use` 已在 `enabledPlugins`，新会话中 `mcp__node_repl__js` 正常挂载。内置浏览器
（IAB）已用于线上网页端的实机验证（见 `docs/_mine/LIVE-WEB-VERIFICATION.md`）。

实战要点：
- 网页端在模块初始化期就建 WebSocket，钩子注入常晚于连接；用 `WebSocket.prototype.send` 补丁
  （对已存在 socket 生效）+ `JSON.parse` 补丁（tee 入站解析）可覆盖绝大多数流量。
- 网页端**切换会话 / 重连都是整页导航**，realm 重置会丢钩子——每个动作单元里先重注再操作。
- 被 Node 探针踢下线后，网页端显示"重新连接"按钮，点击 = 整页 reload（不会软重连）。

---

## 11. 建议的下一步

按优先级：

1. **生成 Gradle wrapper** —— 成本极低，省掉每个接手人的摸索。
2. ~~**新开会话，用浏览器实际验证网页端**~~ —— **✅ 已完成（2026-09-26）**。
   在内置浏览器中打开线上网页端并注入 WS 抓包钩子，逐项驱动 UI 对照 WS-API.md：
   **修正响应头形状（2 元而非 4 元）**、新增 `zcode-task` / `usage-stats` / `marketing-touch` /
   `git` 通道、确认 sendText 免 CAS、队列全流程与 composer 芯片语义（值烤进下一条 sendText）。
   完整报告：**`docs/_mine/LIVE-WEB-VERIFICATION.md`**。剩余未覆盖：`editUserQuery`/`retryTurn`
   的 UI 触发（协议面已有 Node 实证）、握手首帧序列（同左）。注意网页端**切换会话与重连都是整页导航**。
3. **补完有 API 无界面的部分** —— 计划目录、工作流产物、hooks 审核。
4. **真机回归一轮** —— 需要先恢复模拟器（装 SDK `emulator` 包建 AVD，或重新启动 MuMu）。
5. 若要发布：配 signingConfig，并确认 `dev.zcodemobile.app` 包名与免责声明。

---

## 12. 一句话总结

协议层**完整且经过实机验证**（34 命令 / 全部通道方法 / 双向数据流），UI 覆盖了日常使用的主路径，文档齐全到可以照着重新实现一个客户端。**主要缺口是"有协议支持但没界面"的那几块，以及无法在没有模拟器的环境下做回归。**
