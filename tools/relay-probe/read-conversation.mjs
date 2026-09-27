/**
 * Full stack exercise: handshake → bootstrap → bridge → channel → clientHello
 * → subscribe → read the conversation snapshot.
 *
 * This is the reference behaviour the Kotlin client must reproduce.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';

const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const AGENT = 'zcode-agent';

async function main() {
  const raw = process.argv[2] ?? readFileSync('link.txt', 'utf8').trim();
  const link = parseRemoteLink(raw);

  const transport = new RelayTransport(link, { log });
  const client = new ChannelClient(transport, { log });
  transport.onControl = (p) => {
    if (p.zcode_type && !['rpc-frame', 'rpc-frame-ack', 'mobile-diagnostic'].includes(p.zcode_type)) {
      log(`  control ← ${p.zcode_type}`);
    }
  };

  log('── handshake ────────────────────────────────────');
  await transport.connect();

  log('\n── bootstrap ────────────────────────────────────');
  const boot = await transport.bootstrap();
  const res = boot.result ?? {};
  const activeKey = res.initialViewState?.activeWorkspaceKey;
  const activeTask = res.initialViewState?.activeTaskId;
  log(`  ${res.workspaces?.length ?? 0} workspaces, ${res.tasks?.length ?? 0} tasks`);

  log('\n── bridge ───────────────────────────────────────');
  const bridge = await transport.openBridge(activeKey, { taskId: activeTask });
  log(`  bridgeSessionId=${bridge.bridgeSessionId}`);

  log('\n── channel init ─────────────────────────────────');
  for (let i = 0; i < 50 && !client.initialized; i++) await sleep(100);
  log(`  initialized=${client.initialized}`);

  log('\n── v4 handshake ─────────────────────────────────');
  const hello = await client.call(AGENT, 'helloConversationV4');
  log(`  hello: clientMode=${hello.clientMode} profile=${hello.deliveryProfile} v=${hello.protocolVersion}`);
  await client.call(AGENT, 'initializeConversationV4', {
    kind: 'clientHello',
    protocolVersion: 3,
    clientId: `zcode-mobile-${crypto.randomUUID()}`,
    clientKind: 'mobileApp',
    appVersion: '1.0.0',
    capabilities: { workspaceHookReviewUi: true },
  });
  log('  clientHello accepted');

  // ── frame stream ──
  const frames = [];
  const dangling = new Map(); // logicalFrameId -> partial fragments
  client.listen(AGENT, 'onDynamicConversationFrame', { workspacePath: activeKey }, (candidate) => {
    if (!candidate) return;
    frames.push(candidate);
    log(`  ← wire kind=${candidate.kind} topic=${candidate.topic} sub=${candidate.subscriptionId} ord=${candidate.logicalFrameOrdinal}`);

    let frame = candidate.frame;
    if (candidate.kind === 'fragment') {
      const entry = dangling.get(candidate.logicalFrameId) ?? { parts: [], count: candidate.fragmentCount };
      entry.parts[candidate.fragmentIndex] = Buffer.from(candidate.dataBase64 ?? '', 'base64');
      dangling.set(candidate.logicalFrameId, entry);
      if (entry.parts.filter(Boolean).length < entry.count) return;
      try { frame = JSON.parse(Buffer.concat(entry.parts).toString('utf8')); }
      catch (e) { log(`    reassembly failed: ${e.message}`); return; }
    }
    if (!frame) return;

    const kind = frame.payload?.kind;
    if (kind === 'snapshot') {
      const snap = frame.payload.snapshot ?? {};
      const rows = snap.rows?.window ?? [];
      log(`    SNAPSHOT topic=${frame.topic} seq=${frame.fromSeq}..${frame.toSeq}`);
      log(`      sessionId=${snap.sessionId} phase=${snap.phase} totalRows=${snap.rows?.totalCount ?? rows.length}`);
      writeFileSync('snapshot.json', JSON.stringify(frame, null, 2));
      log(`      full frame -> snapshot.json (${JSON.stringify(frame).length} bytes)`);
      dumpRows(rows);
    } else if (kind === 'deltas') {
      log(`    DELTAS ${frame.payload.deltas?.length ?? 0} op(s)`);
    }
  });

  log('\n── subscribe ────────────────────────────────────');
  const sub = await client.call(AGENT, 'subscribeConversationV4', {
    workspacePath: activeKey, sessionId: activeTask,
  });
  log(`  ack: sub=${sub.ack?.subscriptionId} mode=${sub.ack?.mode} rows=${sub.ack?.openTiming?.snapshotRowCount}`);

  log('\n── collecting frames (10s) ──────────────────────');
  await sleep(10000);
  log(`\n  wire frames received: ${frames.length}`);

  transport.close();
}

function dumpRows(rows) {
  log(`      ${rows.length} row(s) in tail window:`);
  for (const row of rows.slice(-14)) {
    const t = row.type ?? row.kind ?? '?';
    let detail = '';
    const c = row.content ?? row;
    const text = c.text ?? c.summaryText ?? c.title ?? row.title ?? '';
    if (text) detail = String(text).replace(/\s+/g, ' ').slice(0, 90);
    else if (c.toolName) detail = `tool=${c.toolName}`;
    else detail = JSON.stringify(row).slice(0, 90);
    log(`        rowId=${String(row.rowId).padStart(4)} [${String(t).padEnd(18)}] ${detail}`);
  }
}

main().then(() => process.exit(0), (e) => {
  console.error(`\nFATAL: ${e.stack ?? e.message}`);
  process.exit(1);
});
