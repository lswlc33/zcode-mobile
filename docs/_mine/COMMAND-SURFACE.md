# ZCode Protocol v4 — Conversation Command Surface

Ground truth: `E:/open_trae_m/ZCode_full` (version 3.14.3, Apache-2.0).
Protocol package: `packages/shared/src/zcode-protocol-v4/`.
CLI-native command handlers: `apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/`.

All identifiers quoted verbatim. Chinese source comments translated/paraphrased where load-bearing.

**Command count: 34** (keys of `commandPayloadSchemas`, `packages/shared/src/zcode-protocol-v4/command.ts:44-247`).

RPC entry points (`V4_METHODS`, `transport.ts:332-384`):

| wire method | purpose |
|---|---|
| `v4/command` | send one command envelope → one `CommandAck` |
| `v4/commands/query` | batch idempotency lookup of up to 64 command keys |
| `v4/conversation/*` | read-only queries (see §4) |
| `v4/attachment/*` | upload/download transactions (see §5) |

---

## 1. Command envelope

Schema: `commandEnvelopeSchema`, `command.ts:323-337`. Validated together with payload by `parseCommandEnvelope`, `command.ts:340-367`.

| field | type | req? | meaning |
|---|---|---|---|
| `ttft` | `localTtftContextSchema` | optional | client-side TTFT context; capacity-rejected commands get `ttftExcluded: "capacity"` on the ACK (`v4-gateway.ts:2378-2403`) |
| `commandId` | `string` | **required** | uuid v7, client-generated; **unchanged across retries**. This is the **idempotency key**. CLI also uses it as `inputId` for input commands (`session-flow.ts:268`, `command-inbox.ts:182`). |
| `clientId` | `string` | **required** | stable per client instance (renderer persists in `localStorage` `zcode-v4-client-id:v1`, `commandFactory.ts:14`; host process uses `host-services-<uuidv7>`, `zcodeV4HostCommand.ts:36`). Used to distinguish submitters in the idempotency table / `pendingCommands` display. |
| `sessionId` | `string \| null` | **required** (nullable) | target session. **`null` only for `createSession`**; all other session commands must carry it, else `rejected` / `proto.sessionNotFound` (`command-inbox.ts:312-323`). `null` commands go into the global idempotency bucket `"@global"` (`command-inbox.ts:73`). |
| `baseRevision` | `number` | optional (required for CAS set) | **CAS key**. Required iff `type ∈ COMMANDS_REQUIRING_BASE_REVISION` (`command.ts:296-312`); `parseCommandEnvelope` rejects the envelope with `"CAS commands require baseRevision and baseLogEpoch"` if missing (`command.ts:347-362`). |
| `baseLogEpoch` | `string` (trim, min 1) | optional (required for row-targeting set) | **CAS key #2**. Required iff `type ∈ ROW_TARGETING_COMMANDS` (`command.ts:314-320`). Checked **before** `baseRevision` (`command-inbox.ts:338-351`): mismatch → `stale` / `proto.staleLogEpoch`. |
| `type` | `commandTypeSchema` | **required** | enum of the 34 command names (`command.ts:257-259`). |
| `payload` | `unknown` (validated per type) | **required** | the per-command object; the envelope schema cannot statically bind it, so `parseCommandEnvelope` re-validates `commandPayloadSchemas[type]` (`command.ts:345`). |
| `issuedAt` | `timestampSchema` (= `z.number()`) | **required** | **client clock, telemetry only**; server never uses it for arbitration (`command.ts:334-335`, `core.ts:19`). |

**Idempotency / CAS keys:** `(sessionId, commandId)` = idempotency key (`commandKeySchema`, `command.ts:447-454`); `baseRevision` = conversation revision CAS; `baseLogEpoch` = projection-generation CAS (rowId only meaningful within one epoch).

### Command classification sets

`COMMANDS_REQUIRING_BASE_REVISION` (`command.ts:296-312`), 15 commands:
`applyFileRewind, forkAssistant, editUserQuery, retryTurn, setAssistantFeedback, sendQueuedNow, editQueueItem, reorderQueueItem, deleteQueueItem, setAutoDrain, switchModelConfig, switchCollaborationMode, setFollowupMode, pauseGoal, resumeGoal`.

`ROW_TARGETING_COMMANDS` (`command.ts:314-320`), 5 commands (⊂ above):
`applyFileRewind, forkAssistant, editUserQuery, retryTurn, setAssistantFeedback`.

**Commands deliberately exempt from CAS** (comments in source): `compact` (`command.ts:148-150`: admission independent of revision; idempotency edge = `sourceCommandId`), `cancelBackgroundWork`, `resumeWorkflowRun` (`command.ts:224-227`), `startSavedWorkflow` (`command.ts:229-235`), `amendWorkflowRunSettings` (`workflow-run-settings-command.ts:12-13`) — all "workflowRuns face is revision-free; a fake CAS failure would only cause harm".

---

## 2. Every command type

Source of each payload: `commandPayloadSchemas`, `command.ts:44-247`. Handler semantics cited per section. Unless stated, a handler returning `undefined` results in `accepted` (no `result`); thrown errors with a `reasonCode` become `failed` ACKs (`v4-gateway.ts:2500-2528`).

Shared referenced schemas:
- `modelSelectionSchema` (`packages/shared/src/model-selection.ts`): `{ providerId: string(min1), modelId: string(min1), options?: { reasoningLevel?: string(min1) } }`, `.strict()`.
- `submissionModeSchema` (`submission.ts:4`): `"build" | "edit" | "plan" | "yolo"`. (`auto` is runtime-internal, never user-submitted.)
- `attachmentRefSchema` (`attachment-ref.ts:4-12`): `{ ref: string, fileName: string, mime: string, bytes: number, previewRef?: string }`, `.strict()`. Carries only a committed-content reference + display metadata; content never enters the command frame.
- `conversationRowTargetSchema` (`core.ts:10-16`): `{ rowId: number(int,≥0), entityId: string(trim,min1) }`, `.strict()`. Display rowId and stable entityId must both be submitted and be validated against the same authoritative projection.
- `sharedContextRefSchema` (`shared-context-ref.ts`): `{ kind: "shared_context_import", context_id: string(trim,min1) }`, `.strict()`.

---

### 2.1 `createSession`

**Purpose:** create a session. If `firstInput` is absent → `phase=draft` empty session; if present → directly writes `turnHeader`+`userInput` rows. This is the actual creation path for the desktop's "new session".

Payload (`command.ts:46-66`):
| field | type | req? | notes |
|---|---|---|---|
| `workspaceId` | `string` | **required** | local workspace = `workspacePath`; remote identities (`remote:ssh/wsl/docker:...`) resolved by host via `parseRemoteWorkspaceIdentity` (`session-mgmt.ts:27-33`). |
| `firstInput` | object `{ text: string, attachments?: AttachmentRef[], modelSelection?: ModelSelection, mode?: SubmissionMode, planEnabled?: boolean }` | optional | |
| `firstInput.text` | `string` | required within object | empty (and no attachments) is rejected before record creation: `proto.invalidPayload` (`session-mgmt.ts:43-49`). |
| `config` | `createSessionRequestedConfigSchema` | optional | `{ modelSelection?, provider?: string, model?: string, thought?: string, followupMode?: "queue"|"guide", mode?: string, planEnabled?: boolean }` (`command.ts:30-41`). |
| `mcpServers` | `zcodeProtocolMcpServerSchema[]` | optional | MCP is runtime startup config; must enter the record at create time in one shot, cannot be back-filled after first send (`command.ts:58`). Two variants: stdio (`name, command, args[], env[], isolation?, protocolVersion?, timeoutMs?`) or http/sse (`name, type, url, headers[], oauth?, isolation?, protocolVersion?, timeoutMs?`) (`zcode-protocol/index.ts:629-653`). |
| `offPeakToolEnabled` | `boolean` | optional | Off-Peak tool-surface flag, additive; old CLIs silently drop it (fail-closed) (`command.ts:60-63`). |
| `dynamicWorkflowEnabled` | `boolean` | optional | dynamic-workflow rollout flag, same pattern (`command.ts:64-65`). |

**Semantics:** `config` is a *request override*, not a snapshot partial — do **not** reuse `sessionConfigStateSchema.partial()` because snapshot defaults `mode` to `"build"` and would misinterpret "mode not passed" as "switch back to build", clobbering a workspace default of `yolo` (`command.ts:36-38`). New sessions are always `deferred` persistence (not in sqlite) and promoted to `immediate` on first send (`session-mgmt.ts:22-33`). `config` is applied **before** `firstInput`; if config application fails it degrades to `warn` and the session keeps runtime defaults — a `failed` ACK would only leak the session (`session-mgmt.ts:56-70`).

**Result:** `{ type: "createSession", sessionId, input?: { delivery, inputId, messageId? } }` (`command.ts:371-382`).

### 2.2 `createSelectionSideSession`

**Purpose:** create a hidden `selection_side_chat` child from the parent's stable on-disk boundary. Parent session = `envelope.sessionId`; the server derives full run config from the parent record. If `firstInput` exists, the first normal input starts immediately after the child is created; otherwise the side pane stays empty (`command.ts:67-77`).

Payload: `{ firstInput?: { text: string(trim,min1), modelSelection?: ModelSelection } }`. A submission recommendation only overrides the new child's complete selection; absence keeps the parent runtime inheritance (`command.ts:70-75`).

**Result:** `{ type: "createSelectionSideSession", sessionId, input?: { delivery, inputId, messageId? } }`. Input only lands on the child; the parent's CommandInbox/queue is not involved (`selection-side-session.ts:33-41`).

### 2.3 `sendText`

**Purpose:** the main user-input command. Routing decided by `inputRouting`: `startNow / enqueue / guide / choice` (`command.ts:78`).

Payload (`command.ts:81-134`):
| field | type | req? | notes |
|---|---|---|---|
| `text` | `string` | **required** | text OR attachments must be non-empty: `hasPromptInput` = `text.trim().length > 0 \|\| attachments.length > 0` (`session-flow.ts:37-39`); else `proto.invalidPayload`. |
| `attachments` | `AttachmentRef[]` | optional | mapped to `TurnAttachment` after the gate (`session-flow.ts:194, 252`). |
| `requestedDelivery` | `"startNow" \| "queue" \| "guide"` | optional | Desktop Cmd/Ctrl+Enter only overrides this one busy input, does not change session `followupMode`. `startNow` atomically preempts the current turn by CLI, bypassing queue admission (`command.ts:84-87`). |
| `browserAmbientContext` | `{ tabCount: int(1..100), currentUrl?: string(trim,1..4096) }` | optional | `.strict()` (`zcode-protocol/index.ts:1723-1728`). |
| `context_refs` | `SharedContextRef[]` **max 1** | optional | Share handover allows only one already-imported context for the current session; full body resolved by runtime from persisted provenance, never passed by the renderer (`command.ts:89-91`). |
| `heldQueueDisposition` | `"clearQueueAndSend" \| "keepQueueAndSend"` | optional (required when `inputRouting.mode === "choice"`) | |
| `expectedHeldQueueItemIds` | `string(min1)[]` | optional | set of queueItemIds the user saw when the paused-queue confirmation opened; CLI validates before clear/keep to prevent acting on items added concurrently by another device (`command.ts:93-95`). |
| `modelSelection` | `ModelSelection` | optional | migration: old senders may omit; CLI pins current Session Selection into the canonical intent. |
| `mode` | `SubmissionMode` | optional | |
| `planEnabled` | `boolean` | optional | |
| `modelExecution` | `modelExecutionSchema` | optional | `{ memoryExtraction?: "skip", selectionScope: "execution", requestAuth?: { apiKey?, headers? }, subagents?: { foregroundModel: "submission", background: "deny" } }` (`model-execution.ts`). Carries non-persisted semantics, dynamic auth, child policy. **Only accepted on idle `startNow`**; prevents Secret/Ticket from entering the normal CommandInbox (`command.ts:101-103`). |
| `automationId` | `string(min1)` | optional | mutually exclusive with `offPeakTaskId`. |
| `offPeakTaskId` | `string(min1)` | optional | |
| `offPeakRunType` | `"init" \| "resume"` | optional | requires `offPeakTaskId`. |
| `botDeliveryTarget` | `{ provider: "feishu"\|"lark"\|"weixin", botId, providerUserId, chatType: "private"\|"group" }` | optional | Bot origin injected by Host only; `CronCreate` reads it within the turn and persists the push-back address (`command.ts:107-108`, `bots.ts:41-48`). |
| `toolDisallowlist` | `string(min1)[]` | optional | scheduled-task sessions must keep turn-scoped tool-surface isolation; cannot borrow `automationId` (`command.ts:109-111`). |

Cross-field rules (`superRefine`, `command.ts:113-134`): `automationId` ⊕ `offPeakTaskId`; `offPeakRunType` requires `offPeakTaskId`; `modelExecution` requires `modelSelection`.

**Semantics / reasons:**
- `startNow`: acquires a foreground promotion lease `send-now:<commandId>` with mode `after-current`, preempts active turn and waits idle, then Core starts with `requireIdle: true` (`session-flow.ts:199-289`). Lease busy → `fault.command.inputRejected`.
- `applyHeldQueueDisposition` (`session-flow.ts:105-131`): when `inputRouting.mode === "choice"`, missing disposition → `heldQueueDispositionRequired`; `expectedHeldQueueItemIds` mismatch (size/dup/missing) → `guard.heldQueueConfirmationStale`; `clearQueueAndSend` clears the queue first.
- `guide` routing with attachments sets `fallbackReasonCode: "guide.attachmentsUnsupported"` (`session-flow.ts:259-261`).
- Deferred-input rejection mapping: `input_too_large → proto.payloadTooLarge`, `empty_input → proto.invalidPayload`, else `fault.command.inputRejected` (`session-flow.ts:82-89`).

**Result:** `{ type: "inputAccepted", delivery: "startNow"|"queue"|"guide", inputId, messageId? }` (`command.ts:412-419`). `messageId` is only back-filled after `TurnStarted`; Core admission ACK does not wait for projection commit, so `messageId` is never a precondition for "input accepted".

### 2.4 `sendGoalCommand`

**Purpose:** set/update the goal (legacy `goalSession action:"set"`). Payload (`command.ts:135-143`): `{ text: string, displayText?: string, modelSelection?, mode?, planEnabled?, heldQueueDisposition?, expectedHeldQueueItemIds? }`.

**Semantics** (`goal-compact.ts:231-304`):
- empty objective → `V4GoalCompactRejectedError("emptyObjective")`.
- `planEnabled` → `guard.planGoalMutuallyExclusive` ("Plan and Goal cannot be active at the same time.").
- busy (active turn, or routing `enqueue`/`guide`) → enqueued with `commandKind: "sendGoalCommand"` so consumption still routes as a goal command, not an ordinary prompt; queue text = `displayText ?? "/goal <objective>"`.
- **Duplicate `set` converges to replace** (`goal-compact.ts:236-239`): if a target already exists it is replaced (broadcast reason `goal_replaced` vs `goal_set`).
- `parseGoalObjectiveFromCommandText` accepts `/goal` or `/target`, strips a leading `replace ` (`goal-compact.ts:353-359`).

**Result:** none (accepted).

### 2.5 `stop`

Payload: `{ expectedForegroundExecutionId?: string(min1) }` (`command.ts:144-147`) — comes from `activeWorks`; CLI uses it to refuse a late Stop that would kill an unrelated later execution.

**Semantics** (`session-flow.ts:304-371`): cancels the projected runtime foreground execution and settles an active goal to `paused`. If `expectedForegroundExecutionId` is present and runtime returns `idle` or `mismatch` → throws `V4CommandNoopError("guard.stopTargetChanged")` (noop, not failure). If queue non-empty after stop → explicitly `setQueueAutoDrain(false)` so future queue items stay held. Falls back to aborting the bootstrap `activeAbortController` for compact / old clients without a runtime foreground token.

### 2.6 `compact`

Payload: `z.object({})` — **empty object**; there is no instructions variant (`command.ts:148-150`).

**Semantics** (`goal-compact.ts:56-118`): input-type maintenance command. Rejected with `compactOperationLock` if a compact turn is running, a manual compact controller is live, or a `compact` queue item exists. `restoreWarning` → rejected (`"restoreWarning"`). If busy or routing ∈ {`enqueue`,`guide`,`choice`} → typed `compact` intent enqueued (FIFO barrier), `inputId = commandId`; queue rejection mapped like sendText. Else `startManualCompact` runs `/compact` in the background. On failure/cancel with a non-empty queue, `setQueueAutoDrain(false)` holds following input. No CAS (idempotency edge = `sourceCommandId`); a persistent timeline command fact is written so a restart does not re-prompt it (`goal-compact.ts:206-226`).

### 2.7 `forkAssistant`

Payload: `{ target: ConversationRowTarget }` (`command.ts:152`). Available on a stable assistant row while running.

**Semantics** (`fork-edit-retry.ts:280-329`): the only stable resolver fixes the logical-turn/message boundary; then conversation-only fork. Does **not** read `activeAbortController`, does not stop the parent, does not enter legacy `forkSession` (which does `ensureNoActiveTurn` + workspace rewind). Rejections: `guard.forkTargetNotStable`, `guard.forkTargetAmbiguous`, `guard.forkAssistantOnly`, `guard.compactOperationLock` (`fork.ts:19-28`), and `fault.command.executionFailed` for unresolved row/segment.

**Result:** `{ type: "forkAssistant", sessionId: <forkedSessionId> }` (`command.ts:371-382`).

### 2.8 `applyFileRewind`

Payload: `{ target: ConversationRowTarget }` (`command.ts:153`). Workspace-only file undo; **does not truncate chat history** (`command.ts:3-4`).

**Semantics** (`file-rewind.ts:11-37`): resolves the row target, gets `messageIds` for the whole product turn and `targetTurnId`, calls `runtime.applyWorkspaceFileRewind`. Row-translation failure → `fault.command.executionFailed`. **Result:** `{ type: "applyFileRewind", applied: boolean, preview, response: string }` (`command.ts:390-395`).

### 2.9 `editUserQuery`

Payload: `{ target: ConversationRowTarget, newText: string, attachments?: AttachmentRef[], workspaceMode?: "preserve"|"rewind" }` (`command.ts:154-160`). Default `preserve` = only switch conversation branch; `rewind` first safely restores that turn's files. Conversation rewind has **no separate command** — this is its UI entry (`command.ts:3`).

**Semantics** (`fork-edit-retry.ts:120-239`): target is a user entity; its canonical transcript messageId is the rewind anchor → whole-segment truncate → native prompt turn re-sends `newText`. Target must be the last real-user row: `guard.latestQueryEditOnly` (`fork-edit-retry.ts:69-76`); resolver unavailability → `guard.actionUnavailable`; translation failure → `fault.command.executionFailed`. Attachment refs default to the edit target's stable refs; empty text+attachments → `proto.invalidPayload`; attachments mapped **before** rewind so a bad ref fails before truncation. In `rewind` mode: preview must be `canApply`, have no `ignoredFiles`, and ≥1 `safeFiles`, else returns `disposition: "blocked"` with `reasonCode` ∈ `guard.workspaceRewindUnsafeFiles` / `guard.workspaceRewindIgnoredFiles` / `guard.workspaceRewindUnavailable` / `guard.workspaceRewindApplyConflict`.

**Result:** `{ type: "editUserQuery", disposition: "rewind"|"fork"|"blocked", sessionId, reasonCode?, preview? }` (`command.ts:396-403`). `fork` remains only for old ACK decode compatibility; new `editUserQuery` never creates a child session.

### 2.10 `retryTurn`

Payload: `{ target: ConversationRowTarget }` (`command.ts:161`). **Semantics** (`fork-edit-retry.ts:246-273`): assistant target → messageId → rewind truncate + re-send the canonical user intent. The original prompt is resolved **before** rewind (unavailable after truncation). Non-last assistant row → `guard.latestAssistantRetryOnly`.

### 2.11 `setAssistantFeedback`

Payload: `{ target: ConversationRowTarget, feedback: "like"|"dislike"|null }` (`command.ts:162-165`). **Semantics** (`assistant-feedback.ts:10-33`): target must be an `assistantText` row with a messageId; persists transcript metadata first, then publishes the same-entity projection event. Failure → `fault.command.executionFailed`; missing host capability → `fault.command.assistantFeedbackUnsupported`.

### 2.12 `sendQueuedNow`

Payload: `{ queueItemId: string }` (`command.ts:166`). **Semantics** (`queue.ts:156-291`): `reserve → Core promotion lease → stop barrier → start/promote → remove`.
- Must read the **complete** `QueueItem`; a text-only fallback is forbidden (would lose `sourceCommandId`/attachments/client/order).
- `queueItemId ≡ core pendingInputId` (same id space).
- Rejections: `queue.itemMissing` (unknown id, via `V4QueueItemTextUnavailableError`), `guard.queueItemReserved` (already reserved), `guard.queuePromotionBusy` (lease conflict / not idle), `fault.command.queuePromotionCommitFailed` (started but queue item removal failed).
- Reservation id = `commandId`; on any pre-start failure the reservation is released and the original item stays in place; only after a successful start is it removed.

### 2.13 `editQueueItem`

Payload: `{ queueItemId: string, newText: string }` (`command.ts:167`). **Semantics** (`queue.ts:91-112`): in-place update by core reducer (keeps position). Editing a `compact` item → `guard.queueItemNotEditable` (would disguise typed maintenance as a normal prompt). Miss = warn (race semantics), still accepted.

### 2.14 `reorderQueueItem`

Payload: `{ queueItemId: string, beforeQueueItemId: string \| null }` (`command.ts:169-172`). `beforeQueueItemId = null` → move to tail (protocol and app API same shape). Miss = warn.

### 2.15 `deleteQueueItem`

Payload: `{ queueItemId: string }` (`command.ts:173`). **Semantics** (`queue.ts:71-89`): miss (concurrent drain / duplicate delete) → **noop** `queue.itemMissing` (not a failure), so the client does not treat it as accepted and re-restore an already-consumed projection.

### 2.16 `setAutoDrain`

Payload: `{ autoDrain: boolean }` (`command.ts:174`). **Semantics** (`queue.ts:131-144`): flips the authorization bit; when `true` it must also trigger the CLI ready hook so an idle paused queue is immediately promoted on the `sendQueuedNow` atomic path (cannot just flip a bit — idle has no active turn to start the head).

### 2.17 `resolveInteraction`

Payload (`command.ts:176-188`): `{ interactionId: string, answer: { optionId?: string, freeText?: string, action?: "accept"|"decline"|"cancel", content?: Record<string,unknown> } }`.

**Semantics** (`interaction-background.ts:45-63`): delivers the answer to the waiting deferred of the interaction broker. First-come-first-served; late/repeat calls are **idempotent success** (`delivered === false` does not throw). **No `requireRecord`** — a late answer may arrive after the session is settled/deleted and must still be harmless (registry addressed globally by `interactionId`). If `answer.optionId === PERMISSION_FULL_ACCESS_OPTION_ID` (`"fullAccess"`, `snapshot.ts:229`) it goes through `resolveFullAccess`. Late resolution = `noop` / `proto.alreadyResolved` per the payload comment (`command.ts:175`).

**Result:** `{ type: "resolveInteraction", resolvedBy: { clientId, optionId? } }` (`command.ts:383-389`).

### 2.18 `respondWorkspaceHookReview`

Payload = `workspaceHookReviewCommandTargetSchema` extended with `decision: workspaceHookReviewDecisionSchema` (`command.ts:189-191`).
Target (`workspace-hook-review.ts:33-45`, `.strict()`): `{ sessionId, taskId, runId, remoteSessionId?, workspaceIdentity, bundleDigest(sha256 64-hex), reviewFlowId, generation(int>0), interactionId }`.
Decision (`workspace-hook-review.ts:20-31`): `{ action: "trust_selected", reviewItemIds: string(min1)[] }` — reviewItemIds must be unique.

**Semantics** (`interaction-background.ts:88-97`): `envelope.sessionId` must equal `payload.sessionId`, else `workspace_hooks_snapshot_mismatch`; rejection otherwise carries the app's `result.reasonCode`.

### 2.19 `toggleWorkspaceHookReviewItem`

Payload = target extended with `{ reviewItemId: string(trim,min1), enabled: boolean }` (`command.ts:192-195`). Same `sessionId` guard; rejection carries app `reasonCode` (`interaction-background.ts:99-108`).

### 2.20 `revokeWorkspaceHookTrust`

Payload = **union** (`command.ts:196-201`):
1. target extended with `{ reviewItemIds: string(trim,min1)[] (min1) }`, or
2. `workspaceHookTrustRevokeTargetSchema` (`workspace-hook-review.ts:50-62`): `{ sessionId, remoteSessionId?, workspaceIdentity, bundleDigest, hookDeclarationDigests: sha256[] (min1, unique) }`.

### 2.21 `requestWorkspaceHookReview`

Payload = `requestWorkspaceHookReviewTargetSchema` (`command.ts:203`; `workspace-hook-review.ts:67-74`): `{ sessionId, remoteSessionId?, workspaceIdentity, bundleDigest }`. Soft gate: opens the review flow on demand; clones the revoke non-flow target but without `hookDeclarationDigests` (the flow pulls all pending items from the current snapshot). Idempotently reuses an active flow; safe no-op when there are no pending items (`interaction-background.ts:121-139`).

### 2.22 `snoozeInteractionAutoResolution`

Payload: `{ interactionId: string }` (`command.ts:205-207`). First valid `AskUserQuestion` action permanently pauses this auto-resolution; duplicate/late calls are idempotent noop (`command.ts:204`). **Semantics** (`interaction-background.ts:65-79`): no active countdown → idempotent, logs and returns.

### 2.23 `switchModelConfig`

Payload: `{ provider: string, model: string, thought: string }` (`command.ts:208-212`). **Semantics** (`model-config.ts:76-142`): same value → **noop** `config.unchanged` (must be distinguishable, not silently accepted). Cross-model → `app.setModel` swaps provider client + model; same model → `thought` is the explicit reasoning-level switch. Emits `ModelSelected` (drives projection config + mid-turn `modelChange` marker). No active-turn guard (matches legacy: switching allowed while running). Target provider must be in the Environment Registry, else `provider.notInRegistry`.

### 2.24 `switchCollaborationMode`

Payload: `{ mode: "build"|"edit"|"plan"|"yolo" }` (`command.ts:215-217`). Value domain = the user-switchable subset of `CollaborationMode`; `auto` is not user-switchable and is absent from the UI command surface (`command.ts:213-216`). **Semantics** (`model-config.ts:153-166`): same mode **and** plan disabled → noop `config.unchanged` (explicit ACK required, otherwise a missing projection seed turns "click full-access did nothing" into a silent no-op). `app.setMode` updates/persists and emits `SessionModeChanged`.

### 2.25 `setFollowupMode`

Payload: `{ mode: "queue"|"guide" }` (`command.ts:218`). **Semantics** (`queue.ts:146-154`): `app.setFollowupMode`.

### 2.26 `pauseGoal`

Payload: `z.object({})` (`command.ts:219`). **Semantics** (`goal-compact.ts:371-388`): independent target control; does not reuse generic stop's queue hold/disposition. No target or target not `active` → idempotent return. Settles target active run then aborts current goal work.

### 2.27 `resumeGoal`

Payload: `z.object({})` (`command.ts:220`). **Semantics** (`goal-compact.ts:394-427`): no target → idempotent success (legacy returns "No goal to resume." without changing state, no throw). Active turn → rejected `activeTurn` ("Cannot manage goals while a prompt is running"). Plan enabled + target exists → `guard.planGoalMutuallyExclusive` (checked **before** writing).

### 2.28 `cancelBackgroundWork`

Payload: `{ workId: string }` (`command.ts:221`). `workId ≡ taskId`; passed straight to core (`interaction-background.ts:177-192`). Missing `cancelBackgroundTask` capability → `fault.command.capabilityUnsupported`. Core returning a `reason` → `fault.command.backgroundWorkCancelRejected.<reason>` where reason = core's stopBackgroundTask reason minus the `background_task_` prefix: `not_found` / `not_running` / `cancel_not_supported` (`command.ts:288-293`). A real cancel, or a stub host returning nothing, is `accepted`.

### 2.29 `resumeWorkflowRun`

Payload: `{ workId: string, name?: string }` (`command.ts:228`). `workId ≡ runId`; `name` optional, feeds the completion-notification topic after resume (the original CreateWorkflow tool input is unavailable after restart). No `baseRevision`. Resumable set = `cancelled ∪ failed+Interrupted`. Missing capability → `fault.command.capabilityUnsupported`. Rejections → `fault.command.workflowRunResumeRejected.<reason>` with reason ∈ `not_found / not_resumable / superseded / already_running / script_missing / script_mismatch / compile_failed`; `compile_failed` bounded diagnostics go into `ack.message` (`command.ts:282-286`, `interaction-background.ts:194-227`).

### 2.30 `startSavedWorkflow`

Payload: `{ name: string(min1), scope?: "project"|"global", args?: Record<string,unknown> }` (`command.ts:236-240`). The hub's "Run" no longer synthesizes chat text; it directly asks the agent to start a saved workflow in a new session. No `baseRevision`. `name` is filled by the agent from the parse result (invariant 6); the command accepts no name override (`command.ts:229-235`). Missing capability (no dwf port) → `V4CapabilityUnsupportedError`. Rejections → `fault.command.savedWorkflowStartRejected.<reason>`, reason ∈ `invalid_name / not_found / invalid_args / compile_failed / session_busy / start_failed` (`savedWorkflowStartRejectionReasonSchema`, `command.ts:265-272`).

**Result:** `{ type: "startSavedWorkflow", runId: string(min1), toolCallId: string(min1) }` (`command.ts:404-410`). `runId` joins the launch turn's run card/notification/side panel; `toolCallId = "launch-<uuid>"` joins the synthesized CreateWorkflow turn.

### 2.31 `amendWorkflowRunSettings`

Payload (`workflow-run-settings-command.ts:15-27`): `{ workId: string, subagentModel?: string(min1,max)`|null`, maxConcurrency?: int(min1)`|null` }`. `workId ≡ runId`. Three-state rule for both settings: omitted = keep, `null` = back to default (session model / local ceiling), value = set. GUI sends only the items the user changed. No `baseRevision`.

Missing capability → `V4CapabilityUnsupportedError`. Rejections → `fault.command.workflowRunSettingsRejected.<reason>`, reason ∈ `not_found / not_configurable / unchanged / script_missing / model_unavailable / compile_failed / missing_boundaries / start_failed` (`workflow-run-settings-command.ts:46-55`); the old run keeps running on rejection.

**Result:** `{ type: "amendWorkflowRunSettings", runId, toolCallId, supersededRunId? }` (`workflow-run-settings-command.ts:34-39`). `toolCallId = "settings-<uuid>"`; `supersededRunId` only present when the old run was still in flight and stopped by this amendment.

### 2.32 `renameSession`

Payload: `{ title: string }` (`command.ts:244`). **Semantics** (`session-mgmt.ts:140-152`): `runtime.setCustomSessionTitle` with the session root `traceContext`. `titleSource=custom` stickiness (auto-title short-circuits on `custom_title`) is guaranteed by core. No legacy broadcast — core emits `SessionTitleUpdated`, projected by the gateway.

### 2.33 `deleteSession`

Payload: `z.object({})` (`command.ts:245`). **Semantics** (`session-mgmt.ts:160-171`): semantics = `closeSession` (close + release runtime resources), **not a real record delete** — the message store has no delete API; history stays in the DB, it just disappears from the active registry. Missing `closeSession` capability → throws → `failed` ACK (never silently degrade).

### 2.34 `discardSharedContext`

Payload: `z.object({ contextId: string(trim,min1) }).strict()` (`command.ts:246`). **Semantics** (`session-mgmt.ts:173-189`): requires a session-scoped storage capability; not updated (not pending) → `fault.command.inputRejected` ("shared context is not pending").

---

## 3. CommandAck

Schema: `commandAckSchema`, `command.ts:430-443`. Result union: `commandResultSchema`, `command.ts:370-428`.

| field | type | req? | meaning |
|---|---|---|---|
| `memoryEnabled` | `boolean` | optional | App Memory switch adopted at session creation; absent = unknown to old senders. |
| `ttftExcluded` | `"capacity"` | optional | present only when the TTFT context was capacity-rejected (`v4-gateway.ts:2402, 2420`). |
| `commandId` | `string` | **required** | echoes the envelope. |
| `status` | `"accepted" \| "rejected" \| "stale" \| "duplicate" \| "noop" \| "failed"` | **required** | see below. |
| `reasonCode` | `string` | optional (**required** for rejected/stale/noop/failed) | guard id or fault code (namespaced). |
| `message` | `string` | optional | human-readable detail (e.g. bounded compile diagnostics). |
| `revisionAtDecision` | `number` | **required** | revision the decision was made at. **On `stale` it carries the server's current revision** so the client can re-sync/retry. |
| `result` | `CommandResult` | optional | duplicate replays the cached result; `accepted` may also carry it immediately (fork, createSession, applyFileRewind, editUserQuery, startSavedWorkflow, amendWorkflowRunSettings, inputAccepted). |

`accepted` does not promise survival across CLI processes; the final authority is durable data keyed by `sourceCommandId` (`command.ts:435`).

### Status semantics

| status | meaning | client action |
|---|---|---|
| `accepted` | executed (or admission accepted); may carry `result`. | proceed |
| `rejected` | guard/validation refusal before execution; nothing happened. | fix payload / resync |
| `stale` | CAS/epoch/target failed; `revisionAtDecision` = current revision. Not remembered in the idempotency table. | re-read revision, retry with new `commandId` |
| `duplicate` | same `(sessionId, commandId)` seen before; **replays the cached final ACK** (result included). | treat as idempotent success |
| `noop` | well-formed but nothing to do (same-value switch, late interaction answer, missing queue item). Idempotent success. | stop / no retry |
| `failed` | domain or internal error during execution; **`failed` is terminal and is never rewritten to `duplicate`** (`command-inbox.ts:446-449`). | surface error |

Consumer helper: `assertV4CommandAckOk` (`zcodeV4HostCommand.ts:102-111`) treats `accepted`/`duplicate`/`noop` as success; `rejected`/`stale`/`failed` throw a structured `ZCodeV4CommandRejectedError` carrying the original `reasonCode`.

### reasonCode catalogue

**Protocol validation** (`proto.*`):
| code | where |
|---|---|
| `proto.invalidPayload` | envelope/payload parse failure (`command-inbox.ts:120-126`); empty input (`session-flow.ts:192`); missing row target (`v4-gateway.ts:659`) |
| `proto.sessionNotFound` | unknown session, or non-createSession with `sessionId: null` (`command-inbox.ts:312-323`) |
| `proto.missingBaseRevision` | CAS command without `baseRevision` at decide time (`command-inbox.ts:325-337`) |
| `proto.staleRevision` | `baseRevision !== revision` (`command-inbox.ts:352-363`); also fileChanges/fileRewindPreview queries (`v4-gateway.ts:1964`) |
| `proto.staleLogEpoch` | `baseLogEpoch !== logEpoch` (`command-inbox.ts:340-351`, `v4-gateway.ts:1963`) |
| `proto.staleTarget` | row target not resolvable in current projection (`v4-gateway.ts:663`, `product-projection.ts:768`) |
| `proto.payloadTooLarge` | deferred input `input_too_large`; projection size overflow (`v4-gateway.ts:2474-2478`) |
| `proto.alreadyResolved` | late `resolveInteraction` (payload comment `command.ts:175`) |

**Guards** (`guard.*`):
`guard.actionUnavailable` (`product-projection.ts:776,795,806,817,826,836`), `guard.compactOperationLock`, `guard.forkAssistantOnly`, `guard.forkTargetNotStable`, `guard.forkTargetAmbiguous` (`fork.ts:19-28`), `guard.heldQueueConfirmationStale`, `guard.latestAssistantRetryOnly`, `guard.latestQueryEditOnly`, `guard.planGoalMutuallyExclusive`, `guard.queueItemNotEditable`, `guard.queueItemReserved`, `guard.queuePromotionBusy`, `guard.selectionSideChatRestrictedCommand` (`executor.ts:21`), `guard.stopTargetChanged`, `guard.workspaceRewindApplyConflict`, `guard.workspaceRewindIgnoredFiles`, `guard.workspaceRewindUnavailable`, `guard.workspaceRewindUnsafeFiles`, `guard.subagentReadOnly` (`v4-bridge.ts:1595`).

**Domain / faults** (`fault.command.*`, `fault.*`):
`fault.command.executionFailed` (default), `fault.command.notImplemented`, `fault.command.capabilityUnsupported`, `fault.command.inputRejected`, `fault.command.queryUnavailable` (`command-inbox.ts:451-458`), `fault.command.childStartFailed` (`v4-bridge.ts:503`), `fault.command.assistantFeedbackUnsupported`, `fault.command.queuePromotionCommitFailed`, `fault.command.backgroundWorkCancelRejected.<not_found|not_running|cancel_not_supported>`, `fault.command.workflowRunResumeRejected.<...>`, `fault.command.savedWorkflowStartRejected.<...>`, `fault.command.workflowRunSettingsRejected.<...>`.
Non-command: `fault.fileChanges.unsupported`, `fault.fileRewindPreview.unsupported`, `fault.attachment.*` (see §5), `fault.provider.*`, `fault.network.*`, `fault.projectionEventCommit.*`.

**Config noops:** `config.unchanged` (`model-config.ts:18`), `queue.itemMissing`, `heldQueueDispositionRequired` (note: this one is a bare reasonCode without a namespace, `session-flow.ts:43`).

### Decision pipeline (`CommandInbox.decide`, `command-inbox.ts:308-444`)

Order: session existence → (if CAS) `baseRevision` presence → `baseLogEpoch` → `baseRevision` → row-target resolver → business `guard` → allow/execute. Per-session **FIFO gate** held from admission until `settle`; key gate per `(sessionId, commandId)`. Idempotency buckets: in-flight → live-input (pinned) → settled LRU (max `idempotencyTablePerSession = 512`, `core.ts:82`) → durable lookups `lookupTranscriptCommand` → `lookupTimelineCommand` → `lookupChildCommand` → `lookupDiscardedCommand` (`command-inbox.ts:284-306`). In-flight duplicates await the shared final promise, so `fork`/`createSession` retries always see the child `result`.

---

## 4. Query surface

All queries are read-only, stateless, and safe to resend on timeout. There is no `queryPayloadSchemas`/`ConversationQuery` symbol in this repo — the query vocabulary is the `V4_METHODS` table (`transport.ts:332-384`) plus `commandsQueryParamsSchema` (`command.ts:456`). Some queries deliberately are **not** v4 commands because the command ACK result is a closed "mutation result" union (putting a read page into it would force `baseRevision`/idempotency machinery onto a read) — see `transport.ts:594-598`.

### 4.1 `v4/commands/query` — `queryConversationCommandsV4`

Params `commandsQueryParamsSchema` (`command.ts:456-466`, `.strict()`):
- `commands: CommandKey[]` — **min 1, max 64**. `CommandKey` (`command.ts:447-454`, `.strict()`): `{ sessionId: string|null, commandId: string(min1) }`.
- `clock?: true` — pure clock probe; **cannot query session commands** (`refine`: every key must have `sessionId === null`).

Result `commandsQueryResultSchema` (`command.ts:476-482`, `.strict()`):
- `results: { key: CommandKey, result: CommandAck | "unknown" }[]` (min1,max64) — `"unknown"` = never seen.
- `clock?: localTtftClockSchema` (only for clock probes).

**Semantics** (`v4-gateway.ts:2544-2563`): awaits READY flights per sessionId, then `inbox.query`; shares the same idempotency gate as `handleCommand`. Client-side this is how a renderer recovers its pending command ledger after restart (`packages/ui/src/v4/pendingCommandRegistry.ts`, TTL 24h = `PROTOCOL_V4_LIMITS.commandPendingTtlMs`).

Service wrapper: `IZCodeAgentService.queryConversationCommandsV4` (`packages/services/src/zcode-agent/zcodeAgent.ts:789`), `zcodeAgentService.ts:5111-5120`.

### 4.2 `v4/conversation/rowsRange` — `conversationRowsRangeV4`

Params `v4ConversationRowsRangeParamsSchema` (`transport.ts:511-519`): `{ sessionId: string, clientMode?: "desktop-continuous"|"web-remote-replayable", beforeRowId?: number, limit: number(1..PROTOCOL_V4_LIMITS.rowsRangeMaxLimit=200) }`. `clientMode` is a trusted host-injected value; renderer callers omit it.

Result `v4ConversationRowsRangeResultSchema` (`transport.ts:521-531`): `{ rows: ConversationRow[] (rowId ascending), atSeq: number, atRevision: int(≥0), atLogEpoch: string, hasMore: boolean }`.

Semantics (`v4-gateway.ts:1570-1586`): cursor pagination (`loadOlder`); no index semantics, total order = ascending rowId, client merges by rowId. Cold sessions reuse the same cold-resume + hydration pipeline as `subscribe`. `clientMode === "desktop-continuous"` → `"continuous"` delivery filter, otherwise `"replayable"`. `atSeq`/`atRevision`/`atLogEpoch` are the staleness guard shared with `fileChanges` etc. — do not stitch published data across revisions.

### 4.3 `v4/conversation/plans`

Params `v4ConversationPlansParamsSchema` (`transport.ts:535-540`): `{ sessionId: string(min1) }`.
Result (`transport.ts:542-550`): `{ plans: toolCallRow[], atSeq: int(≥0), atLogEpoch: string }` — terminal `ExitPlanMode` of the current effective branch, rowId descending (newest first). The directory comes from the CLI's full effective projection, not a renderer's bounded tail window.

### 4.4 `v4/conversation/fileChanges` — `conversationFileChangesV4`

Params `v4ConversationFileChangesParamsSchema` (`transport.ts:562-570`, `.strict()`): `{ sessionId: string(min1), target: ConversationRowTarget, baseRevision: int(≥0), baseLogEpoch: string(trim,min1) }`.
Result (`transport.ts:572-592`, `.strict()`): `{ files: int, additions: int, deletions: int, state?: "active"|"reverted", items: { path(min1), additions, deletions, writeCount, toolNames: string[], patches: { oldStart, oldLines, newStart, newLines, lines: string[] }[] }[] }`.

Semantics (`v4-gateway.ts:1900-1920`): capability-gated by `host.getConversationFileChanges` (`fault.fileChanges.unsupported`); cold/hydrated publisher; `resolveQueryRowTarget` enforces `baseLogEpoch` then `baseRevision` then row-action resolution (throws `proto.staleLogEpoch` / `proto.staleRevision` / `guard.actionUnavailable`). The row target must be a `turnHeader` with `row.fileChanges` and `row.actions.canRewindFiles === true` (`product-projection.ts:822-838`).

### 4.5 `v4/conversation/fileRewindPreview`

Params `v4ConversationFileRewindPreviewParamsSchema` (`transport.ts:710-717`): `{ sessionId, target, baseRevision, baseLogEpoch }`.
Result `v4ConversationFileRewindPreviewResultSchema` (`transport.ts:758-765`): `{ canApply: boolean, ignoredFiles: {operationCount, path, reason: "bash_ignored", toolNames}[], safeFiles: {action: "restore"|"delete", operationCount, path, toolNames}[], unsafeFiles: {currentHash?, expectedHash?, message?, operationCount, path, reason: "checkpoint_missing"|"checkpoint_unreadable"|"external_modified"|"file_read_failed"|"unsupported_checkpoint", toolNames}[] }`.

### 4.6 `v4/conversation/backgroundBashOutput`

Params `v4BackgroundBashOutputParamsSchema` (`transport.ts:388-391`, `z.strictObject`): `{ sessionId: string(min1), workId: string(min1) }`. Task-authorized bounded output query; does not hydrate/resume cold sessions (`v4-gateway.ts:1922-1929`). Returns `{ kind: "unsupported", workId }` when the host lacks the capability.

### 4.7 `v4/conversation/workflowRunEvents`

Params (`transport.ts:599-607`, `.strict()`): `{ sessionId, runId, afterSequence?: int(≥0), limit?: int(1..500) }`.
Result (`transport.ts:612-629`): `{ events: { sequence, type(1..64), payload: Record<string,unknown>, truncated? }[], hasMore: boolean }`. Cursor = journal sequence. **Deliberately carries no `atSeq`/`atLogEpoch`** (`transport.ts:630-646`): it reads the journal (`dwf_event`), unrelated to the conversation log; the cursor never expires, and adding an epoch field would make an unrelated rewind discard a legitimate page. `hasMore` determined by over-fetching one (`v4-gateway.ts:1609-1627`).

### 4.8 `v4/conversation/workflowRuns`

Params (`transport.ts:654-660`): `{ sessionId, limit?: int(1..64) }`.
Result (`transport.ts:700-708`): `{ runs: V4ConversationWorkflowRunSummary[] }`, most-recently-updated first. Summary (`transport.ts:665-695`): `{ runId, toolCallId?, label?(1..160), updatedAt?, status: "completed"|"errored"|"pending"|"running"|"stopped", stopReason?, resumedFrom?, supersededBy?, failureCode?(1..64), failureMessage?(≤2048), resumable: boolean }`. `resumable` computed by CLI with the same predicate as the resume gate (UI must not re-derive). Journal-backed restart discovery (`workflowRuns` projection is memory-only).

### 4.9 `v4/conversation/workflowRunArtifacts` / `...ArtifactData` / `...ArtifactRead`

- **Artifacts** (`workflow-artifacts.ts:145-163`): params `{ sessionId, runId }`; result `{ artifacts: workflowRunArtifactSummary[] }`. Unknown runId → empty list (a fact, not a fault). No `atSeq`/`atLogEpoch`.
- **ArtifactData** (`workflow-artifacts.ts:168-209`): params `{ sessionId, runId, artifactId(≤maxIdLength), afterSequence?, limit? }`; result `{ items: { sequence, siteId(1..64), ordinal, item: unknown }[], hasMore }`. Default limit 200, clamped `[1, 500]` (`maxItemsPerPage`), enforced gateway-side.
- **ArtifactRead** (`workflow-artifacts.ts:219-276`): params `{ sessionId, runId, artifactId, version(int>0..maxVersions), offset(int≥0), limit(int 1..512KiB) }`; result `{ dataBase64, mediaType(1..128), totalBytes(≤attachmentMaxBytes), nextOffset: int|null }`. Authorization: `sessionId` must be the run's `parentSessionId` ∧ `(artifactId, version)` has a completed journal row. Renderer-supplied ids are **never** used as paths directly.

### 4.10 `v4/conversation/workflowRunWorkspace` / `...NodeResult`

- **Workspace** (`workflow-workspace.ts:88-108`): params `{ sessionId, runId }`; result `{ nodes: WorkflowRunWorkspaceNode[] (≤2000), truncated? }`. Light row list (op/args/status/summary/timestamps, no body). Node schema `{ siteId(1..64), ordinal, kind: "world-read"|"world-run", op?(≤32), args?(≤16), inputTruncated?, status: "running"|"completed"|"failed", error?: {code(1..64), message(≤2000)}, summary?: {resultBytes, resultCount?, exitCode?, stdoutBytes?, stderrBytes?}, createdAt, updatedAt }`.
- **NodeResult** (`workflow-workspace.ts:114-141`): params `{ sessionId, runId, siteId, ordinal, maxBytes? (≤32KiB, default 32KiB, clamped gateway-side) }`; result `{ status, result?: unknown, error?, truncated: boolean, totalBytes }`. Body is shape-preserving bounded (strings truncated, arrays truncated from the tail, run stdout/stderr each truncated).

### 4.11 `v4/usage/stats` and `v4/conversation/usage`

- **usageStats** (`transport.ts:773-781`): params `{ range: "all"|"7d"|"30d", timeZone?: string }` (`APP_USAGE_RANGES`, `usage-stats.ts:162`); result = `appUsageSnapshotSchema` (`{ range, generatedAt, timeZone, source: "agent-db", summary, heatmap, dailyModelUsage, ... }`).
- **conversationUsage** (`transport.ts:785-805`): params `{ sessionId }`; result `{ sessionId, totalTokens, inputTokens, outputTokens, reasoningTokens, cacheCreationTokens, cacheReadTokens, modelRequestCount, modelErrorCount, inputBaselineBySource: Record<string, int≥0> }`.

### 4.12 Attachment read/stat queries

- `v4/attachment/read` (`transport.ts:955-1063`): params `{ sessionId, ref, target?, attachmentIndex?, offset(int≥0), limit(int 1..512KiB) }` — `target` and `attachmentIndex` must be provided together. Result `{ dataBase64, mediaType (image/* | video/* | application/pdf), totalBytes(≤attachmentPreviewMaxBytes), nextOffset: int|null }`.
- `v4/attachment/previewSource` (`transport.ts:977-1008`): params `{ sessionId, ref, target?, attachmentIndex?, clientMode }`; result = `{ kind: "local_path", path, mediaType: video/* }` or `{ kind: "chunked" }`. Remote/Web must return chunked; PDF always chunked.
- `v4/conversation/attachmentRead` (`transport.ts:1066-1114`): params `{ sessionId, ref, target, attachmentIndex, offset, limit }` (target/index **required**). Allows any authorized MIME (e.g. `text/plain`), unlike the media-only `attachmentRead`.
- `v4/conversation/attachmentStat` (`transport.ts:1117-1140`): params `{ sessionId, ref, target, attachmentIndex }`; result `{ mediaType, totalBytes(≤attachmentStatMaxBytes = 2 GiB), mtimeMs? }`. Metadata-only; can express real sizes above the transfer cap so "known oversize" is a deterministic block at selection time.

### 4.13 Subscription RPCs (not queries but part of the surface)

`v4/conversation/subscribe` (`transport.ts:422-447`, params extend `subscribeParamsSchema` with `connectionId`, `clientMode`, `workspace?`, `legacyTaskIds?` (≤200), `resumeThoughtLevel?`, `workflowRunDeltas?`), `resync` (`transport.ts:469-498`), `unsubscribe` (`transport.ts:500-507`); `v4/controller/*` (`controller.ts:254-293`). Public response is **ACK-only**; the initial snapshot/resume frame is emitted as an owned notification after the response line. Downstream notifications: `V4_NOTIFICATIONS` (`transport.ts:407-415`) — `v4/conversation/frame`, `v4/telemetry/event`, `v4/telemetry/local-ttft`, `v4/cua/permission-observation`.

---

## 5. Attachment upload flow

Wire methods (`transport.ts:370-373`): `v4/attachment/begin`, `v4/attachment/chunk`, `v4/attachment/commit`, `v4/attachment/abort`. Full-data `attachmentPut` is renderer-internal only and **never** a production RPC method (`transport.ts:807-809`); the wire only uses begin/chunk/commit/abort.

Limits (`PROTOCOL_V4_LIMITS`, `core.ts:64-100`):
| constant | value |
|---|---|
| `attachmentMaxBytes` | 20 MiB |
| `attachmentChunkMaxBytes` | 512 KiB (decoded bytes per chunk; also renderer→host channel and host→CLI NDJSON must prove ≤1 MiB per request) |
| `attachmentUploadMaxChunks` | 64 |
| `attachmentUploadMaxConcurrent` | 16 staged uploads |
| `attachmentUploadMaxStagedBytes` | 64 MiB |
| `attachmentUploadTtlMs` | 5 min (refreshed on begin/chunk) |
| `attachmentUnreferencedTtlMs` | 24 h |
| `attachmentPreviewMaxBytes` | `VIDEO_INPUT_MAX_BYTES` |
| `attachmentStatMaxBytes` | 2 GiB |
| `attachmentReadCacheTtlMs` / `attachmentReadCacheMaxBytes` | 30 s / `VIDEO_INPUT_MAX_BYTES` |

### 5.1 `attachmentBegin` (→ `attachmentBeginV4`)

Params `v4AttachmentBeginParamsSchema` (`transport.ts:832-861`, `.strict()`):
- `connectionId: string(min1)`
- `uploadId: string(min1,max128, regex /^[A-Za-z0-9][A-Za-z0-9._:-]*$/)`
- `sessionId: string(min1)`
- `fileName: string(1..255, regex /^[^\0\r\n]+$/)`
- `mime: string(3..255, regex type/subtype)`
- `totalBytes: int(0..20 MiB)`
- `totalChunks: int(0..64)`
- `checksum: string` — **format `sha256:<64 lowercase hex>`** (`v4AttachmentChecksumSchema`, `transport.ts:830`)
- refine: zero-byte upload must declare zero chunks (`(totalBytes === 0) === (totalChunks === 0)`).

Result `v4AttachmentBeginResultSchema` (`transport.ts:863-880`), discriminated on `state`:
- `{ uploadId, state: "staging", nextChunkIndex: int≥0 }`
- `{ uploadId, state: "committed", nextChunkIndex, ref }`

Semantics (`attachment-upload-registry.ts:95-144`): idempotent by `(connectionId, sessionId, uploadId)`; re-begin with identical metadata resumes (`nextChunkIndex = chunks.length`) and refreshes TTL; differing metadata → `fault.attachment.beginConflict`; ≥16 staged → `fault.attachment.tooManyUploads`; `totalBytes > totalChunks * 512KiB` → `fault.attachment.chunkCountInsufficient`. Gateway ensures the session is resumed (cold-resume) and requires `host.putSessionAttachment` (`fault.attachment.putUnsupported`) (`v4-gateway.ts:1971-1980`).

### 5.2 `attachmentChunk` (→ `attachmentChunkV4`)

Params (`transport.ts:903-929`, `.strict()`): `{ connectionId, uploadId, sessionId, chunkIndex: int≥0, dataBase64: string }`. `dataBase64` is base64 without a `data:` prefix; a custom `superRefine` computes decoded length and rejects >512 KiB (`code: "too_big"`) or invalid base64.
Result (`transport.ts:931-937`): `{ uploadId, nextChunkIndex: int≥0 }`.

Semantics (`attachment-upload-registry.ts:146-177`):
- unknown upload → `fault.attachment.uploadNotFound`; commit already in flight → `fault.attachment.commitInProgress`
- `chunkIndex < chunks.length` (replay): bytes must be identical, else `fault.attachment.chunkConflict`; identical replay is idempotent
- `chunkIndex > chunks.length` → `fault.attachment.chunkGap` (strictly sequential, no holes)
- `chunkIndex >= totalChunks` → `fault.attachment.tooManyChunks`
- empty chunk → `fault.attachment.emptyChunk`
- cumulative bytes > `totalBytes` → `fault.attachment.totalBytesExceeded`
- global staged bytes > 64 MiB → `fault.attachment.stagingCapacityExceeded`

### 5.3 `attachmentCommit` (→ `attachmentCommitV4`)

Params = `v4AttachmentTerminalParamsSchema` (`transport.ts:939-947`, `.strict()`): `{ connectionId, uploadId, sessionId }`.
Result `v4AttachmentCommitResultSchema` (`transport.ts:948`): `{ ref: string(min1) }` (strict).

Semantics (`attachment-upload-registry.ts:179-270`): already-committed → returns the same `ref` (idempotent). Commit is single-flight per upload (`commitPromise`). `commitStaged` requires `chunks.length === totalChunks` **and** `receivedBytes === totalBytes`, else `fault.attachment.uploadIncomplete`; recomputes SHA-256 over the concatenation and compares `sha256:<hex>` to the declared `checksum`, else `fault.attachment.checksumMismatch`; then calls `putSessionAttachment` and records the committed entry (TTL refreshed) returning `ref`.

### 5.4 `attachmentAbort` (→ `attachmentAbortV4`)

Params = same terminal schema (`transport.ts:950-952`). Result `{}` (strict, `transport.ts:952`).
Semantics (`attachment-upload-registry.ts:197-206`): if a commit is in flight it **awaits the commit and returns** (does not delete); otherwise deletes the staged upload (no error if absent — abort is idempotent).

### 5.5 Lifecycle / cleanup

`clearConnection(connectionId)` on `v4/connection/flow` state `closed` (`v4-gateway.ts:695-699`); `clearSession(sessionId)`; `pruneExpired()` on every begin/chunk/commit/abort and on a timer `min(30s, 5min)` (`v4-gateway.ts:684-687`). Staged uploads expire after 5 min idle; committed refs after 5 min (then unreferenced artifacts after 24 h).

### 5.6 Attachment fault codes

`ZCODE_ATTACHMENT_FAULT_CODES` (`attachment-faults.ts:11-34`): `fault.attachment.statUnsupported`, `readUnsupported`, `statNotFile`, `shareStatNotAuthorized`, `shareReadNotAuthorized`, `shareStatConnectionUntrusted`, `shareReadConnectionUntrusted`, `shareStatNotFound`, `shareStatTooLarge`, `previewTooLarge`, `previewNotMedia`. Transported as `error.data.code`; `readZCodeAttachmentFaultCode` reads same-process `code`, JSON-RPC `error.data.code`, or a legacy exact-message fallback (no fuzzy matching).

---

## 6. Gotchas

1. **Idempotency key = `(sessionId, commandId)`, not `clientId`.** Retries must reuse the same `commandId`; `clientId` only attributes the submitter. A retry within the same process (or after a durable lookup) returns `duplicate` with the cached final `result` — including for `forkAssistant`/`createSession`, where the in-flight duplicate awaits the shared final promise so the child `sessionId` is always present.
2. **A rejected command returns an ACK, it does not throw at the protocol level.** `v4/command` always resolves to a `CommandAck`; `status: "rejected"`/`"stale"`/`"failed"` carry `reasonCode` + `message`. Client-side `assertV4CommandAckOk` is what converts them into exceptions.
3. **`failed` is terminal.** `retryAck` never rewrites a `failed` ACK into `duplicate` (`command-inbox.ts:446-449`).
4. **Stale / CAS resync.** CAS commands must send `baseRevision` (and `baseLogEpoch` for row-targeting). On `stale`, the ACK's `revisionAtDecision` is the server's **current** revision — retry with that value and a **new** `commandId` (stale decisions are not recorded in the idempotency table). Host services probe with `baseRevision = 0`, up to 4 attempts (`zcodeV4HostCommand.ts:113-158`).
5. **Epoch before revision.** `baseLogEpoch` is checked before `baseRevision`; rowId is only meaningful inside one epoch (rewind/fork/rebuild renumber rows). A mismatched epoch is `proto.staleLogEpoch`.
6. **Row targeting requires both `rowId` and `entityId`** (`conversationRowTargetSchema`), and both must resolve in the same materialization; the action availability is read from `row.actions` — handlers/previews must not recompute it from position/phase/text (`product-projection.ts:757-761`). Unresolvable → `proto.staleTarget`; resolvable but action disabled → `guard.actionUnavailable`.
7. **`expectedHeldQueueItemIds` / `heldQueueDisposition`.** When `inputRouting.mode === "choice"` (held = `completed + queue>0 + autoDrain=false`), `sendText`/`sendGoalCommand` **must** carry `heldQueueDisposition`; otherwise `heldQueueDispositionRequired`. `expectedHeldQueueItemIds` is the set the user saw when the confirmation opened; if the queue changed on another device (different length, duplicate ids, or a missing id) → `guard.heldQueueConfirmationStale`. `clearQueueAndSend` empties the queue before starting; `keepQueueAndSend` keeps it. This check applies on the `startNow` path too — a single message's delivery must not bypass an existing queue's user arbitration (`session-flow.ts:224-249`).
8. **`requestedDelivery` vs session `followupMode`.** `requestedDelivery: "startNow"` is a per-input override (modifier+Enter); it does not persist and does not change the session's followup mode. `startNow` uses a foreground promotion lease `send-now:<commandId>` and Core `requireIdle`.
9. **`inputRouting` modes:** `startNow | enqueue | guide | reject | choice` (`snapshot.ts:163-170`). `reject` always carries `reasonCode`; `enqueue`/`guide`/`choice` may carry one (e.g. guide ineligibility fallback). `guide` with attachments records `fallbackReasonCode: "guide.attachmentsUnsupported"`. Absence of a projection → `null`, treated as non-held (`types.ts:108-115`).
10. **`inputAccepted` does not imply `messageId`.** Core admission ACK does not wait for projection commit; `messageId` is back-filled later. A separate result type `inputDisposition` (`{ delivery }`) exists because a restart that discarded a command cannot be distinguished by the client from a still-pending `startNow`; delivery comes from the persisted `session_input` fact, not from guessing the current UI phase (`command.ts:420-426`).
11. **`createSession` is the only command allowed `sessionId: null`**; it is the only command in the global idempotency bucket. Its `config` is a request override, not a snapshot partial (do not reuse `sessionConfigStateSchema.partial()`).
12. **`selection_side_chat` restriction.** For a session whose `taskType === "selection_side_chat"`, these commands are rejected with `guard.selectionSideChatRestrictedCommand` (`executor.ts:10-27`): `sendGoalCommand, pauseGoal, resumeGoal, editUserQuery, retryTurn, forkAssistant, discardSharedContext`.
13. **`deleteSession` is a close, not a delete** — history remains in the message store. Missing host capability makes it `failed`, never a silent success.
14. **`resolveInteraction` is first-come-first-served.** A late/duplicate answer is idempotent (`noop` / `proto.alreadyResolved`), never `failed`, and does not require the session record to exist. `snoozeInteractionAutoResolution` likewise.
15. **Workspace hook review commands carry `sessionId` both in the envelope and in the payload**, and they must match, else `workspace_hooks_snapshot_mismatch`. `bundleDigest` / `hookDeclarationDigests` are strict `^[a-f0-9]{64}$`.
16. **Workflow-run commands are revision-free by design** (`cancelBackgroundWork`, `resumeWorkflowRun`, `startSavedWorkflow`, `amendWorkflowRunSettings`). Do not add `baseRevision`; a fake CAS failure would only cause harm. `workId ≡ runId ≡ taskId` (one identity across cancel/resume/settings).
17. **`startSavedWorkflow` / `amendWorkflowRunSettings` / `resumeWorkflowRun` may return `accepted` with `result`** carrying `runId` + `toolCallId` (`launch-<uuid>` / `settings-<uuid>`) plus optional `supersededRunId`; those are the join keys to the new run's card/detail page.
18. **Read queries are not commands.** `workflowRunEvents`, `workflowRuns`, `workflowRunArtifacts*`, `workflowRunWorkspace`, `workflowRunNodeResult` deliberately carry **no** `atSeq`/`atLogEpoch` (they read the journal, whose cursor never expires). `rowsRange`, `plans`, `fileChanges`, `fileRewindPreview` **do** carry the epoch/revision staleness guard; discard the whole page when `atLogEpoch` ≠ the store's current epoch.
19. **`fileChanges` / `fileRewindPreview` require a `turnHeader` row target with `fileChanges` and `canRewindFiles === true`**, plus exact `baseLogEpoch`/`baseRevision` — otherwise `proto.staleLogEpoch` / `proto.staleRevision` / `guard.actionUnavailable`.
20. **Attachment uploads are strictly sequential** (`chunkGap` on holes), replay-idempotent only if bytes are byte-identical, capped at 64 chunks × 512 KiB decoded, 16 concurrent uploads, 64 MiB global staging, 5-minute TTL. `commit` is single-flight; `abort` during a commit awaits it instead of cancelling. The declared checksum format is exactly `sha256:<64 lowercase hex>`.
21. **`compact` takes an empty payload** (`z.object({})`) and never CAS-checks; it is deduplicated via a `compactOperationLock` and a typed queue item. `pauseGoal`/`resumeGoal` also take empty payloads.
22. **Same-value switches are explicit noops, not silent accepts**: `switchModelConfig` / `switchCollaborationMode` → `noop` / `config.unchanged`. Returning `undefined` silently would be interpreted as `accepted`, which can produce a visible "clicked but nothing happened" desync.
23. **`clientMode` / `workflowRunDeltas` are connection-level facts injected by the trusted host**, not per-request client choices; renderer callers omit `clientMode` on `rowsRange`. `clientHello.capabilities` is `.strict()` and the `workflowRunDeltas` declaration is one-directional (only declare it if the Host's hello advertised it), otherwise an old Host fails the whole `clientHello` parse (`transport.ts:79-85`).
24. **`issuedAt` is telemetry only**; the server never arbitrates on the client clock. Clock calibration is done via `commands/query` with `clock: true` (sessionId must be `null`).
25. **Idempotency table is bounded**: settled entries per session ≤ `idempotencyTablePerSession = 512`; in-flight and pinned live-input entries are never evicted. A command evicted from the settled LRU can still be found via the durable lookups (`transcript` → `timeline` → `child` → `discarded`), so `queryCommands` may legitimately return a `discarded` recovery fact rather than `"unknown"`.
