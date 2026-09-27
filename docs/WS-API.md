# ZCode 远程中继 WebSocket 完整协议文档

> 目标地址：`https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…&mid=…&name=…&app_version=3.14.3`
> 实际通讯层：`wss://zcode.z.ai/ws?mid=<mid>`
> 文档版本：对齐 `app_version=3.14.3`

---

## 0. 本文档的证据基础

本文档不是从 minified bundle 猜出来的，三路证据交叉验证：

| 证据源 | 位置 | 作用 |
|---|---|---|
| **线上实时抓包** | `tools/relay-probe/transcript-*.json` | 双向原始报文（含鉴权、控制面、通道帧），共 400+ 条 |
| **线上网页端实机验证** | `docs/_mine/LIVE-WEB-VERIFICATION.md`（2026-09-26） | 浏览器驱动真实网页端逐项对照：响应头修正、新通道、UI 映射、composer 语义 |
| **官方开源仓库** | `ZCode_full`（`package.json` version = `3.14.3`，与链接参数**完全一致**） | 全部 Zod schema、方法签名、命令全集的权威定义 |
| **线上 SPA bundle** | `_relay_probe/index-B-ilXaCQ.js` + `src-wmk2orCZ.js`（**已换版为 `index-NjWRUABD.js`**） | 网页端真实行为的对照，鉴权公式的原文证据 |

**抓包工具**（可直接复跑）：

```bash
cd E:/open_trae_m/zcode-mobile/tools/relay-probe
node capture.mjs --url "<链接>" --out transcript.json           # 握手 + 快照
node gen-plan.mjs && node capture.mjs --plan plan-full.json --out transcript-probe.json
node decode-transcript.mjs transcript-probe.json                # 解码成可读对照表
```

> ⚠️ **链接即凭据**。`hash` 参数是所有鉴权的 HMAC 密钥；`tools/relay-probe/capture.mjs` 已在落盘前自动打码 `hash` 与 `proof`。
> ⚠️ **官方限制同时只允许一个手机/远程控制端**，新连接会把旧端踢下线（`error{code:"KICKED"}`）。
> ⚠️ **`model-selection.getView` / `provider-settings.getView` 的原始返回里带明文供应商 API Key**（见 §7.7），任何客户端都必须做字段投影后才能落盘/上屏。

### 分层总览

```
┌─ 应用层  v4 会话协议：命令 / 快照 / 增量（JSON，本文档 §5–§8）
├─ 通道层  RPC 复用：call / listen（VQL 二进制编码，§3–§4）
├─ 帧层    rpc-frame：base64 + crc32 + 分片（§2.5）
├─ 控制面  relay JSON：鉴权 / bootstrap / bridge（§1–§2）
└─ 传输层  WebSocket  wss://zcode.z.ai/ws?mid=<mid>
```

---

## 1. 连接与鉴权

### 1.1 端点与链接参数

WebSocket：`wss://zcode.z.ai/ws`，若链接带 `mid` 则加 `?mid=<mid>`。

链接查询参数（解析实现见 SPA chunk `pO`/`goe` 函数）：

| 参数 | 必填 | 含义 |
|---|---|---|
| `sid` | ✅ | 设备会话 id（`device_sid`），形如 `d_QVhdHsDAk7nrURU3dThDmU` |
| `hash` | ✅ | 鉴权密钥（HMAC key），**等同密码** |
| `t` | ✅ | 链接**签发**时间戳（ms）。**不是过期时间** —— 实测签发 7 小时后仍可连接 |
| `mid` | ➖ | 设备机器 id（`device_mid`），用于 WS 路由 |
| `name` | ➖ | 设备显示名，仅用于 `auth_init.meta.name` |
| `app_version` | ➖ | 宿主版本，进入 `meta.version` |
| `theme` | ➖ | UI 主题，与协议无关 |

### 1.2 鉴权握手（4 条明文 JSON 文本帧）

```jsonc
// 1) 客户端 → 服务端
{ "type":"auth_init", "role":"terminal", "device_sid":"d_…",
  "meta": { "platform":"web", "version":"3.14.3", "name":"mobile-browser" },
  "client_ts": 1790350895812 }

// 2) 服务端 → 客户端
{ "type":"auth_challenge", "server_ts":1790351069, "nonce":"xY2LU7Im3BbnQgn1rw2sGHaF" }

// 3) 客户端 → 服务端
{ "type":"auth_response", "device_sid":"d_…", "proof":"<base64url>", "client_ts":1790350895868 }

// 4) 服务端 → 客户端
{ "type":"auth_ack", "server_ts":1790351069, "device_sid":"d_…",
  "terminal_sid":"t_LxbbCzfyFCNyZnMRn2HDw3", "pair_status":"matched" }
```

**证明（proof）公式** —— 已从 SPA bundle 原文确认，并由两套独立实现实测通过：

```js
proof = base64url_nopad(
  HMAC_SHA256(
    key = UTF8(hash),
    msg = `${nonce}|terminal|${sid}`     // 第二段是字面量 "terminal"，不是变量
  )
)
```

bundle 原文（`index-B-ilXaCQ.js` 约 4738700 字节处）：

```js
function H2t(e,t,n,r){ /* … */ sign(`HMAC`, a, i.encode(`${t}|${n}|${r}`)) /* … */ return V2t(new Uint8Array(o)) }
function V2t(e){ return btoa(String.fromCharCode(...e)).replace(/\+/g,`-`).replace(/\//g,`_`).replace(/=+$/,``) }
// 调用点：calculateProof(this.options.passHash, t.nonce, `terminal`, this.options.deviceSid)
```

要点：`base64url` **去 padding**；`key` 是 `hash` 字符串的 **UTF-8 字节**（不是 hex 解码）；三段顺序为 `nonce | role | sid`。

`role` 取值：远程控制端固定 `"terminal"`。

`pair_status` 取值：`waiting` | `matched`。实测首次即 `matched`。

### 1.3 心跳与配对状态

鉴权成功后客户端启动心跳，**每 10 秒**发一次，服务端 30 秒无心跳判定掉线：

```jsonc
→ { "type":"pair_status_query", "device_sid":"d_…", "client_ts":… }
← { "type":"pair_status_ack",   "pair_status":"matched" }
```

### 1.4 错误与关闭码

```jsonc
← { "type":"error", "code":"KICKED", "message":"…" }
```

| code | 含义 |
|---|---|
| `KICKED` | 被新的远程控制端顶下线（同时只允许一个） |
| `DEVICE_OFFLINE` | 宿主桌面端不在线 |
| `AUTH_FAILED` | proof 校验失败 |
| `WRONG_PARAM` | 链接参数缺失/非法 |
| `INTERNAL` | 服务端内部错误 |

WebSocket 关闭码：`4004`（链接失效）、`4009`、`4010`、`4011`、`4012`、`4013`（`session-expired` / `session-not-found` / `workspace-closed` 等）。

鉴权超时 20 秒。

---

## 2. 控制平面（relay JSON 层）

### 2.1 消息信封

除鉴权 4 帧外，**所有**业务消息都包一层：

```jsonc
// 出站
{ "type":"data", "payload": { "zcode_type":"…", … }, "client_ts": 1790350895914 }
// 入站（多一个 server_ts，注意单位是**秒**）
{ "type":"data", "server_ts": 1790351069, "payload": { "zcode_type":"…", … } }
```

请求/响应靠 `payload.requestId` 关联（UUID）。

### 2.2 全部 `zcode_type`

| `zcode_type` | 方向 | 说明 |
|---|---|---|
| `bootstrap-request` | → | 拉取工作区 + 会话 + 初始视图状态 |
| `bootstrap-response` | ← | 见 §2.3 |
| `workspace-list-request` / `-response` / `-updated` | ⇄ | 工作区列表 |
| `workspace-bridge-open` | → | 打开工作区通道桥，**之后所有 RPC 的前提** |
| `workspace-bridge-ready` | ← | 返回 `bridgeSessionId` / `bridgeGeneration` |
| `workspace-bridge-error` | ← | 开桥失败 |
| `bridge-degraded` | ← | 桥降级 |
| `workspace-reconnect-request` / `-response` | ⇄ | 桥重连 |
| `platform-request` / `-response` | ⇄ | 宿主平台信息 |
| `mobile-view-state-update` | → | 上报移动端当前查看的会话（影响宿主 UI） |
| `mobile-diagnostic` | → | 诊断上报 |
| `app-error` | ⇄ | 应用级错误 |
| `rpc-frame` | ⇄ | **通道 RPC 载荷载体**（§2.5） |
| `rpc-frame-ack` | ⇄ | 帧确认 |

### 2.3 bootstrap

请求：

```jsonc
→ { "type":"data", "payload":{ "zcode_type":"bootstrap-request", "requestId":"bootstrap-<uuid>" } }
```

响应 `payload.result`（实测 14.8 KB，5 工作区 / 47 会话）：

```jsonc
{
  "desktopAppVersion": "3.14.3",
  "initialViewState": { "activeWorkspaceKey":"E:\\open_trae_m",
                        "activeTaskId":"sess_…", "updatedAt": 1790350895960 },
  "mobileViewState":  { "activeWorkspaceKey":"…", "activeTaskId":"…" },
  "workspaces": [ { "kind":"local", "workspaceKey":"E:\\open_trae_m",
                    "path":"E:\\open_trae_m", "label":"" }, … ],
  "tasks": [ { "taskId":"sess_…", "title":"…", "displayStatus":"running|completed",
               "workspacePath":"E:\\open_trae_m", "workspaceKind":"local",
               "workspaceLabel":"", "provider":"…",
               "createdAt":…, "updatedAt":… }, … ]
}
```

> `tasks[]` 就是**跨工作区的会话清单**（含 `displayStatus`）；单工作区内的完整清单走 §7.1 的 sessions-index。

### 2.4 workspace bridge

```jsonc
→ { "type":"data", "payload":{ "zcode_type":"workspace-bridge-open",
      "requestId":"workspace-bridge-<uuid>",
      "bridgeSessionId":"bridge-<uuid>",       // 客户端自己生成
      "bridgeGeneration":1,
      "workspaceKey":"E:\\open_trae_m",
      "taskId":"sess_…" } }                    // 可选

← { "type":"data", "payload":{
      "zcode_type":"workspace-bridge-ready",
      "requestId":"workspace-bridge-<uuid>",
      "bridgeSessionId":"bridge-<uuid>", "bridgeGeneration":1,
      "bridge": { "bridgeSessionId":"bridge-<uuid>", "bridgeGeneration":1,
                  "kind":"local", "workspaceKey":"E:\\open_trae_m",
                  "workspacePath":"E:\\open_trae_m",
                  "initialTaskId":"sess_…" } } }
```

开桥后服务端立即下发一帧通道 `Initialize`（见 §3.3），此时才能开始发 RPC。

### 2.5 `rpc-frame` 帧格式

```jsonc
{
  "zcode_type": "rpc-frame",
  "bridgeSessionId": "bridge-<uuid>",
  "bridgeGeneration": 1,
  "recoveryId": "…",              // 可选
  "seq": 4,                       // 本端单调序号
  "messageSeq": 4,                // 本端消息序号（ack 用它）
  "messageBytes": 139,            // dataBase64 解码后的字节数
  "checksum": { "algorithm":"crc32", "value":"2ffae522" },  // 8 位小写 hex
  "fragmentIndex": 0,
  "fragmentCount": 1,
  "dataBase64": "BAQGZAYDAQt6Y29kZS1hZ2VudAEXc3Vic2NyaWJl…"
}
```

- `dataBase64` = **裸通道载荷**的 base64：`serialize(header) + serialize(body)`。
- **中继路径上没有 13 字节 SocketProtocol 头**（`type(1)+id(4BE)+ack(4BE)+length(4BE)` 那套只用于桌面直连 socket 路径）。
- `crc32`：多项式 `0xEDB88320`，初值/终值均 `0xFFFFFFFF`，输出小写 8 位 hex。
- **每收到一帧必须回 ack**，否则对端缓冲不释放：

```jsonc
→ { "type":"data", "payload":{ "zcode_type":"rpc-frame-ack",
      "bridgeSessionId":"bridge-<uuid>", "bridgeGeneration":1,
      "ackMessageSeq": 1 } }
```

- 分片：`fragmentCount > 1` 时按 `fragmentIndex` 重装，顺序到达（实测未触发分片，仅代码路径）。

---

## 3. 通道载荷编码（VQL）

镜像自 `packages/rpc/src/serialization.ts`。

### 3.1 值编码（DataType 标签）

| 标签 | 名称 | 编码 |
|---|---|---|
| `0x00` | Undefined | 仅标签 |
| `0x01` | String | 标签 + varint 字节长 + UTF-8 |
| `0x02` | Buffer | 标签 + varint 长 + 原始字节 |
| `0x03` | VSBuffer | 同 Buffer |
| `0x04` | Array | 标签 + varint 元素数 + 逐元素 |
| `0x05` | Object | 标签 + varint 长 + JSON UTF-8 |
| `0x06` | Int | 标签 + varint 值 |

**varint**：7 位一组小端，最高位为续位（`0x80`）。

### 3.2 一条消息 = `serialize(header) + serialize(body)`

- `header` 形状**按方向区分**（2026-09-26 线上网页端实抓 + `channelServer.ts` sendResponse 双重实证）：
  - **客户端 → 服务端请求**（Promise / EventListen）：4 元 `[type, id, channelName, method|eventName]`
  - **客户端 → 服务端取消/释放**（PromiseCancel / EventDispose）：2 元 `[type, id]`
  - **服务端 → 客户端响应/事件**（PromiseSuccess / PromiseError / PromiseErrorObj / EventFire）：**2 元 `[type, id]`**
  - **服务端 → 客户端 Initialize**：1 元 `[200]`
- 事件归属：响应/事件头不带 channel 名，客户端用 listen 时记录的 `id → (channel, eventName)` 映射还原。
- `body`：
  - **调用**：位置参数数组（**即使只有一个参数也必须是单元素数组**）——宿主用 `target.apply(handler, args)` 分发
  - **监听**：裸参数，**不**包数组
  - `Initialize` 帧：`undefined`

### 3.3 类型常量表（字符串 ↔ 数字对照）

```ts
RequestType  { Promise:100, PromiseCancel:101, EventListen:102, EventDispose:103 }
ResponseType { Initialize:200, PromiseSuccess:201, PromiseError:202, PromiseErrorObj:203, EventFire:204 }
```

### 3.4 实测字节对照

| 含义 | 字节 | 逐字节解释 |
|---|---|---|
| 通道初始化 | `04 01 06 c8 01 00` | `04`Array(1) `06 c8 01`Int(200=Initialize) `00`Undefined |
| `helloConversationV4()` | `04 04 06 64 06 00 01 0b 7a 63 6f 64 65 2d 61 67 65 6e 74 01 13 68 65 6c 6c 6f… 04 00` | `04 04`Array(4) `06 64`Int(100=Promise) `06 00`Int(id=0) `01 0b`"zcode-agent" `01 13`"helloConversationV4" ‖ body `04 00`Array(0) |
| `initializeConversationV4([{…}])` | `04 04 06 64 06 01 01 0b 7a 63 6f 64 65 2d 61 67 65 6e 74 01 18 69 6e 69 74…` | 同上，`06 01`id=1，方法名 24 字节 |
| `listen onDynamicConversationFrame {…}` | `04 04 06 66 06 02 01 0b 7a 63 6f 64 65 2d 61 67 65 6e 74 01 1a 6f 6e 44 79 6e…` | `06 66`Int(**102**=EventListen) `06 02`id=2；body 是**裸 Object**不是数组 |
| `model-selection.getView({})` | `04 04 06 64 06 04 01 0f 6d 6f 64 65 6c 2d 73 65 6c 65 63 74 69 6f 6e 01 07 67 65 74 56 69 65 77 04 01 05 02 7b 7d` | body `04 01`Array(1) `05 02`Object(len2) `7b 7d`"{}" |

---

## 4. 通道与 RPC 调用约定

### 4.1 通道清单（中继可达）

| 通道 | 作用 |
|---|---|
| `zcode-agent` | **主通道**：v4 会话、命令、附件、插件、自动化 |
| `model-selection` | 模型目录（`getView`；`onDidChange` 变更推送） |
| `provider-settings` | 供应商设置（`getView` / `refresh` / 个人供应商与模型 CRUD） |
| `setting` | 全局设置（`get` / `update`） |
| `zcode-session` | 会话服务：旧版 `listSessions` 等 + 线上网页端在用的 `readSession({workspacePath, sessionId, messageLimit})`、`readWorkspacePresentation` |
| `zcode-task` | **任务管理（网页端侧边栏现用）**：`listTasks` / `listPinnedTasks` / `listArchivedTasks` / `listPinnedTaskIds` / `listDeletedTaskIds` / `setTaskUnread` / `getTaskSessionFilePath` / `getTaskNativeSessionLogFile` / `getWorkspaceProviderConfigFile`；宿主另推 `controller/tasks-index` 通知流 |
| `window-controller` | 窗口/任务列表（`listTaskList`、`mutateTask`）——**旧面，2026-09-26 线上网页端已改用 `zcode-task`，抓包中未再出现** |
| `usage-stats` | 用量/权益（`getEntitlementSnapshot({includeSubscription, preferredProviderId, accountAccess{…}, …})`） |
| `marketing-touch` | 营销/公告位（`query([{locale}])`） |
| `client-scenes` | 客户端场景（`list`） |
| `git` | 仓库信息（`getRepositorySummary` / `getLocalBranches` / `switchBranch`，探针实证） |
| `hooks` | 工作区 hooks（`loadHooks` / `saveHooks` / `grantWorkspaceHookTrust`） |
| `mcp-sync` | MCP 配置同步 |
| `cua-permission` / `credential` / `client-config` / `memory` | 权限、凭据、客户端配置、记忆 |

### 4.2 调用与监听

```js
// 调用：args 是位置参数列表（必须数组化）
call(channel, method, ...args)   →  header [100, id, channel, method],  body = args

// 监听：arg 是裸值
listen(channel, event, arg, cb)  →  header [102, id, channel, event],   body = arg

// 取消监听
header [103, id]  →  EventDispose
```

响应到达时的 `header[0]` 决定形态：`201` 成功（`body`=返回值）、`202` 错误（`body`={name,message,stack,…}）、`203` 错误对象、`204` 事件（`id` 即 listen 返回的 id）。⚠️ 响应/事件头只有 `[type, id]` 2 元（见 §3.2），事件归属靠 listen 映射还原。

### 4.3 `zcode-agent` 方法全表

**v4 会话（读写）**

| 方法 | 参数 | 返回 |
|---|---|---|
| `helloConversationV4` | 无 | `HelloMessage` |
| `initializeConversationV4` | `[ClientHello]` | `void` |
| `subscribeConversationV4` | `[{workspacePath, sessionId, base?, visibility?}]` | `{ack:{subscriptionId, mode, logEpoch, openTiming}}` |
| `resyncConversationV4` | `[{workspacePath, subscriptionId, base, forceSnapshot?, runtimePolicy?}]` | `V4ConversationResyncResult` |
| `unsubscribeConversationV4` | `[{workspacePath, subscriptionId, runtimePolicy?}]` | `void` |
| `sendConversationCommandV4` | `[{workspacePath, envelope, clientMode?}]` | `CommandAck` |
| `queryConversationCommandsV4` | `[{workspacePath, commands:[{sessionId, commandId}], clock?}]` | `{results:[{key, result}]}` |

**v4 查询（只读、无状态、超时重发安全）**

| 方法 | 参数 |
|---|---|
| `conversationRowsRangeV4` | `[{workspacePath, sessionId, limit (1..200), beforeRowId?}]` |
| `conversationPlansV4` | `[{workspacePath, sessionId}]` |
| `conversationFileChangesV4` | `[{workspacePath, sessionId, target, baseRevision, baseLogEpoch}]` |
| `conversationFileRewindPreviewV4` | `[{workspacePath, sessionId, target, baseRevision, baseLogEpoch}]` |
| `backgroundBashOutputV4` | `[{workspacePath, sessionId, workId}]` |
| `conversationWorkflowRunsV4` | `[{workspacePath, sessionId, limit?}]` |
| `conversationWorkflowRunEventsV4` | `[{workspacePath, sessionId, runId, afterSequence?, limit?}]` |
| `conversationWorkflowRunArtifactsV4` | `[{workspacePath, sessionId, runId}]` |
| `conversationWorkflowRunArtifactDataV4` | `[{…, runId, artifactId, afterSequence?, limit?}]` |
| `conversationWorkflowRunArtifactReadV4` | `[{…, runId, artifactId, version, offset, limit}]` |
| `conversationWorkflowRunWorkspaceV4` | `[{…, runId}]` |
| `conversationWorkflowRunNodeResultV4` | `[{…, runId, siteId, ordinal, maxBytes?}]` |
| `conversationAttachmentStatV4` | `[{…, ref, target, attachmentIndex}]` |
| `conversationAttachmentReadV4` | `[{…, ref, target, attachmentIndex, offset, limit}]` |

**附件**

| 方法 | 参数 |
|---|---|
| `attachmentBeginV4` | `[{workspacePath, sessionId, uploadId, fileName, mime, totalBytes, totalChunks, checksum}]` |
| `attachmentChunkV4` | `[{workspacePath, sessionId, uploadId, chunkIndex, dataBase64}]` |
| `attachmentCommitV4` | `[{workspacePath, sessionId, uploadId}]` → `{ref:"zcode-artifact://…"}` |
| `attachmentAbortV4` | `[{workspacePath, sessionId, uploadId}]` |
| `attachmentReadV4` | `[{…, ref, target?, attachmentIndex?, offset, limit}]` |
| `attachmentPreviewSourceV4` | `[{…}]` |

**会话索引 / 工作区配置订阅**

| 方法 | 参数 | 返回 |
|---|---|---|
| `subscribeSessionsIndexV4` | `[{workspacePath, base?, visibility?, subscriberScope?, runtimePolicy?}]` | `{ack:{subscriptionId, mode, logEpoch}}` |
| `resyncSessionsIndexV4` | `[{workspacePath, subscriptionId, base, forceSnapshot?}]` | — |
| `unsubscribeSessionsIndexV4` | `[{workspacePath, subscriptionId}]` | `void` |
| `subscribeWorkspaceConfigV4` | 同 sessions-index | `{ack:{…}}` |
| `resyncWorkspaceConfigV4` / `unsubscribeWorkspaceConfigV4` | 同上 | — |

**对应的事件监听名**

| 事件 | 参数 |
|---|---|
| `onDynamicConversationFrame` | `{workspacePath}` |
| `onDynamicSessionsIndexFrame` | `{workspacePath}` |
| `onDynamicWorkspaceConfigFrame` | `{workspacePath}` |

**旧版（非 v4）方法**（同一通道，中继可达）：`listSessions`、`createSession`、`resumeSession`、`readSession`、`setModel`、`setThoughtLevel`、`setMode`、`compactSession`、`closeSession`、`goalSession`、`sendPrompt`(deprecated)、`getPluginsOverview`、`listAutomations`/`createAutomation`/…、`testModelConnectivity`、`generateWorkspaceText`。

---

## 5. v4 会话生命周期

### 5.1 hello

```jsonc
→ zcode-agent.helloConversationV4()                     // 无参数，body = []
← { "kind":"hello", "protocolVersion":3,
    "connectionId":"host-rpc-1e6f62e2-…",
    "clientMode":"web-remote-replayable",               // 中继路径固定值
    "deliveryProfile":"replayable",                     // 中继路径固定值
    "serverTime":1790351473493,
    "capabilities": { "nativeDialogs":false, "localTerminal":false,
                      "binaryFrames":false, "compression":"none",
                      "workspaceHookReview":true, "independentPlanState":true,
                      "workflowRunDeltas":true },
    "auth": {} }
```

**`capabilities` 是严格模式（`.strict()`）且单向**：客户端只能回显宿主声明过的键（如 `workflowRunDeltas`），多一个键整个握手失败。线上网页端实际上**只发** `workspaceHookReviewUi`。

### 5.2 initialize

```jsonc
→ zcode-agent.initializeConversationV4([{
    "kind":"clientHello",
    "protocolVersion":3,
    "clientId":"capture-xgjnb63j",       // ★ 必须与后续 envelope.clientId 完全一致
    "clientKind":"mobileApp",            // desktop | web | mobileRemote | mobileApp
    "appVersion":"3.14.3",
    "capabilities":{ "workspaceHookReviewUi":true }
  }])
← undefined                              // 成功无返回体
```

**`clientId` 绑定连接**：之后每个命令信封的 `clientId` 必须与它逐字相同，否则：

```jsonc
Error: fault.command.clientMismatch      // 实测撞到过，见 §10
```

失败码：`fault.connection.handshakeRequired`（没 initialize 就 subscribe）、`fault.connection.helloRequired`、`fault.connection.clientChanged`。

### 5.3 订阅与监听（**顺序关键**）

```jsonc
// ① 先注册帧监听（否则紧随 ack 的首帧快照会丢）
→ header[102, 2, "zcode-agent", "onDynamicConversationFrame"]
  body { "workspacePath":"C:\\Users\\lswlc\\.zcode\\workspace\\default" }

// ② 再订阅
→ zcode-agent.subscribeConversationV4([{ "workspacePath":"…", "sessionId":"sess_…" }])
← { "ack": { "subscriptionId":"sub-muh50rvz-xh5cwpq5-3",
             "mode":"snapshot",                  // snapshot | resume
             "logEpoch":"muh50rvz-xh5cwpq5",
             "openTiming":{ "version":1, "initialFrameEncodeMs":1,
                            "sessionRuntimeState":"warm", "snapshotRowCount":3,
                            "hostPrepareMs":1, "cliProcessState":"reused",
                            "providerRegistrySyncMs":0, "taskMetaReadMs":1,
                            "cliRequestMs":3 } } }
```

> ⚠️ 参数是 **`workspacePath`**，不是 `workspaceKey`。
> ⚠️ ack **只含订阅信息**；快照作为**紧随其后的通知帧**下发（不是返回值）。

### 5.4 帧封装（`onDynamicConversationFrame` 载荷）

```jsonc
{ "wireVersion":3,
  "kind":"complete",              // complete | fragment
  "deliveryKind":"initial",       // initial | online | recovery
  "logicalFrameId":"sub-…-lf-43381",
  "logicalFrameOrdinal":1,        // 单调递增，必须按序应用
  "topic":"conversation/sess_8a893158-…",
  "subscriptionId":"sub-muh50rvz-xh5cwpq5-3",
  "sentAt":…, "fromSeq":0, "toSeq":244033,
  "frame": { "payload": { "kind":"snapshot"|"deltas", … } } }
```

分片时：`kind:"fragment"` + `fragmentIndex` / `fragmentCount` / `logicalBytes` / `checksum` / `dataBase64`，按 `logicalFrameId` 重装。

### 5.5 取消订阅

```jsonc
→ zcode-agent.unsubscribeConversationV4([{ "workspacePath":"…",
     "subscriptionId":"sub-muh50rvz-xh5cwpq5-3" }])
← undefined
```

---

## 6. 数据模型

### 6.1 快照（`frame.payload.kind === "snapshot"`）

实测顶层 21 个键：

```
protocolVersion, sessionId, logEpoch, seq, revision,
control, availability, inputRouting, meta, config, modelTransition,
usage, queue, pendingInteractions, pendingCommands, backgroundWorks,
subagents, goal, plan, workspaceHookAdmission, rows
```

实测完整样本（会话 `sess_8a893158…`，"hi"，已结束）：

```jsonc
{
  "protocolVersion": 1,
  "sessionId": "sess_8a893158-957e-4592-906e-e6f90c9fe92a",
  "logEpoch": "muh50rvz-xh5cwpq5",
  "seq": 10, "revision": 6,                       // ★ CAS 用 revision
  "control": {
    "phase": "completedInterrupted",              // 见下方 phase 取值
    "sessionEnded": true, "canStop": false,
    "stopState": "idle", "stopTargetKind": "unknown",
    "activeWorks": [], "lastError": null, "apiRetry": null
  },
  "availability": {                               // ⚠️ 仅 UI 提示，不是服务端闸门
    "fork":            { "allowed": true },
    "compact":         { "allowed": true },
    "switchModelConfig":{ "allowed": true },
    "setFollowupMode": { "allowed": true },
    "queueEdit":       { "allowed": true },
    "sendQueuedNow":   { "allowed": false, "reasonCode": "sendQueuedNowRequiresRunning" },
    "pauseGoal":       { "allowed": false, "reasonCode": "noGoalToPause" },
    "resumeGoal":      { "allowed": false, "reasonCode": "noGoalToResume" }
  },
  "inputRouting": { "mode": "startNow" },         // startNow | enqueue | guide | choice
  "meta": { "title": "hi", "titleSource": "generated" },   // generated | custom
  "config": {
    "provider": "new-provider-6",
    "model": "gpt-5.6-sol",
    "thought": "max",
    "thoughtLevels": ["none","low","medium","high","xhigh","max"],
    "followupMode": "queue",                      // queue | guide
    "mode": "yolo",                               // build | edit | plan | yolo
    "modelSelection": { "providerId":"new-provider-6", "modelId":"gpt-5.6-sol",
                        "options":{ "reasoningLevel":"max" } },
    "planEnabled": false
  },
  "modelTransition": null,
  "usage": {
    "contextWindow": { "usedTokens":0, "maxTokens":1050000,
                       "autoCompactThresholdTokens":null },
    "cumulative": { "inputTokens":0, "outputTokens":0,
                    "cacheReadTokens":0, "cacheWriteTokens":0 }
  },
  "queue": { "items": [], "autoDrain": true },    // ★ 消息排队
  "pendingInteractions": [],                      // ★ 权限/提问等待应答
  "pendingCommands": [],
  "backgroundWorks": [],
  "subagents": { "revision":0, "childSessionIds":[], "running":[], "endedTotal":0 },
  "goal": null,
  "plan": null,
  "workspaceHookAdmission": null,
  "rows": { "window":[…], "totalCount":3, "firstRowId":1 }
}
```

`phase` 实测取值：`completedInterrupted`、`completedSuccess`、`draft`（草稿仅存内存）等；运行态另有 `running` 系列。

> ⚠️ `availability.*.allowed === false` **不是服务端闸门**。实测在 `pauseGoal:{allowed:false,reasonCode:"noGoalToPause"}` 下提交 `pauseGoal` 仍返回 `accepted`。

### 6.2 行（row）

`rows.window` 是尾部窗口，`totalCount` 是总数，`firstRowId` 是窗口首行 id（配合 `beforeRowId` 向上翻页）。

判别字段是 **`kind`**（不是 `type`）。实测出现的 kind：`turnHeader`、`userInput`、`assistantText`、`reasoning`、`toolCall`、`artifact`、`subagent`、`hookInvocation`、`timelineMarker`。

公共字段：`rowId`、`turnId`、`entityId`、`productTurnId`、`visibility`、`createdAt`、`createdAtSeq`。

实测样本：

```jsonc
// userInput
{ "rowId":2, "turnId":"msg_mudk70hq_…", "entityId":"msg_mudk70hq_…",
  "productTurnId":"msg_mudk70hq_…", "visibility":"visible",
  "createdAt":1790135061131, "createdAtSeq":3,
  "kind":"userInput", "text":"hi", "origin":"realUser",
  "actions":{ "canEdit":true, "editDisposition":"rewind" },
  "sourceCommandId":"01a0cc5d-…", "rootSourceCommandId":"01a0cc5d-…",
  "clientId":"client-01a041e6-…" }

// assistantText
{ "rowId":3, "kind":"assistantText",
  "assistantResponseId":"msg_mudkk8tg_0d15ac38-…",
  "text":"Hi. What would you like to work on?",   // ★ 是 Markdown
  "state":"complete", "actions":{ "canRetry":true } }

// toolCall
{ "rowId":1616, "kind":"toolCall", "toolName":"…", "status":"…",
  "input":{…}, "inputText":"…", "output":{ "text":"…" } }
```

> `assistantText.text` 是 **Markdown**，必须渲染，不能当纯文本上屏。

### 6.3 增量（`frame.payload.kind === "deltas"`）

闭环 7 个 op：

| op | 字段 | 语义 |
|---|---|---|
| `row.appended` | `row` | 追加新行 |
| `row.upserted` | `row` | **按 `rowId` 整行替换**（当追加处理会复制出重复行） |
| `row.removed` | `fromRowId` | 删除该行**及其之后所有行** |
| `row.delta` | `rowId`, `path`, `append` | 流式追加文本；`path ∈ {text, inputText, output.text, summaryText}`，**顶层无包装对象** |
| `state.updated` | `patch` | 状态补丁 |
| `workflowRun.updated` | — | 工作流运行态更新 |
| `workflowRun.removed` | — | 工作流移除 |

实测报文：

```jsonc
{ "op":"row.upserted", "row":{ "rowId":1616, "kind":"toolCall", … } }
{ "op":"state.updated", "patch":{ "revision":10500 } }
```

**`state.updated` 是浅补丁、按 key 整体替换，绝不深合并**。`pendingInteractions` 也走补丁下发，因此批准弹窗会实时出现/消失；**清空是"键存在但数组为空"**，所以必须按**键是否存在**判断，不能按真值判断。未知 op 应记日志跳过，不要抛错。

---

## 7. 操作手册

### 7.1 获取会话列表

**方式 A —— 单工作区完整清单（推荐，带活性）**

```jsonc
// ① 先监听
→ listen  zcode-agent.onDynamicSessionsIndexFrame  { "workspacePath":"C:\\Users\\lswlc\\.zcode\\workspace\\default" }
// ② 再订阅
→ call    zcode-agent.subscribeSessionsIndexV4([{ "workspacePath":"…" }])
← { "ack": { "subscriptionId":"six-muh516aa-0gm4lhox-3",
             "mode":"snapshot", "logEpoch":"muh516aa-0gm4lhox" } }
// ③ 快照随通知帧到达
```

快照：

```jsonc
{ "protocolVersion":1,
  "workspaceId":"C:\\Users\\lswlc\\.zcode\\workspace\\default",
  "logEpoch":"mugmerec-l8f0ca2e",
  "sessions": [
    { "sessionId":"sess_8a893158-957e-4592-906e-e6f90c9fe92a",
      "workspaceId":"C:\\Users\\lswlc\\.zcode\\workspace\\default",
      "title":"hi", "titleSource":"generated",
      "phase":"completedInterrupted", "sessionEnded":true,
      "hasBackgroundWork":false,
      "lastActivityAt":1790135887982,
      "lastAssistantPreview":"Hi. What would you like to work on?",
      "createdAt":1790135058301 }, … ] }        // 实测 9 条
```

条目字段全集：`sessionId, workspaceId, title, titleSource, phase, sessionEnded, hasBackgroundWork, lastActivityAt, lastAssistantPreview, createdAt`（工作流活跃的会话另有 `workflowActivity`，最多 4 条 run）。

主题名：`sessions-index/<workspaceId>`。**没有服务端搜索/分页**，只有快照 + 增量。

**方式 B —— 跨工作区清单**：`bootstrap-response.result.tasks[]`（见 §2.3）。

**方式 C —— 旧接口**：`zcode-agent.listSessions` / `zcode-session.listSessions`，参数 `[{workspacePath}]`（实测返回 `[]`，参数路径不符时也不报错，属遗留面）。

### 7.2 获取会话状态

订阅会话后取快照的 `state` 部分（§6.1）。要点字段：

- 阶段与终止：`control.phase`、`control.sessionEnded`、`control.canStop`
- 能否操作：`availability.<op>.allowed` / `reasonCode`（**仅提示**）
- 输入路由：`inputRouting.mode`
- 当前选择：`config.provider/model/thought/thoughtLevels/mode/followupMode/planEnabled/modelSelection`
- 排队：`queue.items`、`queue.autoDrain`
- 待应答：`pendingInteractions`
- 用量：`usage.contextWindow`、`usage.cumulative`
- 标题：`meta.title` / `meta.titleSource`
- CAS 基准：`revision`、`logEpoch`、`seq`

### 7.3 新建会话

命令 `createSession`（`envelope.sessionId` 必须为 **`null`**，这是唯一允许 null 的命令）：

```jsonc
→ zcode-agent.sendConversationCommandV4([{ "workspacePath":"…",
  "envelope": {
    "commandId":"<uuid>", "clientId":"<与 clientHello 一致>",
    "sessionId": null,
    "type":"createSession",
    "payload": {
      "workspaceId":"E:\\open_trae_m",
      "firstInput": {                       // 省略 = 空草稿会话（draft）
        "text":"…",
        "attachments":[{ "…":"AttachmentRef" }],
        "modelSelection":{…}, "mode":"…", "planEnabled":false
      },
      "config": {                           // 请求级覆盖，全部可选
        "modelSelection":{…}, "provider":"…", "model":"…", "thought":"…",
        "followupMode":"queue|guide", "mode":"…", "planEnabled":false
      },
      "mcpServers":[…],                     // 启动期配置，必须随 create 一次进入
      "offPeakToolEnabled": false,
      "dynamicWorkflowEnabled": false
    },
    "issuedAt": 1790351473493
  } }])
```

实测（空 payload 探针，证明 schema 校验先于执行）：

```jsonc
← Error: ZodError: [{ "path":["workspaceId"],
     "message":"Invalid input: expected string, received undefined" }]
```

> ⚠️ `config` **不能**复用快照的 `sessionConfigStateSchema.partial()`：快照的 `mode` 有 `default("build")`，会把"没传 mode"误变成"请求切回 build"，从而覆盖工作区默认的 `yolo`。所以协议里单独定义了 `createSessionRequestedConfigSchema`。

相关：`createSelectionSideSession`（在父会话下建副屏会话，父会话由 `envelope.sessionId` 指定）。

### 7.4 获取会话消息

**首屏**：订阅时的快照 `rows.window`（尾部窗口）+ `rows.totalCount`。

**向上翻页**：

```jsonc
→ zcode-agent.conversationRowsRangeV4([{ "workspacePath":"…", "sessionId":"sess_…",
     "limit": 20,               // 1..200，必填
     "beforeRowId": 2 }])       // 取 rowId < 2 的行；省略 = 从当前尾部向前
← { "rows": [
     { "rowId":1, "kind":"turnHeader", "state":"completedInterrupted",
       "startedAt":…, "endedAt":…, "activeMs":826851, "historyRoundCount":2, … },
     { "rowId":2, "kind":"userInput", "text":"hi",
       "actions":{ "canEdit":true, "editDisposition":"rewind" }, … },
     { "rowId":3, "kind":"assistantText",
       "text":"Hi. What would you like to work on?",
       "state":"complete", "actions":{ "canRetry":true } } ] }
```

**实时消息**：订阅后持续收 `deltas` 帧并按 §6.3 应用（`row.delta` 做流式文本追加，`row.upserted` 整行替换）。

**单行文件改动**：`conversationFileChangesV4`（需要 CAS 基准 `baseRevision` + `baseLogEpoch`）。

**附件内容**：`conversationAttachmentReadV4` / `attachmentReadV4`（按 offset/limit 分块，≤512 KiB/块）。

### 7.5 发送消息

命令 `sendText`：

```jsonc
→ zcode-agent.sendConversationCommandV4([{ "workspacePath":"…",
  "envelope": {
    "commandId":"<uuid>",            // ★ 幂等键：重试必须复用同一个
    "clientId":"<与 clientHello 一致>",
    "sessionId":"sess_…",
    "baseRevision": 6,               // CAS 基准，取快照 revision
    "type":"sendText",
    "payload": {
      "text":"…",                    // text 与 attachments 至少一个非空
      "attachments":[{ "…" }],
      "requestedDelivery":"startNow|queue|guide",   // 只覆盖本次，不改会话 followupMode
      "modelSelection":{…}, "mode":"…", "planEnabled":false,
      "heldQueueDisposition":"clearQueueAndSend|keepQueueAndSend",  // mode=choice 时必填
      "expectedHeldQueueItemIds":["…"],             // 防并发增删
      "context_refs":[{ "…" }],                     // 最多 1 个
      "automationId":"…",                           // 与 offPeakTaskId 互斥
      "offPeakTaskId":"…", "offPeakRunType":"init|resume"
    },
    "issuedAt": 1790351473493
  } }])
```

实测（合法 payload + 不存在的会话）：

```jsonc
← { "commandId":"probe-send-…", "status":"rejected",
    "reasonCode":"proto.sessionNotFound", "revisionAtDecision":0 }
```

实测（空 payload）：

```jsonc
← Error: ZodError: [{ "path":["text"],
     "message":"Invalid input: expected string, received undefined" }]
```

**成功返回**（`status:"accepted"` 时）：

```jsonc
{ "commandId":"…", "status":"accepted", "revisionAtDecision":7,
  "result": { "type":"inputAccepted", "delivery":"startNow|queue|guide",
              "inputId":"…", "messageId":"…" } }
```

> `messageId` 在 `TurnStarted` 之后才回填，**不是**"输入被接受"的前提。
>
> **`baseRevision` 对 sendText 可选**（不在 `COMMANDS_REQUIRING_BASE_REVISION` 15 命令集内）。
> 2026-09-26 线上网页端对 revision=9 的会话发 sendText 省略 `baseRevision`，宿主
> `accepted / revisionAtDecision:9` —— 省略即按最新 revision。另外网页端 payload
> **不带 `requestedDelivery`**，路由完全交给宿主 `inputRouting.mode`；模型/思考/模式芯片的值
> 烤进 `payload.mode` / `payload.modelSelection`。

路由：`inputRouting.mode` 决定 `startNow`/`enqueue`/`guide`/`choice`。`startNow` 会抢占当前 turn（`send-now:<commandId>` 前台租约），租约忙 → `fault.command.inputRejected`。

### 7.6 回退消息

协议里**没有独立的"回退"命令** —— 会话回退就是 `editUserQuery` 的 UI 入口。

**改用户消息（含对话回退）**：

```jsonc
→ envelope { "type":"editUserQuery",
    "payload": {
      "target": { "rowId":2, "entityId":"msg_mudk70hq_…" },
      "newText":"改后的内容",
      "attachments":[…],                       // 省略 = 沿用原行的引用
      "workspaceMode":"preserve|rewind"        // preserve=只切分支；rewind=先安全还原文件
    } }
```

返回：

```jsonc
{ "type":"editUserQuery", "disposition":"rewind|fork|blocked",
  "sessionId":"…", "reasonCode":"…", "preview":{…} }
```

约束：目标必须是**最后一条真实用户行**，否则 `guard.latestQueryEditOnly`；空文本且无附件 → `proto.invalidPayload`；`rewind` 模式下预览必须 `canApply`、无 `ignoredFiles`、且 ≥1 `safeFiles`，否则返回 `disposition:"blocked"` 并给 `guard.workspaceRewindUnsafeFiles` / `guard.workspaceRewindIgnoredFiles` / `guard.workspaceRewindUnavailable` / `guard.workspaceRewindApplyConflict`。

**只回退文件、不动聊天历史**：`applyFileRewind`，payload `{ target }`，返回 `{type, applied, preview, response}`。

**重试某一轮**：`retryTurn`，payload `{ target }`（目标是 assistant 行；非最后一行 → `guard.latestAssistantRetryOnly`）。

**分叉**：`forkAssistant`，payload `{ target }`，返回 `{type:"forkAssistant", sessionId:<新会话>}`。

实测（`editUserQuery` / `retryTurn` / `applyFileRewind` 用同一套合法 payload 形状）：

```jsonc
← { "status":"rejected", "reasonCode":"proto.invalidPayload", … }   // 行解析不到即拒绝
```

### 7.7 获取模型列表

```jsonc
→ call model-selection.getView([{}])
```

返回结构：

```jsonc
{ "revision": 33,
  "providers": [
    { "providerId":"new-provider-7", "providerName":"workbuddy",
      "config": {
        "group":"standard-personal",
        "access":{ "type":"api-key", "apiKey":"<REDACTED>" },     // ★★ 明文密钥
        "api":{ "type":"openai-chat-completions",
                "baseUrl":"http://127.0.0.1:7863/v1" },
        "personalModelIds":[…], "modelOrder":[…]
      },
      "models": [
        { "modelId":"cn:deepseek-v4.1-flash",
          "config": {
            "enabled": true,
            "properties": {
              "requiresMfjsToolSchema": false,
              "contextWindow": 1000000,
              "inputFormat": { "supportsText":true, "supportsImage":true,
                               "supportsVideo":false, "supportsAudio":false,
                               "supportsPdf":false },
              "outputFormat":{ "supportsText":true },
              "supportsToolCall":true, "supportsJsonSchemaOutput":false,
              "supportsNativeWebSearch":false, "supportsMidConversationSystem":false
            },
            "optionSpecs": {
              "reasoningLevel": { "values":["disabled","low","high","max"],
                                  "map":"reasoningLevel == \"disable…" } } } }, … ] } ],
  "preferredSelection":{…}, "effectiveSelection":{…}, "selectionIssue":… }
```

> ⚠️⚠️ **明文供应商 API Key 泄漏**：原始返回在 `providers[].config.access.apiKey`（实测抓到 `sk-…` 明文），另有 `providers[].config.api.headers`。`provider-settings.getView` 在 `effectiveConfig` / `personalConfig` / `effectiveBuiltinConfig` 处同样泄漏。**客户端必须做白名单投影**，只取需要的展示字段。

其他要点：

- **没有** `displayName`，也**没有**顶层 `thoughtLevels`。推理档位在 `models[].config.optionSpecs.reasoningLevel.values`，上下文窗口在 `models[].config.properties.contextWindow`。
- 供应商模板走 `provider-settings.getView`（实测 116 KB），含 `providerTemplates[]`、`templateNameMap`、`builtinModelIds` 等。

### 7.8 供应商与当前 play / mode

**读**：快照 `config.provider` / `config.model` / `config.thought` / `config.thoughtLevels` / `config.mode` / `config.planEnabled` / `config.modelSelection`。

**写 —— 切换模型/供应商/推理档**：

```jsonc
→ envelope { "type":"switchModelConfig",
    "payload": { "provider":"new-provider-6", "model":"gpt-5.6-sol", "thought":"max" } }
    // 三个字段全部必填；需要 baseRevision
```

**写 —— 切换协作模式（play mode）**：

```jsonc
→ envelope { "type":"switchCollaborationMode",
    "payload": { "mode":"build" } }     // build | edit | plan | yolo；需要 baseRevision
```

`auto` 不是用户可切换值，不出现在命令面。同值且 plan 关闭 → `noop` + `reasonCode:"config.unchanged"`（必须能区分，否则"点了全权限没反应"会变成静默空操作）。

**写 —— 后续消息投递方式**：

```jsonc
→ envelope { "type":"setFollowupMode", "payload": { "mode":"queue" } }   // queue | guide
```

实测（故意用过期 CAS）：

```jsonc
← { "status":"stale", "reasonCode":"proto.staleRevision", "revisionAtDecision":6 }
```

> `revisionAtDecision` 在 `stale` 时就是服务端当前 revision —— 用它重设 `baseRevision` 后重试。

### 7.9 添加文件（附件上传）

三步事务，**发送前先上传**（这样失败时选择器结果还在屏幕上）：

```jsonc
// ① begin
→ zcode-agent.attachmentBeginV4([{ "workspacePath":"…", "sessionId":"sess_…",
     "uploadId":"probe-<uuid>",
     "fileName":"probe.txt", "mime":"text/plain",
     "totalBytes": 25, "totalChunks": 1,
     "checksum": "sha256:<64-hex>" }])
← { "uploadId":"probe-<uuid>", "state":"staging", "nextChunkIndex":0 }

// ② chunk（逐块、严格顺序）
→ zcode-agent.attachmentChunkV4([{ "workspacePath":"…", "sessionId":"sess_…",
     "uploadId":"probe-<uuid>", "chunkIndex":0,
     "dataBase64":"emNvZGUgcmVsYXkgcHJvYmUgcGF5bG9hZAo=" }])
← { "uploadId":"probe-<uuid>", "nextChunkIndex":1 }

// ③ commit
→ zcode-agent.attachmentCommitV4([{ "workspacePath":"…", "sessionId":"sess_…",
     "uploadId":"probe-<uuid>" }])
← { "ref":"zcode-artifact://sess_8a893158-…/tool-result-0a868b06-…" }
```

约束：每块 **decoded ≤ 512 KiB**，最多 **64 块**，总计 **≤ 20 MiB**；`checksum` 为 `sha256:<64-hex>`；严格顺序，仅在内容完全相同时允许重放。失败走 `attachmentAbortV4`。

拿到 `ref` 后放进 `sendText.payload.attachments[]`。

### 7.10 消息排队

**读**：快照 `queue: { items:[], autoDrain:true }`。

**入队**：`sendText` 带 `requestedDelivery:"queue"`（或会话 `followupMode:"queue"` 时自然入队）。

**队列操作命令**：

| 命令 | payload | 说明 |
|---|---|---|
| `sendQueuedNow` | `{ queueItemId }` | 立即发送队首项（reserve → 抢占租约 → 停止屏障 → 启动 → 移除）。`queueItemId ≡ core pendingInputId` |
| `editQueueItem` | `{ queueItemId, newText }` | 原地改（保留位置）。改 `compact` 项 → `guard.queueItemNotEditable` |
| `reorderQueueItem` | `{ queueItemId, beforeQueueItemId \| null }` | `null` = 移到队尾 |
| `deleteQueueItem` | `{ queueItemId }` | 竞态删除返回 `noop` + `queue.itemMissing`（不是失败） |
| `setAutoDrain` | `{ autoDrain: boolean }` | 自动排空开关；置 `true` 时必须同时触发 CLI ready hook |

**"choice" 路由（暂停队列确认）**：`inputRouting.mode === "choice"` 时 `sendText` 必须带 `heldQueueDisposition`（`clearQueueAndSend` / `keepQueueAndSend`）以及 `expectedHeldQueueItemIds`；缺失 → `heldQueueDispositionRequired`；集合不匹配 → `guard.heldQueueConfirmationStale`。

### 7.11 全部设置项

**A. 全局应用设置 —— `setting.get` / `setting.update(patch, expectedAccountSettings?)`**

实测 `setting.get`（2318 B，共 **43** 个键，以下为实测全集）：

```jsonc
{ "recentProjects":["E:\\open_trae_m", …],
  "locale":"zh-CN", "localePreference":"system",
  "terminalInheritSystemProfile":true,
  "httpProxy":"http://127.0.0.1:7890",
  "embeddedBrowserAllowInsecureCertificates":true,
  "embeddedBrowserViewportPreference":{ "mode":"normal",
      "viewport":{"width":393,"height":852}, "zoom":"fit" },
  "computerUseComposerEntryHidden":true,
  "taskAutoArchiveEnabled":true, "taskAutoArchiveOlderThanDays":3,
  "closeToTrayOnWindows":true, "closeToTrayOnWindowsMigrationInitialized":true,
  "keepAwakeWhileRunning":true,
  "desktopZoomLevel":0,
  "desktopWindowSize":{ "width":1096,"height":912,"maximized":false },
  "desktopChromiumHardwareAccelerationEnabled":true,
  "messageStreamShowReasoning":false,
  "messageStreamShowReasoningMigrationInitialized":true,
  "messageStreamShowTodos":true,
  "toolGroupingExploreEnabled":true, "toolGroupingTerminalEnabled":true,
  "toolGroupingChangesEnabled":true,
  "zcodeInteractionBehavior":"queue",
  "askUserQuestionAutoResolutionEnabled":true,
  "modelIoFullRetentionEnabled":false,
  "enabledBuiltinAgentCliProviders":["glm"],
  "startPlanRecommendationDismissed":false,
  "providerFamilyConnectionSelections":{ "bigmodel":{ "kind":"individual-coding-plan" } },
  "providerFamilyDomain":"bigmodel", "providerFamilyDomainUpdatedAt":1789731647819,
  "providerFamilyDomainMigrated":true,
  "nativeSearchEnhancementsEnabled":true,
  "onboardingOccupation":"developer",
  "proactiveSuggestionsEnabled":false,
  "memoryEnabled":true,
  "lastWorkspaceSession":[{ "kind":"local","workspacePath":"E:\\open_trae_m",
                            "workspacePurpose":"project" }, …],
  "lastActiveTabIndex":…, "receivePreviewUpdates":…,
  "autoDownloadAndInstallUpdates":…, "skippedElectronUpdateVersions":…,
  "settingsSyncFirstRunPromptHandled":…,
  "webRemoteControlExternalRelayDevice":…, "webRemoteControlLastEnabledContext":… }
```

写：`setting.update([patch, expectedAccountSettings?])`。

**B. 会话级设置（走命令，见 §7.8）**：`mode`、`planEnabled`、`modelSelection`(provider/model/thought)、`followupMode`。

**C. 工作区配置 —— `subscribeWorkspaceConfigV4`**

实测快照：

```jsonc
{ "protocolVersion":1,
  "workspaceId":"C:\\Users\\lswlc\\.zcode\\workspace\\default",
  "logEpoch":"mugmered-0fmja3e2",
  "config": { "configOptions":[], "slashCommands":[] } }
```

主题 `workspace-config/<workspaceId>`；只发布 `mode` 一类选项（不含模型）。`configOptions` 是设置项目录，`slashCommands` 是可用斜杠命令。

**D. 其他设置通道**

| 通道 | 方法 | 内容 |
|---|---|---|
| `provider-settings` | `getView` / `refresh(reason)` / `createPersonalProvider` / `savePersonalProviderOverlay` / `deletePersonalProvider` / `reorderPersonalProviders` / `reorderPersonalModels` / `addPersonalModel` / `renamePersonalModel` / `deletePersonalModel` / `savePersonalModelDraft` / `setPersonalModelEnabled` / `resolveModelConfig` / `testModelConnectivity` | 供应商与模型 CRUD，**2026-09-26 全部实测通过**（见 §7.11.1） |
| `hooks` | `loadHooks` / `saveHooks` / `grantWorkspaceHookTrust` | 工作区 hooks 与信任 |
| `mcp-sync` | `loadMcpFromUserDirectory` / `saveMcpToUserDirectory` / `listWorkspaceMcpServerStatuses` / `exportMcpServers` / `importMcpServers` | MCP 配置 |
| `cua-permission` | — | 计算机使用权限 |
| `credential` | — | 凭据 |
| `memory` | — | 记忆 |
| `window-controller` | `listTaskList(query)` / `mutateTask({address, mutation})` | 任务列表（pin/archive/delete/mark-read/open/resume） |
| `setting` | `updateDataBaseDir` / `ensureDefaultProject` | 数据目录 / 默认项目 |

**E. 会话创建期设置**：`createSession.payload.mcpServers`（必须随创建一次性进入 record，不能事后补写）、`offPeakToolEnabled`、`dynamicWorkflowEnabled`。

#### 7.11.1 供应商管理 CRUD —— 实测通过（2026-09-26）

全部 14 个方法在真实中继上以一次性探针供应商跑通完整生命周期（create → 改 → 测 → 删），
结束后 `getView` 的 `providerOrder` 与 baseline 逐字一致（真实供应商零影响）。探针脚本：
`tools/relay-probe/probe-provider-crud.mjs`（+ `probe-overlay-fix.mjs`、`probe-connectivity*.mjs`）。

**全局修订语义**：`view.revision` 是供应商注册表的全局修订号，**每次 mutation +1**（实测 18→28
连跨 10 次变更）。`savePersonalModelDraft` 的 CAS 基准 `basedOnRevision` 取最近一次 getView 的
revision；实测基于当前 revision 提交即 accepted。

| 方法 | 实测参数（位置数组化） | 返回 |
|---|---|---|
| `getView` | `[]` | `ProviderSettingsView`（revision/providerTemplates/providerOrder/providers[]） |
| `refresh` | `["settings-manual"]` | View（reason 任意字符串，UI 用 `"settings-manual"`） |
| `createPersonalProvider` | `[{providerName:"…"}]` 或 **`[]`（无参也可）** | `{providerId}`（自动 id 形如 `new-provider-8`） |
| `addPersonalModel` | `[providerId, modelId, {enabled:true}, true]`（第 4 参 `useRecommendedConfig` 可选） | View，revision+1 |
| `renamePersonalModel` | `[providerId, currentModelId, nextModelId]` | View |
| `setPersonalModelEnabled` | `[providerId, modelId, enabled:boolean]` | View |
| `reorderPersonalModels` | `[providerId, [modelIds…]]` | View |
| `reorderPersonalProviders` | `[[providerIds…]]`（全量新顺序） | View |
| `savePersonalProviderOverlay` | `[providerId, {access:{type:"api-key",apiKey}, api:{type:"openai-chat-completions",baseUrl}}]` | View |
| `savePersonalModelDraft` | `[{providerId, originalModelId, nextModelId, personalConfig, basedOnRevision}]` | View |
| `deletePersonalModel` | `[providerId, modelId]` | View |
| `deletePersonalProvider` | `[providerId]` | View |
| `resolveModelConfig` | `[{providerId, modelId}]` | `{inheritedConfig, effectiveConfig, issues[]}` |
| `testModelConnectivity` | `[{workspacePath, providerId, modelId}]` | `{success:true}` 或 `{success:false, error:{code?, message}}` |

**实测到的坑**：

1. **`savePersonalProviderOverlay` 的 config schema 是 `.strict()` 且不含 `providerName`**
   （providerName 存在规则层）。把 `getView` 返回的 `effectiveConfig` 原样回传会
   `ZodError: Unrecognized key "providerName"` —— 必须剥掉规则层字段只传纯 config
   （`access`/`api`/`personalModelIds`/`modelOrder`…）。
2. **`testModelConnectivity` 只测已进入 Registry 的正式模型**：对未注册/禁用的模型返回
   `{success:false, error:{code:"model-unavailable", message:"This model is currently
   unavailable for connectivity testing."}}`；对启用但端点不可达的模型实测返回
   `{success:false, error:{message:"Model request was cancelled."}}`；成功即 `{success:true}`。
   调用可能 **>25s**（宿主侧实际发起网络探测），客户端超时要放宽。
3. **`resolveModelConfig` 的 `issues[]` 给出可操作的校验细节**（实测：
   `"缺少必填配置 providers.<id>.models.<model>.optionSpecs.maxOutputTokens.map"`）——
   客户端可以用它驱动表单校验。
4. 最小可用的 ModelConfigObject 是 `{enabled:true}`（schema 其余字段全 optional），
   配合 `useRecommendedConfig:true` 使用。
5. **每个 mutation 都返回新 View**（含新 revision 与完整 providerOrder），不需要单独再 getView。

### 7.12 权限与提问应答

**读**：快照 `pendingInteractions[]`（随 `state.updated` 补丁实时变化）。

**写**：

```jsonc
→ envelope { "type":"resolveInteraction",
    "payload": {
      "interactionId":"…",
      "answer": { "optionId":"…", "freeText":"…",
                  "action":"accept|decline|cancel",
                  "content":{ … } } } }
← { "type":"resolveInteraction", "resolvedBy": { "clientId":"…", "optionId":"…" } }
```

先到先得；迟到/重复调用是**幂等成功**（`delivered === false` 不抛错）。`answer.optionId === "fullAccess"` 时走 `resolveFullAccess`（全权限放行）。

**推迟自动应答倒计时**：`snoozeInteractionAutoResolution`，payload `{ interactionId }`。

**工作区 hooks 审核**：`respondWorkspaceHookReview` / `toggleWorkspaceHookReviewItem` / `revokeWorkspaceHookTrust` / `requestWorkspaceHookReview`。

### 7.13 目标 / 计划 / 工作流

| 用途 | 命令 |
|---|---|
| 设目标 | `sendGoalCommand` `{ text, displayText?, modelSelection?, mode?, planEnabled?, … }` |
| 暂停 / 恢复目标 | `pauseGoal` `{}` / `resumeGoal` `{}` |
| 压缩上下文 | `compact` `{}` |
| 停止当前轮 | `stop` `{ expectedForegroundExecutionId? }` —— 线上取值形如 `"runtime_command_65"`（前台租约 id，随状态流下发） |
| 计划目录 | 查询 `conversationPlansV4([{workspacePath, sessionId}])` → `{plans:[], atSeq, atLogEpoch}` |
| 工作流运行枚举 | `conversationWorkflowRunsV4` → `{runs:[]}` |
| 工作流事件日志 | `conversationWorkflowRunEventsV4` |
| 工作流产物 | `conversationWorkflowRunArtifactsV4` / `…ArtifactDataV4` / `…ArtifactReadV4` |
| 工作流 transcript | `conversationWorkflowRunWorkspaceV4` / `…NodeResultV4` |
| 恢复 / 启动 / 改设置 | `resumeWorkflowRun` `{workId, name?}` / `startSavedWorkflow` `{name, scope?, args?}` / `amendWorkflowRunSettings` `{workId, subagentModel?, maxConcurrency?}` |
| 取消后台任务 | `cancelBackgroundWork` `{workId}` |
| 后台 bash 输出 | `backgroundBashOutputV4([{workspacePath, sessionId, workId}])` |

### 7.14 会话管理

| 用途 | 命令 / 方法 |
|---|---|
| 重命名 | `renameSession` `{ title }` |
| 删除 | `deleteSession` `{}` —— 语义等同 `closeSession`（关闭 + 释放运行时），**不是真删记录**；历史仍在库里，只是从活动注册表消失 |
| 分叉 | `forkAssistant` `{ target }` |
| 副屏会话 | `createSelectionSideSession` `{ firstInput? }` |
| 反馈 | `setAssistantFeedback` `{ target, feedback: "like"|"dislike"|null }` |
| 丢弃共享上下文 | `discardSharedContext` `{ contextId }` |
| 幂等查询 | `queryConversationCommandsV4([{workspacePath, commands:[{sessionId, commandId}]}])` → `{results:[{key, result}]}`，`result` 可为 `unknown` |

---

## 8. 命令全集（34 个）

`commandPayloadSchemas` 的全部键（`packages/shared/src/zcode-protocol-v4/command.ts:44-247`）：

```
createSession                 createSelectionSideSession     sendText
sendGoalCommand               stop                           compact
forkAssistant                 applyFileRewind                editUserQuery
retryTurn                     setAssistantFeedback           sendQueuedNow
editQueueItem                 reorderQueueItem               deleteQueueItem
setAutoDrain                  resolveInteraction             respondWorkspaceHookReview
toggleWorkspaceHookReviewItem revokeWorkspaceHookTrust       requestWorkspaceHookReview
snoozeInteractionAutoResolution  switchModelConfig           switchCollaborationMode
setFollowupMode               pauseGoal                      resumeGoal
cancelBackgroundWork          resumeWorkflowRun              startSavedWorkflow
amendWorkflowRunSettings      renameSession                  deleteSession
discardSharedContext
```

**信封（`CommandEnvelope`）**

| 字段 | 必填 | 说明 |
|---|---|---|
| `commandId` | ✅ | **幂等键**；重试必须复用同一个 |
| `clientId` | ✅ | 必须与 `clientHello.clientId` 一致，否则 `fault.command.clientMismatch` |
| `sessionId` | ✅ | 仅 `createSession` 允许 `null` |
| `baseRevision` | 15 个命令需要 | CAS 基准，取快照 `revision` |
| `baseLogEpoch` | 5 个行定位命令需要 | **先于** `baseRevision` 校验 |
| `type` | ✅ | 上表之一 |
| `payload` | ✅ | 见各命令 |
| `issuedAt` | ✅ | 客户端时钟，仅遥测 |

**实测的 34 命令应答矩阵**（对不存在的会话 + 合法/空 payload 两种探针）：

| 命令 | 合法 payload | 空 payload | 解读 |
|---|---|---|---|
| `sendText` | `rejected / proto.sessionNotFound` | **ZodError: text** | schema 必填 `text` |
| `sendGoalCommand` | `rejected / proto.sessionNotFound` | `rejected / proto.invalidPayload` | 空目标在流程层拒绝 |
| `stop` | `rejected / proto.sessionNotFound` | `rejected / proto.sessionNotFound` | payload 本就 `{}` |
| `compact` | `rejected / proto.sessionNotFound` | 同左 | payload 本就 `{}` |
| `forkAssistant` | `rejected / proto.invalidPayload` | 同左 | 行解析不到即拒 |
| `applyFileRewind` | `rejected / proto.invalidPayload` | 同左 | 同上 |
| `editUserQuery` | `rejected / proto.invalidPayload` | 同左 | 同上 |
| `retryTurn` | `rejected / proto.invalidPayload` | 同左 | 同上 |
| `setAssistantFeedback` | `rejected / proto.invalidPayload` | 同左 | 同上 |
| `sendQueuedNow` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | 空 payload 缺 `queueItemId` |
| `editQueueItem` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `reorderQueueItem` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `deleteQueueItem` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `setAutoDrain` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `resolveInteraction` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `respondWorkspaceHookReview` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `toggleWorkspaceHookReviewItem` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `revokeWorkspaceHookTrust` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `requestWorkspaceHookReview` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `snoozeInteractionAutoResolution` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `switchModelConfig` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `switchCollaborationMode` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `setFollowupMode` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `pauseGoal` | `rejected / proto.sessionNotFound` | `rejected / proto.sessionNotFound` | payload 本就 `{}` |
| `resumeGoal` | `rejected / proto.sessionNotFound` | 同左 | payload 本就 `{}` |
| `cancelBackgroundWork` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `resumeWorkflowRun` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `startSavedWorkflow` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `amendWorkflowRunSettings` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `renameSession` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `deleteSession` | `rejected / proto.sessionNotFound` | 同左 | payload 本就 `{}` |
| `discardSharedContext` | `rejected / proto.sessionNotFound` | `proto.invalidPayload` | |
| `createSelectionSideSession` | `rejected / proto.sessionNotFound` | 同左 | payload 本就 `{}` |
| `createSession` | *（未测，避免真建会话）* | **ZodError: workspaceId** | schema 必填 `workspaceId` |

**结论**：空 payload 报 `proto.invalidPayload`（或 ZodError）的命令，其必填字段集由此得到实证；报 `proto.sessionNotFound` 的，说明 payload 的 `{}` 本身合法。

---

## 9. `CommandAck` 与错误码

### 9.1 CommandAck

```jsonc
{ "commandId":"…",
  "status":"accepted|rejected|stale|duplicate|noop|failed",
  "reasonCode":"…",                 // 可选
  "message":"…",                    // 可选（如 compile_failed 的诊断）
  "revisionAtDecision": 7,          // ★ 必有
  "result": { … } }                 // 可选，按命令类型
```

| status | 含义 |
|---|---|
| `accepted` | 已接受 |
| `rejected` | 被拒（`reasonCode` 说明原因） |
| `stale` | CAS 基准过期 → 用 `revisionAtDecision` 重设 `baseRevision` 后重试 |
| `duplicate` | 同一 `(sessionId, commandId)` 重复提交 |
| `noop` | 无变化（如 `config.unchanged`） |
| `failed` | 终态失败，**不会**被改写成 `duplicate` |

> **被拒命令仍然返回 `CommandAck`，不抛异常**；只有传输层失败才抛。`queryConversationCommandsV4` 是"已接受但 ack 丢失"的幂等探针。

### 9.2 `reasonCode` 目录

**`proto.*`**：`proto.sessionNotFound`、`proto.staleRevision`、`proto.staleLogEpoch`、`proto.staleTarget`、`proto.invalidPayload`、`proto.payloadTooLarge`、`proto.alreadyResolved`

**`fault.*`**：`fault.connection.handshakeRequired`、`fault.connection.helloRequired`、`fault.connection.clientChanged`、`fault.command.clientMismatch`、`fault.command.inputRejected`、`fault.command.executionFailed`、`fault.command.capabilityUnsupported`、`fault.command.queuePromotionCommitFailed`、`fault.command.assistantFeedbackUnsupported`、`fault.command.backgroundWorkCancelRejected.<reason>`、`fault.command.workflowRunResumeRejected.<reason>`、`fault.command.savedWorkflowStartRejected.<reason>`、`fault.command.workflowRunSettingsRejected.<reason>`、`fault.attachment.shareStatNotAuthorized`

**`guard.*`**：`guard.latestQueryEditOnly`、`guard.latestAssistantRetryOnly`、`guard.actionUnavailable`、`guard.forkTargetNotStable`、`guard.forkTargetAmbiguous`、`guard.forkAssistantOnly`、`guard.compactOperationLock`、`guard.planGoalMutuallyExclusive`、`guard.stopTargetChanged`、`guard.queueItemReserved`、`guard.queuePromotionBusy`、`guard.queueItemNotEditable`、`guard.heldQueueConfirmationStale`、`guard.workspaceRewindUnsafeFiles`、`guard.workspaceRewindIgnoredFiles`、`guard.workspaceRewindUnavailable`、`guard.workspaceRewindApplyConflict`

**其他**：`queue.itemMissing`、`queue.itemMissing`(noop)、`heldQueueDispositionRequired`、`config.unchanged`、`provider.notInRegistry`、`workspace_hooks_snapshot_mismatch`、`compactOperationLock`、`restoreWarning`、`activeTurn`

> `availability.reasonCode` 用的是**不带前缀**的 guard 短名（如 `noGoalToPause`），与命令 ack 的 `guard.*` 命名不同。

---

## 10. 实测证据附录

### 10.1 抓包产物

| 文件 | 内容 |
|---|---|
| `tools/relay-probe/transcript-handshake.json` | 握手 + 会话订阅，31 条线缆报文 |
| `tools/relay-probe/transcript-probe.json` | 全量探测：416 条线缆报文、105 次调用、98 帧、3 个订阅快照 |
| `tools/relay-probe/snapshot.json` | 1614 行会话的完整快照 |
| `tools/relay-probe/deltas.json` | 实时增量样本 |
| `tools/relay-probe/bootstrap.json` | 5 工作区 / 47 会话 |

### 10.2 探针过程中撞到的真实错误（均为协议约束的实证）

| 现象 | 原因 | 修法 |
|---|---|---|
| `fault.connection.handshakeRequired` | 没 `initializeConversationV4` 就 `subscribeConversationV4` | 先 hello → initialize |
| `fault.command.clientMismatch` | 信封 `clientId` ≠ clientHello 的 `clientId` | 全局统一一个 clientId |
| `ZodError: expected "clientHello" / protocolVersion 3 / appVersion` | clientHello 用了精简对象 | 补齐 `kind/protocolVersion/clientId/clientKind/appVersion` |
| `proto.staleRevision` | 故意用过期 CAS | 用 `revisionAtDecision` 重试 |
| `proto.staleLogEpoch` | `conversationFileChangesV4` 的 `baseLogEpoch` 不是当前值 | 用快照 `logEpoch` |
| `fault.attachment.shareStatNotAuthorized` | 对任意 ref 调 `conversationAttachmentStatV4` | 需真实 row/index 授权 |
| `TypeError: M is not iterable` | `window-controller.listTaskList` 参数形状不符（非 v4 面） | 属遗留接口 |

### 10.3 关于"浏览器抓包"

**2026-09-26 已补上浏览器实机验证**：在 ZCode 内置浏览器中打开线上网页端，页面内注入
WebSocket 抓包钩子（`prototype.send` 出站 + 构造函数 + `JSON.parse` tee 入站），逐项驱动 UI
并与本文档逐条对照。结果：**修正了 §3.2 的响应头形状（2 元而非 4 元）**，新增 §4.1 的
`zcode-task` / `usage-stats` / `marketing-touch` / `git` 等通道与方法，确认 sendText 免 CAS、
队列全流程、composer 芯片语义（值烤进下一条 sendText）等行为。完整报告见
**`docs/_mine/LIVE-WEB-VERIFICATION.md`**。

此前的字节级抓包走 Node 客户端接入同一 WebSocket（`tools/relay-probe/capture.mjs`），
逐字节记录双向报文——这仍是握手/控制面/大快照序列的权威证据源。
网页端行为反查（bundle 静态分析）见 `docs/_mine/REMOTE-WEB-CLIENT.md`；
注意线上 bundle 已从 `index-B-ilXaCQ.js` 换版为 `index-NjWRUABD.js`。

---

## 11. 坑与注意事项

**传输层**

1. 中继路径 `dataBase64` **不含** 13 字节 SocketProtocol 头；那套只用于桌面直连 socket。
2. 每收到一个 `rpc-frame` **必须**回 `rpc-frame-ack`，否则对端缓冲不释放。
3. 调用参数**必须位置数组化**，单参数也要 `[arg]`；`listen` 的 body 是**裸值**，不能数组化。
4. `clientHello.capabilities` 是 `.strict()` 且单向：只能回显宿主声明过的键。
5. `link.t` 是签发时间不是过期时间。

**会话层**

6. **先 `listen` 再 `subscribe`**，否则紧随 ack 的首帧快照会丢。
7. `subscribeConversationV4` 的参数是 **`workspacePath`**，不是 `workspaceKey`。
8. 订阅 ack **不含**快照；快照是随后的通知帧。
9. `envelope.clientId` 必须与 clientHello 一致。
10. `row.upserted` 是**整行替换**，当追加会复制出重复行。
11. `row.removed` 删除的是 `fromRowId` **及其之后所有行**。
12. `row.delta` 的 `rowId/path/append` 在**顶层**，无包装对象。
13. `state.updated` 是浅补丁、按 key 整体替换，**绝不深合并**；`pendingInteractions` 清空用"键存在 + 空数组"，要按**键是否存在**判断。
14. 未知 delta op 记日志跳过，别抛错。
15. `availability.*.allowed=false` **只是 UI 提示**，不是服务端闸门。
16. 快照 `sessionConfigStateSchema.mode` 有 `default("build")`，复用会把工作区默认 `yolo` 覆盖掉 —— 创建会话要用独立的 `createSessionRequestedConfigSchema`。
17. `deleteSession` 语义是 close，不是删记录。
18. `phase:"draft"` 的草稿会话只在内存里。
19. `assistantText.text` 是 Markdown，必须渲染。
20. **不要把协议枚举值直接当 UI 文案**：`yolo` / `queue` / `guide` 都是标识符，需要本地化。

**安全**

21. `model-selection.getView` 与 `provider-settings.getView` 的原始返回**带明文 API Key**，客户端必须白名单投影。
22. 中继链接等同密码；`hash` 与 `proof` 不得落盘/进日志。
23. 同时只允许一个远程控制端，新连接会踢掉旧的。

**CAS / 幂等**

24. `commandId` 必须跨重试稳定；它是幂等键。
25. `stale` 用 `revisionAtDecision` 重设基准后重试。
26. `baseLogEpoch` **先于** `baseRevision` 校验。
27. 被拒命令仍返回 ack，不抛异常；只有传输失败才抛。

---

## 12. 客户端实现状态（zcode-mobile）

本文档的协议面已落地到本仓库的 Android 客户端。对应关系：

| 协议面 | 实现位置 |
|---|---|
| 34 个命令 + CAS 强制 | `protocol/.../Commands.kt`（`REQUIRES_BASE_REVISION` / `ROW_TARGETING` 在**构建期**校验，缺 `baseRevision` 直接抛，不等运行期被拒） |
| 全部通道方法 | `protocol/.../ConversationApi.kt` |
| 会话列表（sessions-index） | `protocol/.../SessionsIndex.kt` + `ZCodeSession.subscribeWorkspaceStreams` |
| 任务管理（zcode-task + controller/tasks-index 推送） | `protocol/.../TaskService.kt`（列表五连 / renameTask / setTaskPinned / setTaskUnread / archive·unarchive；推送经 `window-controller.subscribeControllerV4`） |
| 工作区配置 | `protocol/.../WorkspaceConfig.kt` |
| 全局设置（白名单投影） | `protocol/.../SettingsService.kt` |
| 快照/增量投影（含队列、可用性、目标、后台任务） | `protocol/.../ConversationReducer.kt` |
| 命令编排 + `stale` 自动重试 | `app/.../session/ZCodeSession.kt` 的 `submit()` |
| 队列面板 / 目标 / 后台任务 | `app/.../ui/conversation/QueuePanel.kt` |
| 行级操作（编辑/回退/重试/分叉/评价） | `app/.../ui/conversation/RowAction.kt` + `RowRenderers.kt` |
| 会话管理（新建/重命名/关闭） | `app/.../ui/sessions/SessionListScreen.kt` |
| 设置页 | `app/.../ui/settings/SettingsScreen.kt` |

实现时踩到并已修正的几个点，都是文档里没有、只有对着真实报文才能发现的：

- **`stale` 自动重试要复用同一个 `commandId`**。协议把 `commandId` 定义为幂等键，所以用 `revisionAtDecision` 重设 `baseRevision` 重发是安全的：第一次其实已经落地的命令会被答 `duplicate` 而不是执行两次。
- **行级操作菜单由 `row.actions` 驱动**，不按 `kind` 猜。宿主会声明 `canEdit` / `canFork` / `canRetry` / `canRewindFiles` 与 `editDisposition`；按 kind 猜就会发出宿主必然拒绝的命令（`guard.latestQueryEditOnly` 等）。
- **行定位命令必须同时带 `rowId` 与 `entityId`**。`rowId` 只在一个投影代际内有效，宿主会把两者对同一份投影校验。
- **`model-selection.getView` 的推理档位在 `models[].config.optionSpecs.reasoningLevel.values`**，不在模型顶层。读错路径会静默得到空列表，表现是档位选择器永远显示"该模型不支持"。
- **中继不会对大响应分片**：实测 117 KB 的 `rpc-frame` 仍是 `fragmentCount=1`。所以不要为"大响应超时"去改分片重组——方向是错的。
- **被踢下线（`KICKED`）后不做自动重连**：中继同时只允许一个控制端，自动重连会和刚接管的那一端互相踢。客户端改为明确报错 + 提供"重新连接"。

---

## 13. 源码级细节索引


本文档是接口级全景。更细的源码级分析（含 `file:line` 引用）在：

| 文档 | 内容 |
|---|---|
| `docs/_mine/WIRE-TRANSPORT.md` | 线缆/鉴权/VQL/快照/增量的逐字段 schema 与字节细节（696 行） |
| `docs/_mine/COMMAND-SURFACE.md` | 34 个命令的完整 payload、ack 判定管线、reasonCode 目录（539 行） |
| `docs/_mine/SETTINGS-MODELS-SESSIONS.md` | 会话/设置/模型/供应商通道全表（603 行） |
| `docs/_mine/REMOTE-WEB-CLIENT.md` | 线上网页端行为反查 + bundle 原文证据 + "如何伪装成网页端"（618 行） |
| `docs/_mine/LIVE-WEB-VERIFICATION.md` | **线上网页端实机验证报告**（2026-09-26，浏览器驱动 + WS 抓包） |
| `docs/PROTOCOL.md` | 早期版本（部分内容已被本文档取代） |

**权威源码位置**（仓库 `ZCode_full`，version `3.14.3`）：

```
packages/shared/src/zcode-protocol-v4/command.ts       命令全集 / 信封 / CommandAck
packages/shared/src/zcode-protocol-v4/transport.ts     V4_METHODS（host↔CLI 内层方法名）
packages/shared/src/zcode-protocol-v4/delta.ts         增量 op 权威 schema
packages/shared/src/zcode-protocol-v4/snapshot.ts      快照 schema
packages/shared/src/zcode-protocol-v4/wire-codec.ts    载荷字节编解码
packages/rpc/src/serialization.ts                      VQL 值编码
packages/rpc/src/channels.shared.ts                    RequestType / ResponseType
packages/services/src/zcode-agent/zcodeAgent.ts        通道方法签名与参数接口
```
