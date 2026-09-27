# ZCode — Sessions, Settings, Models, Providers, Modes

Ground truth: `E:/open_trae_m/ZCode_full` (v3.14.3, Apache-2.0).
Protocol package: `packages/shared/src/zcode-protocol-v4/`.
RPC service channels: `packages/shared/src/channels.ts` (`ServiceChannels`, lines 75-152).
Host-side service descriptors + implementations: `packages/services/src/`.
CLI-native v4 command handlers: `apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/commands/handlers/`.

All identifiers quoted verbatim from source. Live shapes cross-checked against
`E:/open_trae_m/zcode-mobile/tools/relay-probe/{bootstrap.json,snapshot.json}` (captured from the running desktop).

Two parallel protocol generations exist and both are still wired:

* **v4** (`zcode-protocol-v4`): topic subscriptions (`sessions-index/<ws>`, `conversation/<sid>`, `workspace-config/<ws>`) + `v4/*` RPC methods + 34 commands. This is the authoritative surface.
* **legacy** (`zcode-protocol`): `session/list`, `session/read`, `session/create`, … plus the desktop `zcode-agent` / `zcode-session` / `window-controller` service channels.

---

## 0. Channel inventory (settings / session / model / provider surface)

`ServiceChannels` (`channels.ts:75-152`). A client calls `<channel>.<method>(arg)` over RPC (`packages/rpc/src/channelClient.ts:37-49`).

| channel string | const | descriptor file | role |
|---|---|---|---|
| `setting` | `Setting` | `services/src/setting/setting.ts:19` | App settings `setting.json` read/update |
| `settings-sync` | `SettingsSync` | `services/src/settings-sync/settingsSync.ts:39` | Claude→ZCode import, first-run prompt |
| `model-selection` | `ModelSelection` | `services/src/model-provider/providerFacadeServices.ts:105` | **model catalog** (`getView`) |
| `provider-settings` | `ProviderSettings` | `services/src/model-provider/providerFacadeServices.ts:73` | provider config CRUD (`getView`, save/delete/reorder) |
| `provider-provisioning-target` | `ProviderProvisioningTarget` | `services/src/model-provider/providerProvisioning.ts:10` | push provider config + credentials to a remote env |
| `credential` | `Credential` | `services/src/credential/credential.ts:17` | key/value secret store (`load`/`save`/`delete`) |
| `cua-permission` | `CuaPermission` | `services/src/cua-permission-broker/cuaPermissionService.ts:53` | macOS accessibility/screen-recording status |
| `hooks` | `Hooks` | `services/src/hooks/hooks.ts:39` | workspace hooks load/save/trust |
| `mcp-sync` | `McpSync` | `services/src/mcp-sync/mcpSync.ts:47` | MCP config list/import/export |
| `client-config` | `ClientConfig` | `services/src/client-config/clientConfig.ts:13` | remote public config snapshot |
| `client-scenes` | `ClientScenes` | `services/src/client-scenes/clientScenes.ts:63` | server-driven home scene catalog |
| `window-controller` | `WindowController` | `services/src/window-controller/windowController.ts:69` | task-list projection + membership mutations |
| `zcode-agent` | `ZCodeAgent` | `services/src/zcode-agent/zcodeAgent.ts:864` | agent bridge: session CRUD, v4 subscribe/command passthrough |
| `zcode-session` | `ZCodeSession` | `services/src/zcode-session/zcodeSession.ts:158` | legacy session app service |
| `zcode-task` | `ZCodeTask` | `services/src/session/zcodeTaskService.ts:744` | task/session management + index sync |
| `memory` | `Memory` | `services/src/memory/memory.ts:35` | project memory read |
| `commands` | `Commands` | `services/src/commands/commands.ts:28` | slash command catalog |
| `plugins` / `plugin-management` | `Plugins` / `PluginManagement` | `services/src/plugins/*` | plugin config/enable |
| `subagents` | `Subagents` | `services/src/subagents/*` | subagent profiles |
| `skills` / `skill-sync` | `Skills` / `SkillSync` | `services/src/skills`, `skills-sync` | skills |
| `off-peak-task` | `OffPeakTask` | `services/src/session/offPeakTask.ts:45` | off-peak task CRUD |
| `onboarding-record` | `OnboardingRecord` | `services/src/onboarding/onboardingRecord.ts:69` | onboarding completion record |
| `oauth`, `coding-plan-subscription`, `bots`, `feedback`, `broadcast`, `conversation-share`, `prompt-attachment-transfer` | | | auth/plan/social |

v4 wire methods: `V4_METHODS` (`transport.ts:332-384`) — `v4/command`, `v4/commands/query`, `v4/conversation/{subscribe,resync,unsubscribe,rowsRange,plans,fileChanges,workflowRuns,…}`, `v4/usage/stats`, `v4/attachment/*`.
v4 notifications: `V4_NOTIFICATIONS` (`transport.ts:407-415`) — `v4/conversation/frame` (all three topics), `v4/telemetry/event`, `v4/telemetry/local-ttft`, `v4/cua/permission-observation`.
Wire protocol version `V4_WIRE_PROTOCOL_VERSION = 3` (`core.ts:7`); projection snapshots independently use `protocolVersion: 1`.

---

## 1. Session list / index

### 1a. v4 `sessions-index` topic (authoritative list)

There is **no `getView`** on this channel. It is a conflated topic subscription (same machinery as `conversation`):

* Topic key: `sessions-index/<workspaceId>` — `sessionsIndexTopic()` / `parseSessionsIndexTopic()` (`sessions-index.ts:76-85`). `workspaceId` = `workspaceIdentity?.trim() || workspacePath` (see `windowHostSessionsIndexObserver.ts:60`).
* Subscribe via `v4/conversation/subscribe` (shared RPC, `transport.ts:449-455` `v4SessionsIndexSubscribeResultSchema` = ACK-only). Params (`ZCodeAgentSessionsIndexSubscribeParams`, `zcodeAgent.ts:515-531`): `{ workspacePath, workspaceIdentity?, base?: {logEpoch, seq}, visibility?: "foreground"|"background", subscriberScope?: string, runtimePolicy?: "start-if-needed"|"existing-only" }`.
  * `subscriberScope` is mandatory-in-practice when multiple in-process consumers share one host connection (else they replace each other's subscription generation).
  * Passive list observers must use `runtimePolicy: "existing-only"` (do not boot an agent just to list).
* Initial frame arrives **after** the subscribe ACK, as a `v4/conversation/frame` notification. Frame schema `sessionsIndexTopicFrameSchema` (`transport.ts:223-234`): `{ topic, subscriptionId, fromSeq, toSeq, sentAt, payload: {kind:"snapshot", snapshot} | {kind:"deltas", deltas} }`. Snapshot frame has `fromSeq === 0`.
* Resync: `v4/conversation/resync` with `{subscriptionId, base: {logEpoch, seq}|null, forceSnapshot?}` (`transport.ts:469-498`); gap detected when `frame.fromSeq !== cursor.seq` (`windowHostSessionsIndexObserver.ts:120`).

**Snapshot** `sessionsIndexSnapshotSchema` (`sessions-index.ts:58-66`):

```
{ protocolVersion: 1, workspaceId: string, logEpoch: string, sessions: SessionSummary[] }   // unordered
```

**Delta** `sessionsIndexDeltaSchema` (`sessions-index.ts:68-73`) — conflation key = `sessionId`:

```
{ op: "session.upserted", session: SessionSummary } | { op: "session.removed", sessionId: string }
```

**Every field of `SessionSummary`** (`sessionSummarySchema`, `sessions-index.ts:29-56`):

| field | type | req | meaning / source |
|---|---|---|---|
| `sessionId` | string | yes | session id |
| `workspaceId` | string | yes | workspace key |
| `parentSessionId` | string? | opt | fork tree parent |
| `title` | string | yes | `snapshot.meta.title` |
| `titleSource` | `"default"｜"generated"｜"custom"`? | opt | optional for old frames; `custom` = user renamed |
| `phase` | `sessionPhaseSchema` | yes | `draft｜prewarming｜running｜completedSuccess｜completedInterrupted｜error` (`snapshot.ts:28-37`) |
| `sessionEnded` | boolean | yes | faithful pass-through of `control.sessionEnded`; **not** deletion |
| `hasBackgroundWork` | boolean | yes | `backgroundWorks.some(w => w.status === "running")` |
| `workflowActivity` | `SessionWorkflowActivity`? | opt | see §1c |
| `pendingInteraction` | `{interactionId, kind:"permission"｜"userInput", toolName?, autoResolution?}`? | opt | first permission/userInput pending; **no question/answer payload** |
| `pendingInteractionSummary` | `{permissionCount:int≥0, userInputCount:int≥0}`? | opt | absent when both 0 |
| `goalStatus` | `goalStateSchema.status`? | opt | `active｜paused｜verifying｜verified｜notSatisfied｜failed` |
| `lastActivityAt` | number (unix ms) | yes | client derives unread by comparing local `lastSeenActivityAt` (not `seq`; epoch resets) |
| `lastAssistantPreview` | string? | opt | last non-blank `assistantText` row, ≤120 chars (`MAX_PREVIEW_CHARS`) |
| `createdAt` | number (unix ms) | yes | |

Derivation is a pure function in `apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/sessions-index-projection.ts:24-101` (`deriveSessionSummary`); `workspaceHookReview` pending items are deliberately **excluded** from the sidebar badge (`:50-53`). `summariesEqual` (`:104-125`) is the conflation test.

**Filtering / search / paging:** none on the v4 topic — the snapshot carries all sessions of one workspace, unordered. Sorting and search are client-side. Paged/searchable list queries exist only on `window-controller.listTaskList` (see §1d).

### 1b. Cold seed

`SessionsIndexPublisher.seed()` / `mergeMissingStoredSummaries()` (`sessions-index-publisher.ts:63-75`) seed stored (non-live) sessions; `insertSeedIfMissing` never overwrites an existing live/seed summary (`sessions-index-projection.ts:203-207`).

### 1c. `workflowActivity` variant

`sessionWorkflowActivitySchema` (`sessions-index-workflow-activity.ts:68-71`):

```
{ runs: SessionWorkflowRunSummary[] }   // ≤ SESSION_WORKFLOW_ACTIVITY_MAX_RUNS = 4 (:21)
```

`sessionWorkflowRunSummarySchema` (`:47-66`):

| field | type | notes |
|---|---|---|
| `runId` | string | |
| `toolCallId` | string? | click key to open run pane; `launch-` prefix for direct launches |
| `name` | string? | background-work title; absent if no matching background work |
| `status` | `pending｜running｜completed｜errored｜stopped` | |
| `stopReason` | `user｜model｜provider｜interrupted｜superseded`? | |
| `startedAt` | number? | from background work |
| `phases` | `SessionWorkflowPhaseSummary[]` (≤ maxPhases) | declared order, else entered-order fallback |
| `currentPhase` | string? | |
| `agentsWorking` | int ≥0 | count of `actors[].status === "running"` |

`SessionWorkflowPhaseSummary` = `{ name (1..maxPhaseNameLength), status: "pending"｜"running"｜"done"｜"failed", alongside?: number[] }` (`:35-45`).
Ordering: live runs (pending/running) first in `runs[]` order, then settled runs by `endedAt` desc, then `runs[]` index desc; truncated to 4 (`:170-195`). `undefined` when the session has zero runs (key entirely absent).

### 1d. Desktop task list (`window-controller`)

`IWindowControllerService` (`windowController.ts:52-67`):

* `listTaskList(ZCodeTaskListQuery) → WindowHostControllerTaskListResult` — `ZCodeTaskListQuery` = `{ kind: "pinned"｜"archived"｜"timeline"｜"active", workspaceScopes: {workspacePath, workspaceIdentity?, workspacePurpose?}[], sortBy: "created"｜"updated", search?: string, limit?: number }` (`session/zcodeTaskListTypes.ts:3-29`). This is where **search + limit** live.
* `mutateTask({address, mutation}) → ZCodeTaskMeta | null`; `WindowHostControllerMutation` (`windowController.ts:23-31`) = `{kind:"pin",pinned}` | `{kind:"archive",archived}` | `{kind:"delete"}` | `{kind:"delete-archived"}` | `{kind:"mark-read",expectedUnreadAt?}` | `{kind:"mark-unread"}` | `{kind:"open"}` | `{kind:"resume"}`.
* `deleteArchivedTask(s)`.
* `subscribeControllerV4/resyncControllerV4/unsubscribeControllerV4` + `onDynamicControllerFrame` — topics `controller/workspaces` and `controller/tasks-index` (`controller.ts:8-9`).

`WindowHostControllerTaskRow` (`controller.ts:90-120`): `{ address:{remoteSessionId?, workspacePath, workspaceIdentity?, taskId}, meta: ZCodeTaskMeta, membership:{pinned,archived,active}, sourceAvailability:"online"｜"offline", liveStatus:"idle"｜"running"｜"waiting"｜"completed"｜"error", activity?:{phase, lastActivityAt, hasBackgroundWork, pendingInteractions?, workflowActivity?}, searchSnippets? }`.

### 1e. Legacy list

* `session/list` (`zcodeProtocolMethods.sessionList`, `zcode-protocol/index.ts:3568`): params `zcodeSessionListParamsSchema` (`:1601-1610`) = `{ workspace?: ZCodeWorkspaceRef, sessionIds?: string[1..64], includeArchived?: boolean=false, limit?: int>0 }`; result `{ sessions: ZCodeSessionInfo[] }` (`:1515-1520`).
* `ZCodeSessionInfo` (`zcode-protocol-legacy-types.ts:135-153`): `{ sessionId, workspace, parentSessionId?, traceId?, sessionKind, title, titleSource?: "default"｜"first_input"｜"generated"｜"custom", mode, status, model?, target?, createdAt, updatedAt, archivedAt? }`.
* `ZCodeTask` channel `listSessions` is proxied through `zcode-agent.listSessions(ZCodeAgentListSessionsParams)` (`zcodeAgent.ts:588`; params `:208-213` = `{sessionIds?, runtimePolicy?, includeArchived?, limit?}`).

### 1f. Mobile relay bootstrap (observed)

The mobile relay returns an aggregated payload (`tools/relay-probe/bootstrap.json`), not part of the open-source repo:

```
{ requestId, success, zcode_type,
  result: { desktopAppVersion, initialViewState:{activeTaskId,activeWorkspaceKey,updatedAt},
            mobileViewState:{...}, windowControlSessionId,
            workspaces:[{kind:"local", label, workspacePath, workspacePurpose?}],
            tasks:[{ taskId, title, displayStatus:"running"|"completed"|…, provider,
                     workspacePath, workspaceLabel, workspaceKind, createdAt, updatedAt, unreadAt? }] } }
```

Note the same `taskId` can appear twice (stale `completed` + live `running`); `TaskParsing.kt` collapses by `taskId` preferring the `running` row. `displayStatus`/`workspaceLabel` are relay-side derived fields, not v4 protocol fields.

---

## 2. Session state

### 2a. v4 live state — `ConversationSnapshot`

Read by subscribing `conversation/<sessionId>` (`v4/conversation/subscribe`; params `zcodeAgent.ts:353-357` = `{sessionId, base?, visibility?}`). Initial frame after ACK; deltas follow. ACK may carry `openTiming` diagnostics (`transport.ts:136-156`).

`conversationSnapshotSchema` (`snapshot.ts:470-509`) — top-level keys, all present in a cold snapshot:

| key | schema | notes |
|---|---|---|
| `protocolVersion` | `1` | |
| `sessionId`, `logEpoch` | string | |
| `seq` | number | alignment watermark (= frame `toSeq`) |
| `revision` | number | CAS revision for commands |
| `control` | `sessionControlSchema` | see below |
| `availability` | `sessionActionAvailabilitySchema` | see §2b |
| `inputRouting` | `{mode, reasonCode?}` | see §2c |
| `meta` | `{title, titleSource}` default `{title:"",titleSource:"default"}` | |
| `sharedContextImport` | `{contextId,title,shareUrl,status}`? or legacy `{title}` | `shared-context-import.ts:3-38` |
| `config` | `sessionConfigStateSchema` | **the "sessionConfig"** — see §2d |
| `modelTransition` | `sessionModelTransitionSchema \| null` default `null` | registry fallback notice: `{eventId, origin:"registryFallback", from:{provider,model}, to:{provider,model}}` |
| `usage` | `{contextWindow: {usedTokens,maxTokens,autoCompactThresholdTokens,cache?,breakdown?}\|null, cumulative:{inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens}}` | |
| `queue` | `queueStateSchema` | see §2e |
| `pendingInteractions` | `PendingInteraction[]` | see §7 |
| `pendingCommands` | `{commandId,clientId,type,state:"accepted"｜"executing",at}[]` (≤32) | |
| `backgroundWorks` | `BackgroundWorkSummary[]` | `{workId, kind:"bash"｜"subagent"｜"workflow", title, status:"running"｜"resultPending"｜"failed"｜"cancelled", startedAt, endedAt?, cancellable?, blocked?, anchorRowId, childSessionId?}` (`snapshot.ts:362-380`) |
| `subagents` | `{revision, childSessionIds[], running:[…], endedTotal}`? | optional for old snapshots only |
| `workflowRuns` | `WorkflowRunsState`? | optional for old snapshots only; cold snapshots always carry it |
| `goal` | `GoalState \| null` | `{targetId, objective, summaryTitle, timeUsedSeconds, activeRunStartedAtMs, status, iteration, verifications[], iterations[]}` (`:419-441`) |
| `plan` | `{items:[{id,content,status:"pending"｜"inProgress"｜"completed"}], updatedAt} \| null` | `:444-448` |
| `workspaceHookAdmission` | `{pendingCount, bundleDigest, workspaceIdentity?} \| null` default `null` | soft-gate hook review |
| `rows` | `{window:[ConversationRow], totalCount, firstRowId\|null}` | tail window, `snapshotTailWindowRows = 60` |

Deltas: `conversationDeltaSchema` (`delta.ts:94-144`) — 7 ops: `row.appended`, `row.upserted`, `row.removed`, `row.delta`, `state.updated` (patch), `workflowRun.updated`, `workflowRun.removed`. **`statePatchSchema` (`delta.ts:39-62`) is field-level whole-key replacement, never deep merge**; key set is closed.

`sessionControlSchema` (`snapshot.ts:128-141`): `{ phase, sessionEnded, canStop, stopState:"idle"｜"stoppable"｜"stopping", stopTargetKind, activeWorks:[{kind, foregroundExecutionId?, startedAt}], lastError: SessionErrorInfo|null, apiRetry: {attempt,maxAttempts,nextRetryAt,reasonCode}|null }`.
`sessionPhaseSchema` (`:28-37`): `draft | prewarming | running | completedSuccess | completedInterrupted | error`. `draft` is memory-only, invisible to disk, gone on CLI restart.

### 2b. `availability` — every key and its semantics

`sessionActionAvailabilitySchema` (`snapshot.ts:151-161`); each value is `actionAvailabilitySchema` = `{allowed:true}` **or** `{allowed:false, reasonCode:string}` (`:144-149`). Computed by `computeAvailability` (`projection-state.ts:117-155`):

| key | denied when | reasonCode |
|---|---|---|
| `fork` | compacting | `compactOperationLock` |
| | `phase === "draft"` | `forkTargetNotStable` |
| `compact` | compacting | `compactOperationLock` |
| | `phase === "draft"` | `idleCannotCompact` |
| `switchModelConfig` | never | — (always `{allowed:true}`) |
| `setFollowupMode` | never | — |
| `queueEdit` | never | — |
| `sendQueuedNow` | compacting | `compactOperationLock` |
| | not running/prewarming | `sendQueuedNowRequiresRunning` |
| `pauseGoal` | `goal.status ∉ {active,verifying,notSatisfied}` | `noGoalToPause` (null) / `goalNotActive` |
| `resumeGoal` | `goal.status !== "paused"` | `noGoalToResume` (null) / `goalNotPaused` |

**Important:** these `reasonCode`s are **unprefixed guard ids** (e.g. `noGoalToPause`), unlike command-rejection reasonCodes which are prefixed (`guard.*`, `proto.*`, `fault.*`). Verified live in `snapshot.json`: `"pauseGoal":{"allowed":false,"reasonCode":"noGoalToPause"}`. `availability` is **advisory/derived** — it mirrors `packages/formal-proof/src/model.ts` but does not gate the command wire; enforcement is separate in the gateway.

### 2c. `inputRouting`

`inputRoutingSchema` (`snapshot.ts:163-170`); computed by `computeInputRouting` (`projection-state.ts:157-183`):

| mode | when | reasonCode |
|---|---|---|
| `enqueue` | compacting | `compactingAcceptsFutureInput` |
| `enqueue` | goal verifying | `goalVerifierAcceptsFutureInput` |
| `guide`/`enqueue` | phase running/prewarming | — (guide iff `followupMode === "guide"`) |
| `choice` | phase completed* + `queue.items.length > 0` + `!queue.autoDrain` | `heldQueueInputRequiresChoice` |
| `startNow` | otherwise | — |
| `reject` | (declared; `reasonCode` required when set) | — |

`choice` requires the client to send `sendText.heldQueueDisposition` = `"clearQueueAndSend" | "keepQueueAndSend"` plus optional `expectedHeldQueueItemIds`.

### 2d. `config` = `sessionConfigStateSchema` (`session-config.ts:5-28`)

| field | type | notes |
|---|---|---|
| `modelSelection` | `{providerId, modelId, options?:{reasoningLevel?}}`? | the **sparse intent** the session accepts and persists |
| `provider` | string | UI-effective projection (may be `""` on a fresh draft) |
| `model` | string | UI-effective projection |
| `thought` | string | UI-effective reasoning level (may be `""`) |
| `thoughtLevels` | string[] default `[]` | **model capability**, not a preference; default only for old snapshots |
| `followupMode` | `"queue"｜"guide"` | |
| `mode` | string default `"build"` | `z.string()`, **not** an enum — can hold provider-native values; command enum only accepts `build｜edit｜plan｜yolo` |
| `planEnabled` | boolean? | absent = unknown/false |
| `permissionGrant` | `{interactionId}`? | explicit approval; consumed once per interactionId |
| `planTransition` | `{toolCallId, planEnabled}`? | correlation for tool-driven plan transitions |

Live example (`snapshot.json`): `{"provider":"new-provider-7","model":"cn:deepseek-v4.1-flash","thought":"max","thoughtLevels":["disabled","low","high","max"],"followupMode":"queue","mode":"yolo","modelSelection":{"providerId":"new-provider-7","modelId":"cn:deepseek-v4.1-flash","options":{"reasoningLevel":"max"}},"planEnabled":false}`.

### 2e. `queue`

`queueStateSchema` (`snapshot.ts:217-224`): `{ items: QueueItem[], autoDrain: boolean, pauseReason?: "stopped"｜"manual"｜"error" }`.
`QueueItem` = `conversationInputIntentSchema` + `dispatch.state ∈ {queued,reserved,promoting}` + `toolDisallowlist?` (`:209-215`). `conversationInputIntentSchema` (`input-intent.ts:39-67`): `{sourceCommandId, queueItemId, clientId, kind:"sendText"｜"sendGoalCommand"｜"compact", text, attachments[], modelSelection?, mode?, planEnabled?, sharedContextRefs?[≤1], delivery:{requested,admitted,fallbackReasonCode?}, order:{admissionSeq,queuePosition?}, steer:{state,reasonCode?}, dispatch:{state,reservationId?}, admittedAt, provenance?}`.
Queue is **not persisted** — lost on CLI process death; client must reconcile and let the user resend.

### 2f. Legacy `session/read`

`session/read` → `ZCodeSessionStateSnapshot` (`zcode-protocol/index.ts:1016-1035`): `{ protocol:{name,version}, session: ZCodeSessionInfo, settings: ZCodeSessionSettingsState, projection, runtime, messages, goalStats?, todos?, todoGroups?, slashCommands? }`.
`settings` (`:890-921`): `{ model:{current?, available:[ZCodeModelOption], lastUsed?}, thoughtLevel:{enabled, current?, defaultLevel?, available:[{value,label,description?}]}, mode:{current}, permission?:{mode?, rulesRevision?} }`.
`ZCodeModelOption` (`:797-810`): `{ ref: ModelSelection, label, providerLabel?, description?, contextWindow?, maxOutputTokens?, reasoning?:{levels:[{value,label,description?}], defaultLevel?}, properties:{inputFormat,outputFormat}, disabledReason? }` — **this legacy path is where model display labels live**.

---

## 3. Create / delete / rename / fork session

All are v4 commands sent via `v4/command` with a `commandEnvelopeSchema` (`command.ts:323-337`): `{ ttft?, commandId, clientId, sessionId: string|null, baseRevision?, baseLogEpoch?, type, payload, issuedAt }`. `parseCommandEnvelope` (`:340-367`) validates payload per type and enforces CAS keys. ACK = `commandAckSchema` (`:430-444`): `{memoryEnabled?, ttftExcluded?, commandId, status:"accepted"｜"rejected"｜"stale"｜"duplicate"｜"noop"｜"failed", reasonCode?, message?, revisionAtDecision, result?}`.

| operation | command | payload | notes |
|---|---|---|---|
| create | `createSession` | `{ workspaceId, firstInput?:{text, attachments?, modelSelection?, mode?, planEnabled?}, config?:{modelSelection?,provider?,model?,thought?,followupMode?,mode?,planEnabled?}, mcpServers?, offPeakToolEnabled?, dynamicWorkflowEnabled? }` (`:46-67`, config schema `:30-42`) | `sessionId` in envelope **must be null**; `config` is applied before `firstInput`; ACK.result `{type:"createSession", sessionId, input?:{delivery,inputId,messageId?}}` |
| create side pane | `createSelectionSideSession` | `{ firstInput?:{text(trim,min1), modelSelection?} }` (`:69-79`) | parent = `envelope.sessionId`; inherits parent runtime config |
| rename | `renameSession` | `{ title: string }` (`:244`) | sets `titleSource="custom"` (sticky); core event `SessionTitleUpdated` |
| delete | `deleteSession` | `{}` (`:245`) | **semantics = close**, NOT record deletion; messages remain in the DB (`session-mgmt.ts:154-171`) |
| fork | `forkAssistant` | `{ target: {rowId:int≥0, entityId} }` (`:152`) | requires `baseRevision` **and** `baseLogEpoch`; ACK.result `{type:"forkAssistant", sessionId: childId}` |
| discard shared ctx | `discardSharedContext` | `{ contextId }` (`.strict()`) (`:246`) | |
| compact | `compact` | `{}` (`:150`) | exempt from CAS |
| goal | `sendGoalCommand` | `{ text, displayText?, modelSelection?, mode?, planEnabled?, heldQueueDisposition?, expectedHeldQueueItemIds? }` (`:135-143`) | |
| close (legacy) | `session/close` (`zcode-protocol/index.ts:3581`) | `zcodeSessionCloseParamsSchema` (`:1985`) | |

Handlers: `session-mgmt.ts:35-129` (create), `:140-152` (rename), `:160-171` (delete); `fork-edit-retry.ts:280-329` (forkAssistant).
Legacy: `session/create` params `zcodeSessionCreateParamsSchema` (`:1559-1581`) with `{sessionId?, workspace, parentSessionId?, mode?, model?, persistence?, thoughtLevel?, titleGenerationEnabled?, mcpServers?, toolAllowlist?, toolDenylist?, importedHistory?, offPeakToolEnabled?, dynamicWorkflowEnabled?}`; `session/resume` (`:1583-1599`); `session/fork` (`:3588`).

---

## 4. Model catalog

### 4a. Entry point

**Channel `model-selection`, method `getView`.** Descriptor `IModelSelectionService` (`services/src/model-provider/providerFacadeServices.ts:95-107`), impl `createModelSelectionService` (`:212-264`), facade `ModelSelectionFacade.getView` (`packages/provider/src/facades.ts:519-563`).

```
IModelSelectionService.getView(input?: { selection: ModelSelection | null }) → Promise<ModelSelectionView>
```

* `ModelSelectionViewInput` (`facades.ts:197-199`) = `{ selection: ModelSelection | null }` — required when `input` is passed; passing it turns on `effectiveSelection`/`selectionIssue` resolution.
* Without input, the view still carries `preferredSelection` (registry initial selection), but **no** `effectiveSelection`/`selectionIssue`.
* Providers with `config.visibility === "hidden"` are filtered out (`facades.ts:549`).
* `revision` is monotonic; `onDidChange` fires with a fresh view on registry change.

### 4b. Full result shape (`ModelSelectionView`, `facades.ts:183-205`)

```
{
  revision: number,
  providers: ModelSelectionProviderView[],
  preferredSelection?: ModelSelection,            // absent when initial.source === "none"
  effectiveSelection?: ModelSelection | null,      // only when input.selection provided
  selectionIssue?: "selection-missing" | "account-connection-unavailable" | "provider-not-found"
                 | "model-not-found" | "reasoning-level-missing" | "reasoning-level-not-supported"
}
```

`ModelSelectionProviderView` (`:188-195`, built by `projectModelSelectionProviderView` `:571-588`):

```
{ providerId, providerName, templateId,
  config: RegistryProviderConfigObject,   // serializeRegistryProviderConfig, resolver.ts:39-68
  models: [ { modelId, config: RegistryModelConfigObject } ] }   // serializeRegistryModelConfig, resolver.ts:76-108
```

`RegistryProviderConfigObject` = complete `providerConfigDataSchema` (`provider-data-schema.ts:83-99`):

```
{ group: "standard-personal"|"zai-family"|"bigmodel-family",
  logo?: {type:"builtin", key},
  access: { type:"api-key"|"zhipu-coding-plan-api-key", apiKey: string, apiKeyManagementUrl? }
        | { type:"zhipu-account", accountType:"zai"|"bigmodel",
            mode:"start-plan"|"individual-coding-plan"|"team-coding-plan"|"off-peak", entitled:boolean },
  api: { type:"anthropic-messages"|"openai-chat-completions"|"openai-responses",
         baseUrl: string, headers?: Record<string,string> },
  builtinModelIds?: string[], personalModelIds?: string[], modelOrder?: string[],
  visibility?: "visible"|"hidden" }
```

`RegistryModelConfigObject` = complete `modelConfigDataSchema` (`packages/shared/src/model-config.ts:95-110`):

```
{ enabled: boolean,
  properties: { requiresMfjsToolSchema: boolean, contextWindow: number(int>0),
                inputFormat: {supportsText,supportsImage,supportsVideo,supportsAudio,supportsPdf: boolean},
                outputFormat: {supportsText: boolean},
                supportsToolCall: boolean, supportsJsonSchemaOutput: boolean,
                supportsNativeWebSearch: boolean, supportsMidConversationSystem: boolean },
  optionSpecs: { reasoningLevel: { values: string[] (non-empty, unique, low→high), map: string },
                 maxOutputTokens: { max: int>0, map: string } } }
```

**Thought/reasoning levels:** `models[].config.optionSpecs.reasoningLevel.values` (ordered low→high; first entry is the lowest public level). There is **no `defaultLevel`** in the registry projection — defaults come from the model rules / CLI runtime.
**Context window:** `models[].config.properties.contextWindow`.
**Capabilities:** `properties.inputFormat` / `outputFormat` / `supports*`.
**No display names, no `label`, no `modelName`, no top-level `thoughtLevels`** — see gotcha §8.9.

### 4c. ⚠ PLAINTEXT API KEYS — must be projected/filtered by the client

The raw `getView` payload **embeds provider API keys in plaintext**:

* `providers[].config.access.apiKey` — for every provider whose `access.type ∈ {"api-key","zhipu-coding-plan-api-key"}` (`resolver.ts:44-52` copies `config.access.apiKey` verbatim; schema `provider-data-schema.ts:30-36` `apiKey: z.string().nullable().optional()`).
* `providers[].config.api.headers` — an arbitrary `Record<string,string>` that commonly carries `Authorization` / `x-api-key` (`provider-data-schema.ts:68`).
* `providers[].config.access.apiKeyManagementUrl` — not a secret, but provider-identifying.

`provider-settings.getView()` exposes the same secret at **three** more paths (see §4d).
A client MUST whitelist-project `{providerId, providerName, modelId, config.properties.contextWindow, config.optionSpecs.reasoningLevel.values, enabled}` and never store/log/echo the response. The mobile `ModelCatalogService` (`protocol/.../ModelCatalog.kt:24-32`) documents exactly this discipline.

### 4d. Provider info / provider settings catalog

**Channel `provider-settings`, method `getView`** → `ProviderSettingsView` (`facades.ts:171-176`):

```
{ revision, providerTemplates:[{templateId, templateNameMap:{zh-CN?,en-US?}, config}],
  providerOrder: ProviderId[],
  providers: [ ProviderSettingsProviderView ] }
```

`ProviderSettingsProviderView` (`:149-163`, built `:617-663`): `{ providerName, templateId, enabled, accountState?, providerId, executable, effectiveBuiltinConfig?, personalConfig?, effectiveConfig, issues: ConfigValidationIssue[], models: ProviderSettingsModelView[] }`.
`ProviderSettingsModelView` (`:106-120`): `{ kind:"candidate", modelId, builtin, effectiveBuiltinConfig, personalExactConfig?, useRecommendedConfig?, effectiveConfig, enabled, executable, selectable, issues }`.
⚠ secrets at `providers[].effectiveConfig.access.apiKey`, `providers[].personalConfig.access.apiKey`, `providers[].effectiveBuiltinConfig.access.apiKey`, and each `.api.headers`.

Provider mutations on the same channel (`providerFacadeServices.ts:30-71`): `refresh(reason)`, `createPersonalProvider(input?)`, `resolveModelConfig({providerId,modelId}|{providerId,originalModelId,modelId,personalConfig})`, `savePersonalProviderOverlay(providerId, config, metadata?)`, `deletePersonalProvider(providerId)`, `reorderPersonalProviders(providerIds)`, `reorderPersonalModels(providerId, modelIds)`, `addPersonalModel(providerId, modelId, config, useRecommendedConfig?)`, `renamePersonalModel(providerId, currentModelId, nextModelId)`, `deletePersonalModel(providerId, modelId)`, `savePersonalModelDraft({providerId, originalModelId, nextModelId, personalConfig, useRecommendedConfig?, basedOnRevision})`, `setPersonalModelEnabled(providerId, modelId, enabled)`, `testModelConnectivity({workspacePath, workspaceIdentity?, providerId, modelId})`.

Credentials live in the separate `credential` channel: `load(key)`, `save(key, value)`, `delete(key)` (`credential.ts:11-15`). Remote environments receive keys via `provider-provisioning-target.apply(ProviderProvisioningEnvelope)` (`shared/provider-provisioning.ts:66-98`): `{schemaVersion:1, syncId, personalConfig, accountSettings, credentials:[{scope:"oauth-session"|"account-provider", key, value}][≤256]}`; only `account-provider:<id>:api-key` keys are allowed (`isProviderProvisioningAccountCredentialKey`, `:26-30`).

### 4e. Legacy catalog

`ZCodeSessionStateSnapshot.settings.model.available: ZCodeModelOption[]` (§2f) is the labelled catalog. The derived `ZCodeConfigOption` list (`zcode-agent-model-state.ts:57-119`) builds `{id:"model", category:"model", type:"select", currentValue: "providerId/modelId$reasoningLevel", options:[{value, name, description?, modelProviderId?, modelProviderName?, modelThoughtLevels?, modelDefaultThoughtLevel?}]}` plus `id:"mode"` and (if enabled) `id:"thought_level"`.
Picker string format: `providerId/modelId[$reasoningLevel]` — `ZCODE_MODEL_REASONING_SEPARATOR = "$"`; `formatModelPickerValue` / `parseModelPickerValue` (`model-selection.ts:31-60`).

### 4f. Workspace config topic does **not** carry models

`workspace-config/<workspaceId>` (`workspace-config.ts:72-81`) publishes `{ configOptions, slashCommands }` (`workspaceConfigStateSchema`, `:50-54`). The v4 publisher only emits **one** option: `{id:"mode", name:"Mode", category:"mode", type:"select", currentValue, options: getZCodeAgentModeSelectOptions()}` (`apps/zcode-cli/packages/bootstrap/src/zcode-protocol/v4-workspace-config.ts:11-33`). The schema still allows model option fields (`modelProviderId`, `modelThoughtLevels`, `modelDefaultThoughtLevel` — `workspace-config.ts:20-24`) for legacy passthrough, but the current publisher does not emit them. Model catalog = `model-selection` channel only.

---

## 5. Provider info / current mode — read & set

### Read

* Active provider/model/thought per session: `snapshot.config` (`provider`, `model`, `thought`, `modelSelection`, `thoughtLevels`).
* Workspace-level default: `workspace-config` topic `configOptions[id="mode"].currentValue` (workspace default mode).
* Provider identity/state: `model-selection.getView().providers[]` (`providerId`, `providerName`, `templateId`, `config.group`, `config.access.type`, `config.visibility`) and `provider-settings.getView().providers[].accountState`.
* Legacy: `session/read` → `settings.model.current`, `settings.mode.current`; `settings.model.lastUsed`.

### Set

| what | command | payload | CAS |
|---|---|---|---|
| model + thought | `switchModelConfig` | `{ provider: string, model: string, thought: string }` — **all three required**; `thought` may be `""` (`command.ts:208-212`) | requires `baseRevision` (`COMMAND_REQUIRING_BASE_REVISION`) |
| collaboration mode | `switchCollaborationMode` | `{ mode: "build"｜"edit"｜"plan"｜"yolo" }` (`:215-217`) | requires `baseRevision` |
| followup mode | `setFollowupMode` | `{ mode: "queue"｜"guide" }` (`:218`) | requires `baseRevision` |
| mode/plan/model at create | `createSession.config` | see §3 | n/a |

`switchCollaborationMode` handler (`model-config.ts:153-166`): noop ACK (`reasonCode:"config.unchanged"`) if same mode **and** `!planEnabled`; otherwise `app.setMode(mode)` emits `SessionModeChanged` → projection updates `config.mode`.
`switchModelConfig` handler (`model-config.ts:76-142`): resolves provider in the Environment Registry (`provider.notInRegistry` → failed ACK), calls `setModel`, then pins `thought` only if supported; emits `ModelSelected`. Same-value → noop `config.unchanged`.
Legacy equivalents: `session/setModel`, `session/setThoughtLevel`, `session/setMode` (`zcode-protocol/index.ts:3585-3589`); service-layer `zcode-agent.setModel/setThoughtLevel/setMode` (`zcodeAgent.ts:704-706`, params `:290-305`) and `zcode-session.setModel/setThoughtLevel/setMode` (`zcodeSession.ts:152-154`).
`ZCodeSessionMode` (`zcode-protocol-legacy-types.ts:74`) = `"plan"|"build"|"edit"|"yolo"|"auto"`; `submissionModeSchema` (`submission.ts:4`) = `"build"|"edit"|"plan"|"yolo"` (`auto` is runtime-internal, never user-submitted). `ZCodeTaskMode` (`zcode-task-mode-schema.ts:6`) = `"yolo"|"plan"|"edit"|"auto"|"autoEdit"|"build"`.

---

## 6. All settable items

### 6a. Session-scoped

| item | where it lives in state | how to set |
|---|---|---|
| `mode` | `snapshot.config.mode` | `switchCollaborationMode {mode}` (baseRevision) / `sendText.mode` / `createSession.config.mode` |
| `planEnabled` | `snapshot.config.planEnabled` | `sendText.planEnabled`, `sendGoalCommand.planEnabled`, `createSession.config.planEnabled` (only these; no dedicated command) |
| `followupMode` | `snapshot.config.followupMode` | `setFollowupMode {mode}` (baseRevision) |
| model selection | `snapshot.config.modelSelection` | `switchModelConfig {provider,model,thought}` |
| thought level | `snapshot.config.thought` / `thoughtLevels` | `switchModelConfig.thought` |
| queue autoDrain | `snapshot.queue.autoDrain` | `setAutoDrain {autoDrain:boolean}` (baseRevision) |
| queue items | `snapshot.queue.items` | `sendQueuedNow {queueItemId}`, `editQueueItem {queueItemId,newText}`, `reorderQueueItem {queueItemId,beforeQueueItemId|null}`, `deleteQueueItem {queueItemId}` |
| title | `snapshot.meta.title` | `renameSession {title}` |
| shared context import | `snapshot.sharedContextImport` | created by `sendText.context_refs[≤1]`; removed by `discardSharedContext {contextId}` |
| goal | `snapshot.goal` | `sendGoalCommand`, `pauseGoal {}`, `resumeGoal {}` |
| background work | `snapshot.backgroundWorks` | `cancelBackgroundWork {workId}` |
| pending interactions | `snapshot.pendingInteractions` | `resolveInteraction`, `snoozeInteractionAutoResolution {interactionId}` |
| hook review | `snapshot.workspaceHookAdmission` | `respondWorkspaceHookReview`, `toggleWorkspaceHookReviewItem`, `revokeWorkspaceHookTrust`, `requestWorkspaceHookReview` |
| MCP servers | session record (create-time only) | `createSession.mcpServers` / `session/create` / `session/resume`; runtime-visible via `mcp/list` |
| off-peak tool flag | session record | `createSession.offPeakToolEnabled` (host-decided; `resume` must repeat it or cold resume loses the tool face) |
| dynamic workflow flag | session record | `createSession.dynamicWorkflowEnabled` (host-decided) |
| model transition notice | `snapshot.modelTransition` | read-only (registry fallback) |

### 6b. Workspace-scoped

| item | where | how |
|---|---|---|
| workspace default mode | `workspace-config` `configOptions[id="mode"].currentValue` | read-only topic; mode is set per session |
| slash commands | `workspace-config.slashCommands` | read-only catalog (`{name,description,inputHint?,source?:"builtin"|"custom"}`) |
| workspace hooks | `hooks` channel | `loadHooks({workspaceIdentity?, workspacePath}) → {hooks, hooksEnabled, workspaceHookSnapshot?, trustStoreCorrupt?}`; `saveHooks({workspaceIdentity?, workspacePath, hooks})`; `grantWorkspaceHookTrust({workspaceIdentity?, workspacePath, bundleDigest, hookDeclarationDigest})` (`hooks.ts:6-37`) |
| MCP (workspace/user dir) | `mcp-sync` channel | `loadMcpFromUserDirectory`, `saveMcpToUserDirectory`, `listWorkspaceMcpServerStatuses({workspacePath, workspaceIdentity?, mcpServers?, mode?})`, `listLocalUserMcpCandidates`, `listRemoteUserMcpStatuses`, `exportMcpServers`, `checkRemoteUserMcpWriteAccess`, `importMcpServers` (`mcpSync.ts:18-45`) |
| remote workspace config | `client-config.getSnapshot({forceRefresh?}) → {pluginStoreOrder}` (`clientConfig.ts:9-15`, `shared/clientConfig.ts:5-27`) | read-only |
| home scenes | `client-scenes.list()` (`clientScenes.ts:59-61`) | read-only |

### 6c. App / global settings (`setting` channel, `~/.zcode/v2/setting.json`)

`ISettingService` (`setting.ts:5-17`): `get(): Promise<AppSettings>`, `update(patch: Partial<AppSettings>, expectedAccountSettings?: Pick<AppSettings,"providerFamilyDomain"|"providerFamilyConnectionSelections">): Promise<void>`, `updateDataBaseDir(newDir|undefined)`, `ensureDefaultProject(homedir) → {path, created}`.

`AppSettings` schema `appSettingsObjectSchema` (`validationAppSettings.ts:420-475`); patch schema `appSettingsPatchSchema` (`:493-562`) — **`update` accepts exactly the patch key set**:

| key | type / default | meaning |
|---|---|---|
| `recentProjects` | string[] = `[]` | |
| `locale` | `"zh-CN"｜"en-US"` = `"zh-CN"` | |
| `localePreference` | `"system"｜"zh-CN"｜"en-US"` = `"system"` | |
| `shortcutBindings` | `Record<string,string[]>`? | keybinding overrides |
| `terminalInheritSystemProfile` | bool = `true` | |
| `terminalFontFamily` | string? | |
| `integratedTerminalShell` | `{mode:"auto"}` \| `{mode:"shell", dialect:"cmd"｜"git-bash", id, label, path}`? | |
| `httpProxy`, `httpProxyNoProxy`, `httpProxyCaCertPath` | string? | |
| `embeddedBrowserAllowInsecureCertificates` | bool = `false` | |
| `embeddedBrowserViewportPreference` | schema default | |
| `computerUseComposerEntryHidden` | bool = `true` | |
| `taskAutoArchiveEnabled` | bool = `false` | |
| `taskAutoArchiveOlderThanDays` | int 1..365 = `7` | |
| `closeToTrayOnWindows` (+`…MigrationInitialized`) | bool = `true` | |
| `keepAwakeWhileRunning` | bool = `false` | |
| `desktopZoomLevel` | int -3..5? | |
| `desktopWindowSize` | `{width≥480,height≥640,maximized}`? | |
| `desktopChromiumHardwareAccelerationEnabled` | bool = `true` | |
| `messageStreamShowReasoning` (+`…MigrationInitialized`) | bool = `true` | |
| `messageStreamShowTodos` | bool = `false` | |
| `toolGroupingExploreEnabled` / `…TerminalEnabled` / `…ChangesEnabled` | bool `true`/`true`/`false` | |
| `zcodeInteractionBehavior` | `"queue"｜"guide"` = `"queue"` | global default followup behavior |
| `askUserQuestionAutoResolutionEnabled` | bool = `true` | AskUserQuestion auto-resolve countdown |
| `modelIoFullRetentionEnabled` | bool = `false` | keep full model I/O |
| `startPlanRecommendationDismissed` | bool = `false` | |
| `providerFamilyConnectionSelections` | schema = `{}` | |
| `providerFamilyDomain` | `"zai"｜"bigmodel"｜""`? (+`…UpdatedAt`, `…Migrated`) | |
| `nativeSearchEnhancementsEnabled` | bool = `true` | |
| `onboardingOccupation` | enum \| null | |
| `proactiveSuggestionsEnabled` | bool? | |
| `memoryEnabled` | bool = `false` | App Memory master switch |
| `lastWorkspaceSession` | `AppWorkspaceSessionEntry[]` = `[]` | tab/session restore list |
| `lastActiveTabIndex` | int ≥0 = `0` | |
| `lastActiveTaskByWorkspace` | `Record<string,string>`? | |
| `dataBaseDir` | string? | use `updateDataBaseDir` instead |
| `pendingPostUpdateReleaseNotes` | `{version,title,markdown,releaseDate?,releaseNotesByLocale?}`? | |
| `receivePreviewUpdates` | bool = `false` | |
| `autoDownloadAndInstallUpdates` | bool = `false` | |
| `skippedElectronUpdateVersions` | `partialRecord<"stable"｜"preview", string>` = `{}` | |
| `settingsSyncFirstRunPromptHandled` | bool? | |
| `zcodeEndpointOrigin` | normalized origin string? | dev endpoint override |

Runtime-only preferences pushed to live agents: `zcode-agent.syncAppRuntimePreferences({askUserQuestionAutoResolutionEnabled, modelIoFullRetentionEnabled?})` (`zcodeAgent.ts:551-554`). Desktop main also broadcasts `settings:app-runtime-preferences` (`shared/app-runtime-preferences.ts:3-11`).

Settings import/migration: `settings-sync` channel — `detect`, `importSelected`, `getClaudeAgentsFileMigrationStatus`, `copyClaudeAgentsFileToZcodeAgentsFile`, `getFirstRunPromptState`, `markFirstRunPromptHandled` (`settingsSync.ts:13-37`).

### 6d. CUA permission

`cua-permission` channel (`ICuaPermissionService`, `zcode-cua/broker.d.ts:139-150`): `getStatus(workspacePath, workspaceIdentity?, options?: {probeScreenCapture?}) → CuaPermissionStatusResult`, `restartHelper(workspacePath?, workspaceIdentity?, options?) → CuaPermissionRestartResult`.
Status schema `cuaRequestAccessStatusSchema` (`cuaPermission.ts:5-14`): `{schemaVersion:1, platform:"darwin", grantOwner, accessibility:"granted"|"stale"|"denied", screenRecording:"granted"|"denied"|"unknown"}` — **darwin-only**.
Live observations stream as `v4/cua/permission-observation` notifications (`cuaPermissionObservationSchema`, `:29-41`). Platform channels (desktop main): `zcode:open-cua-permission-onboarding`, `zcode:prepare-cua-helper-permission-drag`, `zcode:start-cua-helper-permission-drag`, `zcode:cancel-cua-permission-onboarding` (`channels.ts:288-304`).

### 6e. Telemetry

* **Not user-settable.** Master switch is a build constant `ZCODE_TELEMETRY_ENABLED` plus `ZCODE_TELEMETRY_REPORT_ENDPOINT`; when either is falsy the event is dropped (`services/src/telemetry/telemetryCore.ts:367-370`).
* Client→server upload: v4 notification `v4/telemetry/event` (`V4_NOTIFICATIONS.conversationTelemetryFact`) carrying `ConversationTelemetryFact` (`telemetry.ts:263-323`) — 10 strict kinds: `turn.started`, `model.request.status`, `stream.chunk`, `tool.lifecycle`, `permission.lifecycle`, `usage.delta`, `subagent.lifecycle`, `workflow.lifecycle`, `turn.terminal`, `compaction.terminal`. All branches `.strict()` to prevent prompt/tool-input/URL leakage.
* Related settings: `modelIoFullRetentionEnabled` (§6c), `feedbackPrivacy` redaction (`shared/feedbackPrivacy.ts`), renderer action trace config (read-only, platform channel `zcode:get-renderer-action-trace-config`).
* Desktop renderer reporting: platform channels `zcode:report-telemetry-event`, `zcode:report-arms-custom-event`, `zcode:sync-telemetry-context` (`channels.ts:317-331`).

### 6f. Feature flags (host-decided, not client settings)

* Off-Peak tool face: `createSession.offPeakToolEnabled` / `session/create|resume.offPeakToolEnabled`; host gate via `resolveOffPeakClientConfig`; sync message `workspace/updateOffPeakToolPolicy`.
* Dynamic workflow: `DYNAMIC_WORKFLOW_MODES = ["disabled","onDemand","alwaysOn"]`, default `"disabled"` (fail-closed), env override `ZCODE_DYNAMIC_WORKFLOW_MODE` (`dynamic-workflow-feature.ts:9-76`); `createSession.dynamicWorkflowEnabled`; sync message `workspace/updateDynamicWorkflowPolicy`.
* Provider family: `providerFamilyDomain` / `providerFamilyConnectionSelections` in AppSettings + `workspace/updateAccountConfig`.

---

## 7. Permissions / approvals

### 7a. Reading prompts

`snapshot.pendingInteractions: PendingInteraction[]` (`snapshot.ts:310-350`): `{ interactionId, kind:"permission"|"userInput"|"workspaceHookReview", anchorRowId: number|null (null = session-level), createdAt, autoResolution?, payload }`. `payload` is a discriminated union on `kind`:

* `permissionRequestPayloadSchema` (`:237-258`): `{kind:"permission", toolCallId, toolName, summary, detail, freeText?, origin?, display?, fullAccessOption?, options:[{optionId, label, kind:"allowOnce"|"allowAlways"|"deny"|"custom", response?}]}`.
  * `PERMISSION_FULL_ACCESS_OPTION_ID = "fullAccess"` (`:229`).
* `userInputRequestPayloadSchema` (`:276-293`): `{kind:"userInput", prompt, freeText, options?:[{optionId,label}], sensitive?, toolName?, toolCallId?, traceId?, input?, schema?, questions?:[{question,header,options,multiSelect?}], currentQuestionIndex?, answerDrafts?, origin?}`.
* `workspaceHookReviewRequestPayloadSchema` (`workspace-hook-review.ts:79-181`) — immutable payload, must match `interactionId`.

`autoResolution` (`interactionAutoResolutionSchema`, `:295-308`): `{state:"hiddenGrace"|"visibleCountdown", startedAt, visibleAt, deadlineAt}` | `{state:"snoozed", startedAt, snoozedAt}`.

### 7b. Answering

Command `resolveInteraction` (no CAS required):

```
{ interactionId: string,
  answer: { optionId?: string, freeText?: string,
            action?: "accept"|"decline"|"cancel",
            content?: Record<string, unknown> } }
```

(`command.ts:176-188`). ACK.result `{type:"resolveInteraction", resolvedBy:{clientId, optionId?}}` (`:383-389`).

Handler (`interaction-background.ts:45-63`):
* `answer.optionId === "fullAccess"` routes to `host.interactions.resolveFullAccess(interactionId, sessionId)`; otherwise `resolve(interactionId, answer)`.
* **Late/duplicate answers are idempotent `accepted`** (no `requireRecord`), never an error.
* `snoozeInteractionAutoResolution {interactionId}` (`command.ts:205-207`) permanently suspends this interaction's auto-resolution; repeat calls are idempotent noop.

`kind:"workspaceHookReview"` interactions are answered with dedicated commands (`respondWorkspaceHookReview`, `toggleWorkspaceHookReviewItem`, `revokeWorkspaceHookTrust`, `requestWorkspaceHookReview`) — see §6a; handler `interaction-background.ts:88-150`; the payload's `sessionId` must equal `envelope.sessionId` else `workspace_hooks_snapshot_mismatch`.

Legacy reverse requests: `interaction/requestPermission`, `interaction/requestUserInput` (`zcode-protocol/index.ts:3675-3676`).

---

## 8. Gotchas

1. **`mode` default silently clobbers workspace yolo.** `sessionConfigStateSchema.mode` has `z.string().default("build")` (`session-config.ts:17`). `createSession.config` therefore uses a **separate** `createSessionRequestedConfigSchema` (`command.ts:30-42`) where `mode` is optional, precisely so "mode not sent" cannot become "switch back to build" and override the workspace default yolo (`command.ts:36-40`). Never reuse `sessionConfigStateSchema.partial()` for a request.
2. **`availability.reasonCode` values are unprefixed** (`noGoalToPause`, `idleCannotCompact`, `compactOperationLock`, `sendQueuedNowRequiresRunning`, `goalNotActive`, `goalNotPaused`, `noGoalToResume`, `forkTargetNotStable`) — unlike command-rejection reasonCodes which are `guard.*` / `proto.*` / `fault.*` (`product-projection.ts:297`). Match on the raw id for availability.
3. **`availability` is advisory, not enforced.** `switchModelConfig`/`setFollowupMode`/`queueEdit` are always `{allowed:true}`; the gateway independently rejects bad commands. UI must not treat `allowed:true` as a guarantee, nor `allowed:false` as the only error source.
4. **`sessionEnded:true` ≠ deleted.** It is derived (`phase ∈ completed*`) and turns true the moment a successful turn settles (`sessions-index-projection.ts:83`). Deletion is a separate concept; `deleteSession` only closes the runtime (`session-mgmt.ts:154-171`) — the record stays in the DB.
5. **`phase:"draft"` is memory-only**, invisible to disk, and disappears on CLI restart. A fresh draft's `config.provider/model/thought` may be `""`.
6. **`titleSource:"custom"` is sticky.** The index projection refuses to downgrade a `custom` title to `default`/`generated`, and also refuses to regress `phase` from non-draft back to `draft` (`sessions-index-projection.ts:152-188`). A stored summary can hold a stale-but-better value while live hydration catches up.
7. **CAS is not uniform.** `switchModelConfig`, `switchCollaborationMode`, `setFollowupMode`, `pauseGoal`, `resumeGoal`, `setAutoDrain`, all queue edits, `applyFileRewind`, `forkAssistant`, `editUserQuery`, `retryTurn`, `setAssistantFeedback` require `baseRevision`; of those, the 5 row-targeting ones additionally require `baseLogEpoch` (`command.ts:296-320`). `createSession`, `sendText`, `compact`, `renameSession`, `deleteSession`, `resolveInteraction`, `cancelBackgroundWork`, workflow commands do **not**.
8. **Key-presence vs truthiness traps:**
   * `config.planEnabled` absent ⇒ unknown, not `false`. Check presence before rendering a toggle.
   * `config.mode` is a free string; `"auto"`/provider-native values can appear in state even though the command enum forbids them.
   * `titleSource` is `optional` in `SessionSummary` for old frames — absence is not `"default"`.
   * `sessionConfig.thoughtLevels` default `[]` means "unknown/old snapshot", not "model has no levels".
   * `config.followupMode` default is `queue`; explicitly sending `"queue"` in `createSession.config` is skipped by the handler because `runtime.setFollowupMode` has no same-value guard and would emit an empty delta (`model-config.ts:260-264`).
9. **Model display names are NOT in `model-selection.getView`.** Entries are `{modelId, config}` only — no `label`, no `modelName`, no top-level `thoughtLevels`. `ModelCatalog.kt` reading `modelName`/`name`/`thoughtLevels` yields null/empty; derive names client-side or use the legacy `settings.model.available[].label` (§2f). Thought levels are at `models[].config.optionSpecs.reasoningLevel.values`.
10. **`getView` result shape depends on whether `input` was passed.** With `input = {selection}` you get `effectiveSelection` + `selectionIssue`; without it you get only `preferredSelection` and the base provider list.
11. **`provider-settings.getView`/`model-selection.getView` leak secrets** at `access.apiKey` and `api.headers` (multiple paths). Whitelist-project; never persist/log/echo (see §4c).
12. **`workspace-config` is workspace-level, `conversation.config` is session-level.** `workspaceConfigOption.currentValue` is the workspace default; the session's effective mode lives in the conversation snapshot. Do not treat them as the same value.
13. **`workspace-config` currently publishes only the `mode` option** (plus slash commands) — model/thought options are absent in the v4 publisher despite the schema allowing them.
14. **Same-value mode/model switches return `noop` ACK with `reasonCode:"config.unchanged"`** (`model-config.ts:18,103-105,161-163`), not `accepted`. Treat `noop` as success-with-no-change.
15. **`sessions-index` delta conflation is not a field diff.** Same `sessionId` overwrites the whole summary; `summariesEqual` suppresses no-op deltas but a changed `lastActivityAt` alone still emits a frame.
16. **Queue is volatile.** `queue.items` are not persisted; on CLI process death they vanish and the client must reconcile.
17. **`sendText` `heldQueueDisposition` is mandatory when `inputRouting.mode === "choice"`** (`heldQueueInputRequiresChoice`), and `expectedHeldQueueItemIds` guards against concurrent queue mutation.
18. **`workflowActivity` key is entirely absent when a session has no workflow runs** (not an empty array) — same for `pendingInteraction`/`pendingInteractionSummary` (absent when counts are 0) and `goalStatus` (absent when no goal).
19. **`sessions-index` and `workspace-config` subscribe ACKs are ACK-only**; the initial snapshot arrives as a *separate* `v4/conversation/frame` notification that may race ahead of the ACK response. Stage/queue frames until the ACK assigns `subscriptionId` (see `windowHostSessionsIndexObserver.ts:150-172`), or you will drop the snapshot.
20. **`subscriberScope`** must differ per in-process consumer of the same topic, else one consumer's subscribe replaces another's generation.
21. **`deleteSession` requires the host `closeSession` capability**; a missing hook makes the command fail (ACK `failed`) rather than silently no-op (`session-mgmt.ts:165-168`).
22. **`forkAssistant` is conversation-only** — it does not stop the parent, does not rewind workspace files, and only accepts the last assistant segment of a turn (`fork-edit-retry.ts:280-329`).
23. **`resolveInteraction` late answers are idempotent success**, so a client that races another device will not get an error; do not treat "no pending interaction" as failure.
24. **`workspaceHookReview` interactions cannot use AskUserQuestion auto-resolution** (`snapshot.ts:332-338`) and are excluded from the sidebar pending badge (`sessions-index-projection.ts:50-53`).
25. **Telemetry has no user toggle** in `AppSettings`; it is a build-time constant. `modelIoFullRetentionEnabled` is the only related user setting.
