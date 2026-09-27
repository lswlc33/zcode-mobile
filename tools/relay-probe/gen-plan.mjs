/**
 * Generates plan-full.json: a zero-side-effect probe of the whole command
 * surface plus the read-only query/settings surface.
 *
 * Safety model: every command probe targets a NONEXISTENT sessionId, so the
 * host rejects it (proto.sessionNotFound) before any state can change. Each
 * command is probed twice — once with a schema-valid payload and once with an
 * empty payload — so the pair distinguishes "command type unknown" from
 * "payload invalid" from "payload fine, session missing".
 *
 * createSession is the one exception: it is session-less, so it is only ever
 * probed with an INVALID payload, which fails validation and creates nothing.
 */
import { writeFileSync } from 'node:fs';

const WS = 'C:\\Users\\lswlc\\.zcode\\workspace\\default';
const REAL_SID = 'sess_8a893158-957e-4592-906e-e6f90c9fe92a';
const BOGUS_SID = 'sess_00000000-0000-0000-0000-000000000000';
const SHA = 'a'.repeat(64);

const target = { rowId: 2, entityId: 'msg_probe_entity' };
const hookTarget = {
  sessionId: BOGUS_SID,
  taskId: BOGUS_SID,
  runId: 'run-probe',
  workspaceIdentity: WS,
  bundleDigest: SHA,
  reviewFlowId: 'flow-probe',
  generation: 1,
  interactionId: 'interaction-probe',
};

/** command type -> a schema-valid payload (except createSession, see above). */
const VALID = {
  sendText: { text: 'probe' },
  sendGoalCommand: { text: 'probe' },
  stop: {},
  compact: {},
  forkAssistant: { target },
  applyFileRewind: { target },
  editUserQuery: { target, newText: 'probe' },
  retryTurn: { target },
  setAssistantFeedback: { target, feedback: 'like' },
  sendQueuedNow: { queueItemId: 'probe-queue-item' },
  editQueueItem: { queueItemId: 'probe-queue-item', newText: 'probe' },
  reorderQueueItem: { queueItemId: 'probe-queue-item', beforeQueueItemId: null },
  deleteQueueItem: { queueItemId: 'probe-queue-item' },
  setAutoDrain: { autoDrain: true },
  resolveInteraction: { interactionId: 'probe-interaction', answer: { action: 'decline' } },
  respondWorkspaceHookReview: {
    ...hookTarget,
    decision: { action: 'trust_selected', reviewItemIds: ['probe-item'] },
  },
  toggleWorkspaceHookReviewItem: { ...hookTarget, reviewItemId: 'probe-item', enabled: true },
  revokeWorkspaceHookTrust: {
    sessionId: BOGUS_SID,
    workspaceIdentity: WS,
    bundleDigest: SHA,
    hookDeclarationDigests: [SHA],
  },
  requestWorkspaceHookReview: {
    sessionId: BOGUS_SID,
    workspaceIdentity: WS,
    bundleDigest: SHA,
  },
  snoozeInteractionAutoResolution: { interactionId: 'probe-interaction' },
  switchModelConfig: { provider: 'probe-provider', model: 'probe-model', thought: 'high' },
  switchCollaborationMode: { mode: 'build' },
  setFollowupMode: { mode: 'queue' },
  pauseGoal: {},
  resumeGoal: {},
  cancelBackgroundWork: { workId: 'probe-work' },
  resumeWorkflowRun: { workId: 'probe-work' },
  startSavedWorkflow: { name: 'probe-workflow' },
  amendWorkflowRunSettings: { workId: 'probe-work' },
  renameSession: { title: 'probe-title' },
  deleteSession: {},
  discardSharedContext: { contextId: 'probe-context' },
  createSelectionSideSession: {},
  // createSession omitted from VALID on purpose — see file header.
};

const steps = [];
let n = 0;
const cmdId = (tag) => `probe-${tag}-${(++n).toString().padStart(3, '0')}`;

const envelope = (type, payload, sid) => ({
  commandId: cmdId(type),
  clientId: '<CLIENT_ID>',
  sessionId: sid,
  baseRevision: 999999999,
  type,
  payload,
  issuedAt: Date.now(),
});

const command = (type, payload, sid, note) => ({
  channel: 'zcode-agent',
  method: 'sendConversationCommandV4',
  args: [{ workspacePath: WS, envelope: envelope(type, payload, sid) }],
  note: note ?? `${type} (${sid === BOGUS_SID ? 'bogus session' : 'sessionless'})`,
});

// ── 1. read-only queries ────────────────────────────────────────────────────
steps.push(
  { note: '══ 1. READ-ONLY: model / provider / settings ══' },
  { channel: 'model-selection', method: 'getView', args: [{}], note: 'model catalog + reasoning levels' },
  { channel: 'provider-settings', method: 'getView', args: [{}], note: 'provider templates + personal providers' },
  { channel: 'setting', method: 'get', args: [], note: 'global app settings' },
  { channel: 'zcode-session', method: 'listSessions', args: [{ workspacePath: WS }], note: 'legacy session list' },
  { channel: 'zcode-agent', method: 'listSessions', args: [{ workspacePath: WS }], note: 'agent session list' },
  { channel: 'window-controller', method: 'listTaskList', args: [{ workspacePath: WS }], note: 'window task list (search/limit)' },
  { channel: 'hooks', method: 'loadHooks', args: [{ workspacePath: WS }], note: 'workspace hooks' },
  { channel: 'mcp-sync', method: 'listWorkspaceMcpServerStatuses', args: [{ workspacePath: WS }], note: 'MCP server statuses' },
);

// ── 2. subscriptions (the "session list" live feed) ─────────────────────────
steps.push(
  { note: '══ 2. SESSIONS INDEX + WORKSPACE CONFIG ══' },
  { listen: 'onDynamicSessionsIndexFrame', arg: { workspacePath: WS }, note: 'listen before subscribe' },
  { sleep: 200 },
  { channel: 'zcode-agent', method: 'subscribeSessionsIndexV4', args: [{ workspacePath: WS }], note: 'session list snapshot/deltas' },
  { sleep: 1500 },
  { listen: 'onDynamicWorkspaceConfigFrame', arg: { workspacePath: WS }, note: 'listen before subscribe' },
  { sleep: 200 },
  { channel: 'zcode-agent', method: 'subscribeWorkspaceConfigV4', args: [{ workspacePath: WS }], note: 'workspace config snapshot' },
  { sleep: 1500 },
);

// ── 3. conversation read queries ────────────────────────────────────────────
steps.push(
  { note: '══ 3. CONVERSATION READ QUERIES ══' },
  { channel: 'zcode-agent', method: 'conversationRowsRangeV4', args: [{ workspacePath: WS, sessionId: REAL_SID, limit: 20 }], note: 'history paging (loadOlder)' },
  { channel: 'zcode-agent', method: 'conversationRowsRangeV4', args: [{ workspacePath: WS, sessionId: REAL_SID, beforeRowId: 2, limit: 5 }], note: 'history paging with beforeRowId cursor' },
  { channel: 'zcode-agent', method: 'conversationPlansV4', args: [{ workspacePath: WS, sessionId: REAL_SID }], note: 'ExitPlanMode catalog' },
  { channel: 'zcode-agent', method: 'conversationWorkflowRunsV4', args: [{ workspacePath: WS, sessionId: REAL_SID, limit: 5 }], note: 'workflow run enumeration' },
  { channel: 'zcode-agent', method: 'conversationWorkflowRunEventsV4', args: [{ workspacePath: WS, sessionId: REAL_SID, runId: 'run-probe', limit: 5 }], note: 'workflow run event log paging' },
  { channel: 'zcode-agent', method: 'conversationFileChangesV4', args: [{ workspacePath: WS, sessionId: REAL_SID, target, baseRevision: 6, baseLogEpoch: 'probe' }], note: 'file changes for a row (needs CAS)' },
  { channel: 'zcode-agent', method: 'conversationFileRewindPreviewV4', args: [{ workspacePath: WS, sessionId: REAL_SID, target, baseRevision: 6, baseLogEpoch: 'probe' }], note: 'file rewind preview' },
  { channel: 'zcode-agent', method: 'backgroundBashOutputV4', args: [{ workspacePath: WS, sessionId: REAL_SID, workId: 'probe-work' }], note: 'background bash output' },
  { channel: 'zcode-agent', method: 'queryConversationCommandsV4', args: [{ workspacePath: WS, commands: [{ sessionId: REAL_SID, commandId: 'probe-does-not-exist' }] }], note: 'idempotency probe for a lost ack' },
  { channel: 'zcode-agent', method: 'conversationAttachmentStatV4', args: [{ workspacePath: WS, sessionId: REAL_SID, ref: 'zcode-artifact://probe', target, attachmentIndex: 0 }], note: 'attachment metadata precheck' },
);

// ── 4. command surface: valid payload vs bogus session ──────────────────────
steps.push({ note: '══ 4a. COMMANDS with schema-valid payloads (bogus session) ══' });
for (const [type, payload] of Object.entries(VALID)) {
  steps.push(command(type, payload, BOGUS_SID));
}
steps.push(command('createSession', {}, null, 'createSession with empty payload -> ZodError, creates nothing'));

steps.push({ note: '══ 4b. COMMANDS with empty payloads (bogus session) -> required-field evidence ══' });
for (const type of Object.keys(VALID)) {
  steps.push(command(type, {}, BOGUS_SID, `${type} empty payload`));
}

// ── 5. attachment upload ────────────────────────────────────────────────────
const body = Buffer.from('zcode relay probe payload\n', 'utf8');
const sha = 'sha256:' + (await import('node:crypto')).createHash('sha256').update(body).digest('hex');
const uploadId = 'probe-' + (await import('node:crypto')).randomUUID();
steps.push(
  { note: '══ 5. ATTACHMENT UPLOAD (add file) ══' },
  {
    channel: 'zcode-agent',
    method: 'attachmentBeginV4',
    args: [{ workspacePath: WS, sessionId: REAL_SID, uploadId, fileName: 'probe.txt', mime: 'text/plain', totalBytes: body.length, totalChunks: 1, checksum: sha }],
    note: 'begin upload',
  },
  {
    channel: 'zcode-agent',
    method: 'attachmentChunkV4',
    args: [{ workspacePath: WS, sessionId: REAL_SID, uploadId, chunkIndex: 0, dataBase64: body.toString('base64') }],
    note: 'chunk 0 (base64)',
  },
  { channel: 'zcode-agent', method: 'attachmentCommitV4', args: [{ workspacePath: WS, sessionId: REAL_SID, uploadId }], note: 'commit -> zcode-artifact ref' },
);

// ── 6. teardown ─────────────────────────────────────────────────────────────
steps.push(
  { note: '══ 6. TEARDOWN ══' },
  { channel: 'zcode-agent', method: 'unsubscribeConversationV4', args: [{ workspacePath: WS, subscriptionId: '<CONV_SUB_ID>' }], note: 'close conversation subscription' },
  { channel: 'zcode-agent', method: 'unsubscribeSessionsIndexV4', args: [{ workspacePath: WS, subscriptionId: '<IDX_SUB_ID>' }], note: 'close sessions-index subscription' },
  { channel: 'zcode-agent', method: 'unsubscribeWorkspaceConfigV4', args: [{ workspacePath: WS, subscriptionId: '<WCS_SUB_ID>' }], note: 'close workspace-config subscription' },
);

writeFileSync(new URL('./plan-full.json', import.meta.url), JSON.stringify({ steps }, null, 2));
console.log(`plan-full.json written: ${steps.length} steps, ${Object.keys(VALID).length} command types`);
