# ZCode 中继协议 —— 逆向与实测记录

本文档记录的一切都经过**实机验证**：Node 参考实现（`tools/relay-probe/`）与 Kotlin 实现
（`protocol/`）均已成功连接 `zcode.z.ai` 中继，完成握手并读取真实会话数据。

> **性质说明**：本文档描述的是**你自己账号、你自己桌面**的客户端如何与 ZCode 服务通信。
> 中继服务端本身不开源，其实现不在公开仓库内。协议细节来源于：
> 1. 公开投放的前端 bundle（`/remote/v4/latest/assets/*.js`）静态分析
> 2. 浏览器 DevTools 抓包（你自己会话的 WebSocket 帧）
> 3. 对开源仓库 `zai-org/ZCode`（Apache-2.0）中 schema 的比对

---

## 1. 分层总览

```
┌─────────────────────────────────────────────────────────┐
│ 应用层：会话快照 / 增量、工作区列表、任务列表、命令        │
├─────────────────────────────────────────────────────────┤
│ v4 协议：clientHello、subscribe、snapshot/deltas        │
│   ← 开源：packages/shared/src/zcode-protocol-v4/        │
├─────────────────────────────────────────────────────────┤
│ Channel RPC：请求/响应/事件 多路复用，VQL 二进制序列化    │
│   ← 开源：packages/rpc/                                 │
├─────────────────────────────────────────────────────────┤
│ 中继帧层：data/rpc-frame，base64 + crc32 + 分片          │
│   ← 不在开源仓库内（外部 relay），本文档实测还原          │
├─────────────────────────────────────────────────────────┤
│ WebSocket JSON 控制面：auth_*、bootstrap、bridge         │
│   ← 不在开源仓库内，本文档实测还原                        │
└─────────────────────────────────────────────────────────┘
```

**重要结论**：中继路径上**没有** 13 字节 SocketProtocol 头。
`rpc-frame.dataBase64` 承载的是**裸 channel payload**。
13 字节头只用于直连 socket（CLI / 桌面）路径。

---

## 2. 连接与鉴权

### 2.1 端点

```
wss://zcode.z.ai/ws?mid=<deviceMid>
```

`mid` 来自链接的 `mid` 参数，可为空。

### 2.2 链接参数

来自 `https://zcode.z.ai/remote/v4?...`：

| 参数 | 含义 | 是否必需 |
|---|---|---|
| `sid` | 设备会话 ID | 必需 |
| `hash` | HMAC 密钥（base64） | 必需 |
| `t` | 签发时间戳（毫秒） | 必需，须为数字 |
| `mid` | 机器 ID | 可选 |
| `name` | 主机名 | 可选（客户端不上送） |
| `app_version` | 桌面版本 | 可选 |

缺少 `sid`/`hash`/`t` 时报 `Missing or invalid Web remote control relay parameters.`

`t` 是**签发时间**而非过期时间：实测签发 76 分钟后链接仍可用。

### 2.3 握手时序

四条消息，全为 JSON 文本帧：

```jsonc
// 1. 客户端 → 服务端（socket open 后立即发送）
{"type":"auth_init","role":"terminal","device_sid":"<sid>",
 "meta":{"platform":"web","version":"<app_version>","name":"mobile-browser"},
 "client_ts":1790329515187}

// 2. 服务端 → 客户端
{"type":"auth_challenge","server_ts":1790329689,"nonce":"8jKGNpQ8o54ghmZaoNQ4Vsu"}

// 3. 客户端 → 服务端
{"type":"auth_response","device_sid":"<sid>","proof":"<见下>","client_ts":1790329515188}

// 4. 服务端 → 客户端
{"type":"auth_ack","server_ts":1790329689,"device_sid":"<sid>",
 "terminal_sid":"t_PGsFwPCcUFDFgjp1JAbZ9B","pair_status":"matched"}
```

### 2.4 签名算法（逐字节确认）

```js
proof = base64url_nopad( HMAC-SHA256( key = hash, msg = `${nonce}|terminal|${device_sid}` ) )
```

- `base64url_nopad`：标准 base64 后 `+`→`-`、`/`→`_`、去掉 `=`
- 消息模板为 **`nonce|role|sid`**，顺序固定，分隔符为 `|`
- 密钥是链接里的 `hash` 原文（44 字符 base64，解码后 32 字节）
- `role` 固定为字面量 `terminal`

Kotlin 实现：

```kotlin
val mac = Mac.getInstance("HmacSHA256")
mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
val sig = mac.doFinal("$nonce|terminal|$deviceSid".toByteArray(Charsets.UTF_8))
Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
```

### 2.5 心跳

```jsonc
// 每 10s（有抖动），客户端 → 服务端
{"type":"pair_status_query","device_sid":"<sid>","client_ts":...}
// 服务端 → 客户端
{"type":"pair_status_ack","pair_status":"matched"}
```

`pair_status` 取值包含 `matched`（桌面已配对在线）。

### 2.6 错误码

关闭码：

| 码 | 含义 |
|---|---|
| 4004 | SessionNotFound |
| 4009 | SessionConflict |
| 4010 | DesktopDisconnected |
| 4011 | SessionExpired |
| 4012 | WorkspaceClosed |
| 4013 | InvalidMobileConnection |

消息级错误码：`KICKED`、`DEVICE_OFFLINE`、`AUTH_FAILED`、`WRONG_PARAM`、`INTERNAL`

---

## 3. 控制面

所有控制面消息走 `type:"data"`，`payload.zcode_type` 区分种类，
`requestId` 关联请求与响应。

### 3.1 工作区与任务列表

```jsonc
// 请求
{"type":"data","payload":{"zcode_type":"bootstrap-request",
  "requestId":"bootstrap-<uuid>"},"client_ts":...}

// 响应（实测 14 KB）
{"type":"data","payload":{"zcode_type":"bootstrap-response","requestId":"bootstrap-<uuid>",
  "result":{
    "desktopAppVersion":"3.14.3",
    "initialViewState":{"activeWorkspaceKey":"E:\\open_trae_m","activeTaskId":"sess_...","updatedAt":...},
    "mobileViewState":{...},
    "workspaces":[{"kind":"local","workspacePath":"E:\\open_trae_m","workspaceKey":"E:\\open_trae_m"}],
    "tasks":[{"taskId":"sess_...","title":"...","displayStatus":"running|completed|error",
              "provider":"glm","workspaceKind":"local","workspaceLabel":"open_trae_m",
              "workspacePath":"E:\\open_trae_m","createdAt":...,"updatedAt":...,"unreadAt":...}]
  }}}
```

### 3.2 打开工作区桥接

**必须先开 bridge 才能收发 channel 流量。**

```jsonc
// 请求
{"type":"data","payload":{"zcode_type":"workspace-bridge-open",
  "requestId":"workspace-bridge-<uuid>","bridgeSessionId":"bridge-<uuid>",
  "bridgeGeneration":1,"workspaceKey":"E:\\open_trae_m","taskId":"sess_..."}}

// 响应
{"type":"data","payload":{"zcode_type":"workspace-bridge-ready",
  "bridgeSessionId":"bridge-<uuid>",
  "bridge":{"bridgeSessionId":"bridge-<uuid>","bridgeGeneration":1,"kind":"local",
            "workspaceKey":"E:\\open_trae_m","workspacePath":"E:\\open_trae_m",
            "initialTaskId":"sess_..."}}}
```

其它控制消息：`workspace-reconnect` → `workspace-reconnect-response`、
`mobile-view-state-update`、`mobile-diagnostic`。

### 3.3 数据帧封装

```jsonc
// 双向
{"type":"data","payload":{
   "zcode_type":"rpc-frame",
   "bridgeSessionId":"bridge-...","bridgeGeneration":1,
   "seq":1,"messageSeq":1,"messageBytes":6,
   "checksum":{"algorithm":"crc32","value":"b4ff6360"},
   "fragmentIndex":0,"fragmentCount":1,
   "dataBase64":"BAEGyAEA…"
 },"client_ts":...}

// 收方回执
{"type":"data","payload":{"zcode_type":"rpc-frame-ack",
  "bridgeSessionId":"bridge-...","bridgeGeneration":1,"ackMessageSeq":1}}
```

- `seq` / `messageSeq`：各自单向上递增
- `messageBytes`：本条逻辑消息的字节数
- `fragmentIndex` / `fragmentCount`：分片（实测单帧场景恒为 0 / 1）
- `checksum`：对 `dataBase64` 解码后的字节做 crc32，8 位小写十六进制
- **`dataBase64` 解码后即裸 channel payload，不含 13 字节头**

crc32（多项式 `0xEDB88320`，初值/终值取反）：

```kotlin
var crc = 0xffffffff.toInt()
for (b in bytes) { crc = crc xor (b.toInt() and 0xff)
  repeat(8) { crc = if (crc and 1 != 0) 0xedb88320.toInt() xor (crc ushr 1) else crc ushr 1 } }
String.format("%08x", crc.inv().toLong() and 0xffffffffL)
```

---

## 4. Channel RPC 层

来源：`packages/rpc/src/{serialization,channelClient,channelServer,proxy-channel}.ts`（Apache-2.0）。

### 4.1 消息结构

```
message = serialize(header) + serialize(body)
```

| 方向 | header |
|---|---|
| 请求 | `[RequestType, id, channelName, methodName]` |
| 请求（取消） | `[RequestType, id]` |
| 响应 | `[ResponseType, id]`（`Initialize` 为 `[200]`） |

```ts
RequestType  = { Promise:100, PromiseCancel:101, EventListen:102, EventDispose:103 }
ResponseType = { Initialize:200, PromiseSuccess:201, PromiseError:202,
                 PromiseErrorObj:203, EventFire:204 }
```

### 4.2 VQL 序列化

格式：`[1 字节类型标签][VQL 长度（如适用）][数据]`
VQL 为 7 位一字节、最高位续位。

| 标签 | 类型 |
|---|---|
| 0 | Undefined |
| 1 | String |
| 2 | Buffer |
| 3 | VSBuffer |
| 4 | Array |
| 5 | Object（JSON 回退） |
| 6 | Int（VQL 编码） |

实例：首个服务端帧 `04 01 06 c8 01 00`
= `Array(1)` + `Int(200)` + `Undefined` = `serialize([200]) + serialize(undefined)`
= **`ResponseType.Initialize`**（通道就绪信号）。

### 4.3 参数约定（易错点）

服务端经 `ProxyChannel.fromService` 分派为 `target.apply(handler, args)`，
因此 **`call` 的参数必须是位置参数数组** —— 单参方法也要包成单元素列表。

```kotlin
// 对象方法无参调用 → 传空列表
channel.call("zcode-agent", "helloConversationV4")

// 带一个参数 → 单元素列表
channel.call("zcode-agent", "initializeConversationV4", clientHello)
```

`listen` 则**不是**数组，单个参数直接传（内部走 `target.call(handler, arg)`）。

### 4.4 通道名

`zcode-agent`（会话/Agent 服务）、`zcode-session`、`window-controller`、
`file`、`git`、`terminal`、`system`、`setting` 等。

---

## 5. v4 会话协议

来源：`packages/shared/src/zcode-protocol-v4/`（Apache-2.0，约 55 个 schema 文件）。

`V4_WIRE_PROTOCOL_VERSION = 3`。

### 5.1 握手

```kotlin
// 1. 读 host hello（无参）
val hello = channel.call("zcode-agent", "helloConversationV4")
```

实测响应：

```json
{"kind":"hello","protocolVersion":3,
 "connectionId":"host-rpc-7dd94b7d-...",
 "clientMode":"web-remote-replayable","deliveryProfile":"replayable",
 "serverTime":1790329835941,
 "capabilities":{"nativeDialogs":false,"localTerminal":false,
                 "binaryFrames":false,"compression":"none",
                 "workspaceHookReview":true,"independentPlanState":true,
                 "workflowRunDeltas":true},"auth":{}}
```

中继路径恒为 `web-remote-replayable` / `replayable`（手机档位）。

```kotlin
// 2. 回送 clientHello
channel.call("zcode-agent", "initializeConversationV4", mapOf(
  "kind" to "clientHello",
  "protocolVersion" to 3,
  "clientId" to "zcode-mobile-<uuid>",
  "clientKind" to "mobileApp",     // 也可 desktop / web / mobileRemote
  "appVersion" to "0.1.0",
  "capabilities" to mapOf("workspaceHookReviewUi" to true),
))
```

⚠️ `clientHello.capabilities` 是 `.strict()`：**只能声明 host 在 hello 里
先宣告过的键**（如 `workflowRunDeltas`），否则整条 clientHello 解析失败、连接握不上。

未握手就 subscribe 会得到 `fault.connection.handshakeRequired`。

### 5.2 订阅

```kotlin
channel.call("zcode-agent", "subscribeConversationV4", mapOf(
  "workspacePath" to "E:\\open_trae_m",   // 注意是 workspacePath
  "sessionId" to "sess_...",
))
```

实测响应：

```json
{"ack":{"subscriptionId":"sub-mugp7yvi-dzgfqnud-11","mode":"snapshot",
 "logEpoch":"mugp7yvi-dzgfqnud",
 "openTiming":{"version":1,"snapshotRowCount":294,"sessionRuntimeState":"warm",
               "cliProcessState":"reused","initialFrameEncodeMs":5}}}
```

`mode` 为 `snapshot`（全量）或 `resume`（带 `base` 时增量）。

### 5.3 下行帧

先订阅事件，再 subscribe：

```kotlin
channel.listen("zcode-agent", "onDynamicConversationFrame",
  mapOf("workspacePath" to workspaceKey)) { candidate -> /* ... */ }
```

`candidate` 形状（wire.ts）：

```jsonc
// 未分片
{"wireVersion":3,"kind":"complete","logicalFrameId":"...","logicalFrameOrdinal":1,
 "topic":"conversation/sess_...","subscriptionId":"sub-...",
 "frame":{ /* ConversationTopicFrame */ }}

// 分片（需按 logicalFrameId 聚合后 base64 解码拼接）
{"wireVersion":3,"kind":"fragment","fragmentIndex":0,"fragmentCount":3,
 "logicalBytes":12345,"checksum":{"algorithm":"crc32","value":"..."},
 "dataBase64":"...", ...}
```

`ConversationTopicFrame`：

```jsonc
{"topic":"conversation/sess_...","subscriptionId":"sub-...",
 "fromSeq":0,"toSeq":52741,"sentAt":1790329871000,
 "payload":{"kind":"snapshot","snapshot":{...}}      // 或
 "payload":{"kind":"deltas","deltas":[{...},{...}]}}
```

### 5.4 快照结构

顶层键：`protocolVersion, sessionId, logEpoch, seq, revision, control,
availability, inputRouting, meta, config, modelTransition, usage, queue,
pendingInteractions, pendingCommands, backgroundWorks, subagents, goal, plan,
workspaceHookAdmission, rows`

`rows` = `{window:[...尾部窗口...], totalCount:N, ...}`
实测 `snapshotRowCount=294`，窗口返回 60 行。

### 5.5 行（row）结构

判别字段是 **`kind`**（不是 `type`）。

公共字段：`rowId, turnId, entityId, productTurnId, visibility, createdAt,
createdAtSeq, kind, assistantResponseId`

观测到的 `kind` 与专属字段：

| kind | 专属字段 | 说明 |
|---|---|---|
| `reasoning` | `text`, `state` | 模型思考过程 |
| `assistantText` | `text`, `state` | 助手回复正文 |
| `toolCall` | `toolCallId`, `toolName`, `status`, `inputText`, `input`, `startedAt`, `output`, `endedAt` | 工具调用 |

实例：

```jsonc
{"rowId":267,"kind":"reasoning","entityId":"msg_...","visibility":"visible",
 "createdAt":1790329798138,"createdAtSeq":46749,"state":"complete",
 "text":"Now I have the method names. Let me check ..."}

{"rowId":237,"kind":"toolCall","toolName":"Bash","status":"success",
 "toolCallId":"call_00_PeVdIfVqwAgoHaAyA5MA4812",
 "inputText":"{\"command\":\"cd /e/open_trae_m ...\"}",
 "input":{"command":"cd /e/open_trae_m ...","description":"Extract V4 method name list"},
 "startedAt":1790329739717,"endedAt":...,
 "output":{"text":"=== V4_METHODS in transport.ts ===..."}}
```

`state` 取值含 `complete`，流式过程中应为增量态（配合 deltas）。

### 5.6 增量（delta）

权威定义在 `zcode-protocol-v4/delta.ts` 的 `conversationDeltaSchema`。
实测中最频繁出现的是 `row.upserted` 与 `state.updated`。

| op | 载荷 | 语义 |
|---|---|---|
| `row.appended` | `row` | 追加到尾部 |
| `row.upserted` | `row` | 按 `rowId` **整行替换**；id 不存在则追加 |
| `row.removed` | `fromRowId` | 删除该行及其后所有行（edit/retry 截断分支） |
| `row.delta` | `rowId`, `path`, `append` | 流式文本追加；`path ∈ {text, inputText, output.text, summaryText}` |
| `state.updated` | `patch` | 顶层字段整体替换（**绝不深合并**） |
| `workflowRun.updated` | … | workflow run 键级增量（手机端不做投影） |

⚠️ **两个容易写错的地方**（都是实测才暴露的）：

1. **没有 `row.path.appended`**，流式追加的 op 名是 `row.delta`，
   且 `rowId`/`path`/`append` 是**顶层字段**，没有 `target` 包装对象。
2. **`row.upserted` 不是追加**。把它当 append 处理会让每一条流式更新
   都追加一份新行，整个会话列表翻倍。

`state.updated.patch` 的字段（`statePatchSchema`）：

```
revision, control, sharedContextImport, availability, inputRouting, meta, config,
modelTransition, usage, queue, pendingInteractions, pendingCommands, backgroundWorks,
subagents, workflowRuns, goal, plan, workspaceHookAdmission
```

注意 `pendingInteractions` **也会从 patch 到达**——审批弹窗可能在任何时刻由增量
推开或收起，不能只依赖快照。清空（`[]`）时须按"键存在"判断，否则弹窗不会消失。

实测样本：

```jsonc
{"op":"row.upserted","row":{"rowId":594,"kind":"toolCall","toolName":"Bash",
  "status":"running","inputText":"{\"command\":\"cd /e/open_trae_m …\"}", …}}
{"op":"state.updated","patch":{"revision":3187}}
```


### 5.7 命令面（写路径）

命令通过 `sendConversationCommandV4` 提交，参数为 `{ workspacePath, envelope }`。

#### 信封结构

```jsonc
{
  "commandId": "<uuid>",        // 客户端生成，重试时必须不变（幂等键）
  "clientId": "<clientHello 的 clientId>",
  "sessionId": "sess_...",
  "baseRevision": 3078,         // 部分命令要求（CAS）
  "baseLogEpoch": "mugp7yvi-...",
  "type": "sendText",
  "payload": { ... },
  "issuedAt": 1790333437059     // 仅遥测，服务端不用于裁决
}
```

⚠️ **实测发现的硬约束**：信封里的 `clientId` **必须与 `initializeConversationV4`
握手时用的 `clientId` 完全一致**，否则返回 `fault.command.clientMismatch`。
这是最容易踩的坑——客户端若在握手和发命令时各生成一套 id，命令会被静默拒绝。

#### 命令类型全集

```
createSession              createSelectionSideSession  sendText
sendGoalCommand            stop                        compact
forkAssistant              applyFileRewind             editUserQuery
retryTurn                  setAssistantFeedback        sendQueuedNow
editQueueItem              reorderQueueItem            deleteQueueItem
setAutoDrain               resolveInteraction          respondWorkspaceHookReview
toggleWorkspaceHookReviewItem  revokeWorkspaceHookTrust  requestWorkspaceHookReview
snoozeInteractionAutoResolution  switchModelConfig       switchCollaborationMode
setFollowupMode            pauseGoal                   resumeGoal
cancelBackgroundWork       resumeWorkflowRun           startSavedWorkflow
amendWorkflowRunSettings   renameSession               deleteSession
discardSharedContext
```

#### `sendText` —— 发送消息

```jsonc
{
  "text": "消息内容",
  "requestedDelivery": "startNow" | "queue" | "guide",   // 缺省则按 inputRouting.mode
  "mode": "build" | "edit" | "plan" | "yolo",
  "planEnabled": false,
  "attachments": [ ... ],                                // 可选
  "modelSelection": { ... }                              // 可选
}
```

`requestedDelivery` 语义：`startNow` 原子抢占当前 turn（不排队），
`queue` 入队，`guide` 作为引导插入。

#### `resolveInteraction` —— 审批与追问（手机端最大价值）

```jsonc
{
  "interactionId": "<来自 snapshot.pendingInteractions>",
  "answer": {
    "optionId": "allow",                    // 选项 id
    "freeText": "拒绝理由",                  // 可选
    "action": "accept" | "decline" | "cancel",  // elicitation 风格
    "content": { ... }                      // AskUserQuestion 多题答案
  }
}
```

待审批项从快照的 `pendingInteractions` 数组读取，每项形如：

```jsonc
{
  "interactionId": "...", "kind": "...", "title": "...", "description": "...",
  "options": [{ "optionId": "allow", "name": "...", "kind": "...", "description": "..." }]
}
```

首到者胜，晚到为 noop（`reasonCode=proto.alreadyResolved`）。

#### 回执语义（CommandAck）

```jsonc
{
  "commandId": "...",
  "status": "accepted" | "rejected" | "stale" | "duplicate" | "noop" | "failed",
  "reasonCode": "fault.command.clientMismatch",   // rejected/stale/noop/failed 必带
  "message": "...",
  "revisionAtDecision": 3078,
  "result": { ... }                                // duplicate 回放缓存结果
}
```

- `accepted` **不承诺**跨 CLI 进程存活；最终以权威数据（行的 `sourceCommandId`）为准
- `stale` = CAS 版本不符，客户端应 resync 后重试
- `duplicate` = commandId 命中幂等表，`result` 是上次的缓存结果

#### 幂等查询

`queryConversationCommandsV4({ workspacePath, commands: [{sessionId, commandId}] })`
按 `commandId` 查回已提交命令的最终结果。实测回包：

```jsonc
{"key":{"sessionId":"sess_...","commandId":"b6b774cb-..."},
 "result":{"memoryEnabled":true,"commandId":"b6b774cb-...",
           "status":"accepted","revisionAtDecision":3078}}
```

#### 一个反直觉点

`snapshot.availability` 里的 `allowed: false`（如
`pauseGoal: {allowed:false, reasonCode:"noGoalToPause"}`）**只是 UI 提示，不是硬门禁**——
实测提交 `pauseGoal` 仍返回 `accepted`。客户端不应把它当作服务端拒绝的预判依据。

### 5.8 其它方法

`resyncConversationV4`、`unsubscribeConversationV4`、
`conversationRowsRangeV4`（向上翻历史，上限 200）、
`sendConversationCommandV4`（发消息，返回 `CommandAck`）、
`queryConversationCommandsV4`、`conversationPlansV4`、
`conversationFileChangesV4`、`conversationWorkflowRunsV4`、
`attachment{Begin,Chunk,Commit,Abort}V4`（附件，单块 ≤512 KiB）等。

---

## 6. 复现方式

```bash
# Node 参考实现
cd tools/relay-probe
printf '%s' "<remote url>" > link.txt
node read-conversation.mjs

# Kotlin 实现
cd ../..
<gradle> :verify:run --args="<remote url>"
```

---

## 7. 未决问题

- **中继服务端实现不开源**，仅客户端侧可得；服务端行为可能随时变化
- `t` 是否参与签名校验未确认（实测签名只用 `nonce|role|sid`）
- 分片重组路径（`fragmentCount > 1`）未在实测中触发，实现按 `wire-codec.ts` 推导
- `seq` 与 `messageSeq` 的语义差异（是否前者含 ack 帧计数）未确认
- v4 命令面（发消息、审批）未实测，仅按接口签名推导
