# 线上网页端实机验证报告（Browser Use + WS 抓包）

> 验证日期：2026-09-26
> 被测对象：`https://zcode.z.ai/remote/v4?...`（ZCode 桌面 3.14.3 的远程控制网页端）
> 验证方式：ZCode 内置浏览器（IAB）打开真实网页端，在页面内注入 WebSocket 抓包钩子，
> 逐项驱动 UI 并双向记录帧；同时以官方源码（`ZCode_full`，version 3.14.3）复核。
> 结论速览：**WS-API.md 的协议主体全部与线上行为吻合；修正 1 处文档错误（响应头形状）；
> 新增 4 个通道 + 10 余个方法的线上实证；确认一批纯 UI 行为。**

---

## 0. 抓包方法（可复现）

网页端 SPA 在模块初始化阶段就建立 WebSocket，常规 evaluate 注入必然错过连接。
本次采用三层钩子 + 诱导手段：

1. **`WebSocket.prototype.send` 补丁** —— 对**已存在**的 socket 也生效（原型方法在调用时查找），
   覆盖全部出站帧；
2. **构造函数补丁**（替换 `window.WebSocket`）—— 捕获之后新建 socket 的入站帧与关闭事件；
3. **`JSON.parse` 补丁** —— 应用解析入站文本帧必经此调用（运行时全局查找），tee 出入站消息的
   解析结果，弥补无法拦截已存在 socket 入站事件的缺口；
4. **诱导手段**：用 Node 探针客户端完成 `auth_init/auth_response` 握手占用控制端名额，
   把网页端踢下线（`error{code:"KICKED"}` → 网页端显示"重新连接"按钮）。

### 过程中确认的网页端架构行为

| 行为 | 实测证据 |
|---|---|
| **切换会话 = 整页导航**（非 SPA 路由） | 点击侧边栏任务后 `window.__wsLog` 消失（realm 重置），新 socket 重新握手 |
| **"重新连接" = 整页 reload** | 点击后同样 realm 重置，页面全量重载 |
| bundle 已换版 | 线上入口为 `index-NjWRUABD.js`（上一会话分析的缓存为 `index-B-ilXaCQ.js`） |
| 页面标题 | `ZCode - Web Remote Control` |

> 因此：握手 + bootstrap 首帧序列仍以 Node 抓包 transcript 为准（`transcript-handshake.json`），
> 本次浏览器捕获覆盖的是**页面稳定后的全部出站 + JSON.parse 可见的入站**。

---

## 1. 与 WS-API.md 逐项对照结果

### 1.1 完全吻合 ✅（线上实测 = 文档描述）

| 文档条目 | 线上实测 |
|---|---|
| §1.3 心跳 | `pair_status_query` 每 10s 左右一次（实测间隔 8.2–12s，15 组样本），应答 `pair_status_ack` |
| §2.5 帧确认 | 每个入站 `rpc-frame` 必回 `rpc-frame-ack`（一个 15s 窗口内 360 个 ack） |
| §5.2/§7.5 sendText | 信封与 payload 见 §2.2，ack 返回 `{status:"accepted", result:{type:"inputAccepted", delivery, inputId}}` |
| §7.14 setAssistantFeedback | 完整信封实抓，见 §2.3 |
| §7.13 stop | `stop` 实抓，见 §2.5 |
| §7.10 队列 | 入队 / sendQueuedNow / deleteQueueItem 全链路实抓，见 §2.4 |
| §7.13 会话视图附带查询 | 打开会话自动调用 `conversationPlansV4`、`conversationWorkflowRunsV4(limit:64)` |
| §2.2 mobile-view-state-update | 每次切换视图实发 `{viewState:{activeWorkspaceKey, activeTaskId, updatedAt}, deviceInfo}` |
| §6.3 sessions-index 增量 | 实测到达 op `session.upserted`（topic `sessions-index/E:\open_trae_m`） |
| 信封 clientId | `client-01a0d7e9-…`（与 clientHello 一致，全程不变） |
| commandId | UUIDv7 形态（`01a0db71-…`，时间前缀） |

### 1.2 文档修正 ❗（线上行为与文档表述不一致）

**响应/事件帧的头部是 2 元，不是 4 元。**

- 文档 §3.2 原文："header 固定 4 元数组：`[requestType|responseType, id, channelName, method|eventName]`"
- 线上实抓（多个 201/204 入站帧）：头部解出 `[201, id]`、`[204, listenId]`，**没有 channel/method 两项**
- 官方源码实锤（`packages/rpc/src/channelServer.ts` sendResponse）：

  ```ts
  case ResponseType.PromiseSuccess:
  case ResponseType.PromiseError:
  case ResponseType.EventFire:
  case ResponseType.PromiseErrorObj:
    this.send([response.type, response.id], response.data);   // ← 2 元
  case ResponseType.Initialize:
    this.send([response.type]);                                // ← 1 元
  ```

- 只有**客户端→服务端的请求**（Promise/EventListen）才是 4 元 `[type, id, channel, name]`；
  取消/释放（PromiseCancel/EventDispose）是 2 元 `[type, id]`（实测 `[103, 48]`）。
- **对实现的影响**：解析端不能假设响应头带 channel 名；事件归属要用 listen 时记录的
  `id → (channel, eventName)` 映射还原。zcode-mobile 的解码按 Array 解析天然兼容，但文档必须改。

### 1.3 文档确认（此前存疑、线上坐实）

- **sendText 不要求 baseRevision**。`command.ts` 的 `COMMANDS_REQUIRING_BASE_REVISION`
  恰好 15 个命令，`sendText` 不在其中；线上网页端对 revision=9 的会话发 sendText 省略
  `baseRevision`，宿主 `accepted / revisionAtDecision:9`。省略即"按最新 revision"。
  （文档 §7.5 示例信封里的 `baseRevision: 6` 是可选字段，不是必填。）
- **`queueItemId ≡ inputId`**：入队 ack 返回的 `result.inputId` 去掉 `queue_` 前缀后与后续
  `sendQueuedNow`/`deleteQueueItem`/`editQueueItem` 的 `queueItemId` 一致（`queue_01a0db7b-…`）。

---

## 2. 实抓帧样例（脱敏后）

### 2.1 发送消息（sendText）

```jsonc
// 出站（新建会话后的第一条，baseRevision 省略）
→ call zcode-agent.sendConversationCommandV4 [{
  "workspacePath": "E:\\open_trae_m",
  "envelope": {
    "commandId": "01a0db6e-…", "clientId": "client-01a0d7e9-…",
    "sessionId": "sess_af2f1838-…",
    "type": "sendText",
    "payload": { "text": "…测试消息…", "mode": "build", "planEnabled": false,
                 "modelSelection": { "providerId": "account:bigmodel-start-plan",
                                     "modelId": "GLM-5.3-Flash",
                                     "options": { "reasoningLevel": "max" } } },
    "issuedAt": … } }]

// 入站 ack
← PromiseSuccess { "status": "accepted", "revisionAtDecision": 8,
  "result": { "type": "inputAccepted", "delivery": "startNow", "inputId": "01a0db77-…" } }
```

要点：
- **composer 三个芯片（模型/思考/模式）的值都烤进下一条 sendText**：实测把模式芯片切到
  "完全访问"、思考芯片切到"高"后，下一条消息 payload 变为
  `mode:"yolo"` + `options.reasoningLevel:"high"`。芯片切换本身**不发任何命令**。
- **payload 里没有 `requestedDelivery`**：空闲时宿主路由 startNow，回合运行中点"加入队列"
  宿主路由 queue——路由完全由宿主 `inputRouting.mode` 决定，客户端不显式指定。
- 运行中的 composer 占位语变为"**继续输入以排队后续修改**"，发送按钮变为"**加入队列**"。

### 2.2 新建任务（同一动作的完整命令序列）

点击"新建任务"后同 realm 抓到（19 帧出站中的方法序列）：

```
client-scenes.list
EventDispose(48)                       ← 释放旧订阅
workspace-list-request（控制面）
setting.get
model-selection.onDidChange（listen ×3）+ model-selection.getView ×3
zcode-session.readWorkspacePresentation        ★ 文档未载
usage-stats.getEntitlementSnapshot             ★ 文档未载
zcode-agent.sendConversationCommandV4 ×3       （createSession 序列）
zcode-agent.subscribeConversationV4
```

`usage-stats.getEntitlementSnapshot` 实参（截断）：

```jsonc
[{ "includeSubscription": true,
   "preferredProviderId": "account:bigmodel-start-plan",
   "accountAccess": { "type": "zhipu-account", "family": "bigmodel", "planKind": "start-plan" },
   "allowDisabledPreferredProvider": true, "requirePreferredProvider": true, … }]
```

### 2.3 点赞（setAssistantFeedback，行定位命令样板）

```jsonc
→ call zcode-agent.sendConversationCommandV4 [{
  "workspacePath": "E:\\open_trae_m",
  "envelope": {
    "commandId": "01a0db71-…", "clientId": "client-01a0d7e9-…",
    "sessionId": "sess_af2f1838-…",
    "baseRevision": 8,
    "baseLogEpoch": "muhqnxnp-j30sjyla",        // ← 行定位命令双 CAS 基准
    "type": "setAssistantFeedback",
    "payload": { "target": { "rowId": 4, "entityId": "msg_muhqpk1x_…" }, "feedback": "like" },
    "issuedAt": … } }]
← { "status": "accepted", "revisionAtDecision": 8 }
```

### 2.4 队列全流程

1. **入队**：回合运行中输入文本 → 按钮变"加入队列" → 点击 →
   `sendText` ack 返回 `delivery:"queue"`。队列项渲染为会话流中的右侧气泡。
2. **自动排空**：长回合结束的瞬间，队列项被自动提升为新的运行回合（autoDrain 默认开）。
3. **队列项气泡操作**（完整四件套，与协议 §7.10 一一对应）：

   | UI 按钮 | 命令 |
   |---|---|
   | 拖拽排序（把手） | `reorderQueueItem` |
   | 立即 | `sendQueuedNow` |
   | 编辑 | `editQueueItem` |
   | 移除待发送消息 | `deleteQueueItem` |

4. **sendQueuedNow 实抓**：

   ```jsonc
   → { "type": "sendQueuedNow", "baseRevision": 69,
       "payload": { "queueItemId": "queue_01a0db7f-…" } }
   ```

   （同一帧发了两次，宿主幂等去重——第二次为 duplicate 类应答，无副作用。）

5. **deleteQueueItem 实抓 ×3**（`baseRevision` 64/75/84，随每次操作递增）。

6. **editQueueItem 未能通过 UI 复现**：两次编辑手势（Enter / blur）都落到了 `deleteQueueItem`
   （队列项编辑器的键盘语义与预期不同）。该命令的 payload 形状以 Node 抓包与官方 schema 为准。

### 2.5 停止（stop）

```jsonc
→ { "type": "stop",
    "payload": { "expectedForegroundExecutionId": "runtime_command_65" } }
← { "status": "accepted" }
```

★ `expectedForegroundExecutionId` 的真实形态是 `runtime_command_<N>`（随状态流下发的前台
租约 id），文档此前只有字段名没有取值样例。

---

## 3. 文档未载的通道与方法（本次新发现）

### 3.1 `zcode-task` 通道（网页端侧边栏的任务管理面）

| 方法 | 实抓参数 |
|---|---|
| `listTasks` | `[{ "workspacePath": "E:\\open_trae_m" }]` |
| `listPinnedTasks` | 同上 |
| `listArchivedTasks` | 同上 |
| `listPinnedTaskIds` | `[]` |
| `listDeletedTaskIds` | 同上 |
| `setTaskUnread` | `[{ "taskId": "sess_…", "workspacePath": "…", "unread": false, "expectedUnreadAt": 1790387875947 }]` |
| `getTaskSessionFilePath` | `[{ "workspacePath": "…", "taskId": "sess_…" }]` |
| `getTaskNativeSessionLogFile` | `[{ "taskId": "sess_…", "workspacePath": "…" }]` |
| `getWorkspaceProviderConfigFile` | `[{ "workspacePath": "…", "provider": "glm" }]` |

侧边栏每次变更都会重放 `listPinnedTaskIds → listArchivedTasks → listTasks → listPinnedTasks
→ listDeletedTaskIds` 五连。另有宿主主动推送的 **`controller/tasks-index`** 通知流
（EventFire，高频，侧边栏实时刷新的数据源）。

> 旧的 `window-controller`（`listTaskList`/`mutateTask`）在本次抓包中**未再出现**——网页端已整体
> 迁到 `zcode-task`。

### 3.2 其他新面

| 通道.方法 | 实抓参数 | 推测用途 |
|---|---|---|
| `marketing-touch.query` | `[{ "locale": "zh-CN" }]` | 营销/公告位内容 |
| `usage-stats.getEntitlementSnapshot` | 见 §2.2 | 用量/订阅权益快照 |
| `zcode-session.readWorkspacePresentation` | （新任务页触发） | 工作区呈现信息 |
| `zcode-session.readSession` | `[{ "workspacePath": "…", "sessionId": "sess_…", "messageLimit": 1 }]` | 轻量读取会话 |
| `model-selection.onDidChange`（listen） | — | 模型目录变更推送（新任务页监听 ×3） |
| `telemetry-report`（控制面出站） | `{ "event": { "elementName": "session_create", "eventType": "result", "talkId": "sess_…", "eventExtraDetail": { "create_source": "project", "client_kind": "mobile", … } } }` | 埋点 |

### 3.3 遥测暴露的客户端自述

`mobile-view-state-update.deviceInfo` 与 telemetry 显示：

- `platform: "web"`、`version: "3.14.3"`、`name: "mobile-browser"`
- `client_kind: "mobile"`（网页端自报为 mobile 类）
- UA 含 `ZCode/3.14.3 … Electron/41.0.3`——在 ZCode 桌面内嵌视图里打开时如此
  （本次 IAB 即宿主 Electron 的 BrowserView）

---

## 4. UI ↔ 协议映射（zcode-mobile 的本地化与结构参考）

### 4.1 模式选择菜单（composer 芯片）

菜单结构（DOM role 实测）：

```
menu "切换模式"
├─ menuitemcheckbox "计划模式 / 编辑前先出计划。"      ← plan 独立于 radio 组！
├─ ────────────────
├─ menuitemradio "变更前确认 / 改文件前先问我。" [checked]   ← build
├─ menuitemradio "自动编辑 / 自动编辑文件。"                  ← edit
└─ menuitemradio "完全访问 / 减少确认次数。"                  ← yolo
```

| 协议值 | 网页端文案 |
|---|---|
| `build` | 变更前确认 |
| `edit` | 自动编辑 |
| `yolo` | 完全访问 |
| `planEnabled: true` | 计划模式（独立 checkbox，与三档 radio 正交） |

**结构印证协议**：`planEnabled` 与 `mode` 是两个独立字段，网页端 UI 也做成了
checkbox + radio 两轴——zcode-mobile 若做成"四选一列表"，结构上就不对。

### 4.2 思考档位菜单

GLM-5.3-Flash 实测选项：`低 / 高 / 最高`（`optionSpecs.reasoningLevel.values` 的本地化）。
选择后同样只改 composer 待发配置。

### 4.3 队列气泡与运行态 composer

- 队列项 = 会话流中的右侧灰气泡（复制/编辑/立即/移除 + 拖拽把手）
- 运行中：发送按钮 → "加入队列"（有文本）或"停止生成"（空文本）；占位语"继续输入以排队后续修改"
- 点赞后按钮变"已赞" `[pressed]`

---

## 5. 未能在 UI 复现的部分（协议面已有 Node 实证）

| 项 | 原因 |
|---|---|
| `editUserQuery` / `retryTurn` 的 UI 触发 | 行操作按钮需精确 hover + 滚动定位，长会话下视口管理成本过高；协议形状见 WS-API.md §7.6（Node 探针 + 官方 schema 双重实证） |
| `switchModelConfig` / `switchCollaborationMode`（会话级命令） | composer 芯片是"下一条消息"的配置，不产生这两个命令；网页端可能在其他入口（如会话菜单）使用 |
| `renameSession` / `deleteSession` | 网页端标题自动生成，未走 UI 改名/关闭 |
| `setting.update` / `resolveInteraction` / 附件上传 | 未触发相应场景；IAB 文件选择器受限，附件三步事务已有 Node + 真机双实证 |
| 握手 + bootstrap 首帧序列 | SPA 在模块初始化期建连，钩子必晚于连接；以 `transcript-handshake.json` 为准 |

---

## 6. 对 zcode-mobile 的行动建议

1. **解码器**：响应头按 2 元处理（事件归属用 listen 映射还原 channel）——若当前实现假设
   4 元，遇到严格解析会碎。
2. **UI 结构**：模式选择器改为"三档 radio + 计划 checkbox"两轴。
3. **sendText**：`baseRevision` 可省（省略 = 最新 revision）；但队列/行定位命令必须带
   `baseRevision`（+行定位加 `baseLogEpoch`）。
4. **任务列表**：如需对齐网页端，接 `zcode-task.listTasks` 系列（`window-controller` 已过时）。
5. **文案**：模式/思考档位的中文文案可直接采用 §4.1/§4.2 的官方网页端措辞。
6. **stop**：可带 `expectedForegroundExecutionId`（从状态流的 foreground 租约读取），防止
   停错回合。

---

## 7. 证据文件

- 本文档的帧样例均来自 2026-09-26 会话的页面内抓包（出站完整、入站经 JSON.parse tee）。
- 字节级握手/控制面序列：`tools/relay-probe/transcript-handshake.json`、`transcript-probe.json`。
- 抓包工具：`tools/relay-probe/capture.mjs`（Node 侧）；浏览器侧钩子代码见 §0 与 §8。

---

## 8. 第二轮实测：设置页 / 搜索 / 右侧面板 / 移动端布局（2026-09-26 补充）

### 8.1 设置页（侧边栏"设置" = 全页设置应用）

非弹窗，是整页替换。默认落在**使用统计**页。左侧导航三组：

```
基础设置：常规 / 外观 / 模型设置 / 浏览器控制 / 键盘快捷键
Agent 能力：记忆 / 子智能体 / 插件 / MCP 服务器 / 技能 / 命令 / 钩子
数据与统计：使用统计（21.8 亿累计 Token 热力图 + 按模型分色趋势图）
```

各页流量：

| 页面 | 发出的调用 |
|---|---|
| 使用统计（落地页） | `setting.get` ×2、`usage-stats.getAppUsageSnapshot([{range:"all"\|"7d", timeZone:"Asia/Shanghai"}])`、`system.info`、`system.listIntegratedTerminalShells` |
| 模型设置 | `provider-settings.onDidChange`(listen) + `getView` + `refresh(["settings-manual"])`、`setting.get`、**`credential.load(["oauth:active_provider"\|"oauth:zai:access_token"\|"oauth:bigmodel:access_token"])`**、**`coding-plan-subscription.getStaticProducts` / `getStaticTeamProducts` / `getEnterprisePricing([{authenticated, family:"bigmodel"}])`**、`usage-stats.getEntitlementSnapshot` |
| 常规 | `setting.get` |

> ⚠️ **`credential.load` 的响应含 OAuth 明文 token**（`oauth:zai:access_token` 等），与
> `provider-settings.getView` 的 API Key 同级红线：客户端不得落盘/上屏。

**新通道汇总**：`system`（info / listIntegratedTerminalShells）、`credential`（load）、
`coding-plan-subscription`（getStaticProducts / getStaticTeamProducts / getEnterprisePricing）、
`file`（见 §8.2）、`terminal`（见 §8.3）。

### 8.2 搜索（Ctrl+K 命令面板）

打开即调 `window-controller.listTaskList([{kind:"active", workspaceScopes:[{workspacePath}], sortBy:"updated", limit:4}])` ——
**`window-controller` 并未废弃**：侧边栏走 `zcode-task`，搜索面板走 `window-controller`。

输入关键词后：

```
window-controller.listTaskList([{ …, search:"相机", limit:80 }])   ← 服务端任务内容搜索
file.listWorkspaceFilesLength([{rootPath}])                        ← 新通道 file
file.listWorkspaceFilesRange([{rootPath, offset:0, length:4000000}])
```

- 任务搜索匹配**会话内容**（结果项带消息片段摘录）。
- 文件搜索走 `file` 通道（列表分页，单请求上限 4 MB）。
- 面板常驻命令：新任务 Ctrl+N / 打开工作区 Ctrl+O / 设置 / 切换侧边栏 Ctrl+B / 切换终端 Ctrl+J /
  添加终端标签 / **添加审查标签** / 切换主题 / 技能 / MCP 服务器 / 问题上报 / 用户社群 / 产品文档 /
  **断开连接**。

### 8.3 右侧面板（标签页系统）

"展开侧边面板"打开空面板 → 显示"打开标签页"三选：**辅助对话 / 审查 / 终端**。面板头部有
`新增标签(+)` / `收起侧边面板`，已开标签可 × 关闭。

| 标签 | 行为与调用 |
|---|---|
| **终端** | **真远程 PTY**：`terminal.create([{cols, rows, cwd:"E:\\open_trae_m"}])` + `terminal.onDynamicData`/`onDynamicExit`(listen, id"1") + `terminal.resize([{id, cols, rows}])`；键盘输入走 **`terminal.write([{id, data:"echo zcode-ws-test\r"}])`**（含 `\r` 原样）。实测 PowerShell 会话正常交互 |
| **审查** | `git.refresh([{workspacePath, includeIdentity:true, includeBranchComparison:true}])` ×2；非 git 工作区显示空状态"当前 workspace 不在 Git 仓库中"，带 暂存 筛选与 刷新 |
| **辅助对话** | 未深入（选择器坐标漂移），推测为侧聊/选区对话 |

### 8.4 移动端布局（390×844）

**手机视口渲染的是一套完全独立的 UI**（即桌面扫码后在手机上看到的界面）：

- **首页**：`ZCode 远程控制 / 已连接到当前桌面窗口` 头部 + 提示卡（"二维码失效后需要回到桌面重新连接"）
  + **工作区卡片列表**（卡片含 本地 徽标、路径、任务数、更新时间、展开箭头、+ 按钮），
  任务行带状态徽章（**运行中** 蓝 / **已完成** 绿 —— 即 `displayStatus` 的本地化）。
- **会话页**（点任务进入）：`← 任务会话` 头部、会话名行（文件夹图标 + 标题 + … 菜单 + 面板开关）、
  Markdown 正文、**底部单行紧凑 composer**（+ / 完全访问盾 / 模型芯片 / 思考 / 发送），
  排队项以文本形式驻留在 composer 上方。
- **会话 "…" 菜单**：置顶任务 / 重命名任务 / 归档任务 / 标记为未读 / 复制路径 / 复制任务路径 /
  复制日志路径 / 复制会话 ID / 查看调用轨迹 / 反馈问题。
- **重命名走的是 `zcode-task.renameTask([{taskId, workspacePath, title}])`**——不是 v4 命令
  `renameSession`（后者协议面存在但网页端未用）。
- 视口切换（桌面→手机）时旧面板状态会残留到移动布局（真机直接以手机尺寸加载则无此问题）。

### 8.5 对 zcode-mobile 的追加建议

1. **移动端 UI 直接以本节为蓝本**：卡片工作区列表 + 状态徽章 + 紧凑 composer，与 Trae 手机端
   风格对齐的同时也和官方网页端一致。
2. **重命名**：可改走 `zcode-task.renameTask`（比 v4 `renameSession` 少 CAS 负担）；置顶/归档/
   未读同理（对应 listPinnedTasks/listArchivedTasks/setTaskUnread 的变更侧）。
3. **搜索**：接 `window-controller.listTaskList({search})` 即得任务内容搜索；文件搜索接
   `file.listWorkspaceFiles*`。
4. **终端视图**（原缺口）：`terminal` 五个方法就是完整实现路径（create/write/resize +
   onDynamicData/onExit），中继路径可用。
5. **credential.load 响应脱敏**：OAuth token 红线与 API Key 相同。

---

## 9. 供应商管理 CRUD 实测（2026-09-26，回答"可以直接实现吗"）

**可以。** 14 个方法全部在真实中继上实测通过，且用一次性探针供应商跑了完整生命周期
（创建 → 加模型 → 改名 → 禁用 → resolve → 排序 → overlay 替换配置 → 模型草稿（CAS）→
逐级删除 → 注册表还原校验）。最终 `providerOrder` 与 baseline 逐字一致——用户真实供应商零影响。

方法与参数见 **WS-API.md §7.11.1**（完整表格）。此处只记实测独有的发现：

| 发现 | 实测证据 |
|---|---|
| `createPersonalProvider` 无参也可 | `[]` 直接建出 `new-provider-9`；带 `{providerName}` 建出 `new-provider-8` |
| **overlay 的 config schema 是 `.strict()` 且不含 `providerName`** | 回传 `effectiveConfig` 原样 → `ZodError: Unrecognized key "providerName"`；剥成纯 `{access, api}` 后通过，字段逐字回读 |
| 全局修订号 | `view.revision` 每次 mutation +1（18→28 连跨 10 次变更）；`savePersonalModelDraft.basedOnRevision` 用它做 CAS |
| `resolveModelConfig` 的 issues 可驱动表单校验 | `"缺少必填配置 providers.<id>.models.<m>.optionSpecs.maxOutputTokens.map"` |
| `testModelConnectivity` 三种结果形状 | 未注册/禁用 → `{success:false, error:{code:"model-unavailable",…}}`；启用但不可达 → `{success:false, error:{message:"Model request was cancelled."}}`；真实可用模型 → `{success:true}`（对用户当前账号模型实测）。**可能 >25s** |
| 每个 mutation 返回新 View | 不必再 getView 就能拿到新 revision/order |

结论：**供应商管理（增删改查、排序、启停、模型 CRUD、配置替换、连通性测试）协议面全部实证，
zcode-mobile 可以直接实现**。唯一要注意的设计点是 `getView` 返回里的明文 API Key 必须白名单
投影后再上屏（既有 `ModelCatalogService` 的红线，同样适用于这一整块）。
