/**
 * End-to-end test of the ZCode relay stack: handshake → bootstrap → bridge →
 * channel RPC → v4 conversation subscribe.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import {
  RelayTransport, ChannelClient, parseRemoteLink,
} from './zcode-client.mjs';

const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const CH_SESSION = 'zcode-session';
const CH_AGENT = 'zcode-agent';

async function main() {
  const raw = process.argv[2] ?? readFileSync('link.txt', 'utf8').trim();
  const link = parseRemoteLink(raw);

  const lines = [];
  const rec = (...a) => { const s = a.join(' '); lines.push(s); log(s); };

  const transport = new RelayTransport(link, { log: rec });
  const client = new ChannelClient(transport, { log: rec });
  const events = [];
  transport.onControl = (p) => {
    if (p.zcode_type && !['rpc-frame', 'rpc-frame-ack'].includes(p.zcode_type)) {
      rec(`  control ← ${p.zcode_type}`);
    }
  };

  rec('── handshake ───────────────────────────────────');
  await transport.connect();

  rec('');
  rec('── bootstrap ───────────────────────────────────');
  const boot = await transport.bootstrap();
  const res = boot.result ?? {};
  const workspaces = res.workspaces ?? [];
  const tasks = res.tasks ?? [];
  rec(`  ${workspaces.length} workspaces, ${tasks.length} tasks, desktop=${res.desktopAppVersion}`);

  const activeKey = res.initialViewState?.activeWorkspaceKey;
  const activeTask = res.initialViewState?.activeTaskId;
  const ws = workspaces.find((w) => (w.workspacePath ?? w.workspaceKey) === activeKey) ?? workspaces[0];
  const key = ws?.workspacePath ?? ws?.workspaceKey;
  rec(`  bridging ${key} (task ${activeTask})`);

  rec('');
  rec('── bridge ──────────────────────────────────────');
  const bridge = await transport.openBridge(key, { taskId: activeTask });
  rec(`  bridge=${JSON.stringify(bridge)}`);

  rec('');
  rec('── channel init ────────────────────────────────');
  for (let i = 0; i < 50 && !client.initialized; i++) await sleep(100);
  rec(`  initialized=${client.initialized}`);

  // ── try to find which channel hosts the v4 methods ──
  rec('');
  rec('── probing channels for v4 surface ─────────────');

  const probeMethods = async (channel) => {
    try {
      const hello = await client.call(channel, 'helloConversationV4');
      rec(`  ✓ ${channel}.helloConversationV4 →`);
      rec(`      ${JSON.stringify(hello).slice(0, 400)}`);
      return hello;
    } catch (e) {
      rec(`  ✗ ${channel}.helloConversationV4 → ${e.message.slice(0, 200)}`);
      return null;
    }
  };

  let hello = await probeMethods(CH_AGENT);
  if (!hello) hello = await probeMethods(CH_SESSION);

  if (hello) {
    rec('');
    rec('── clientHello ─────────────────────────────────');
    const clientHello = {
      kind: 'clientHello',
      protocolVersion: 3,
      clientId: `zcode-mobile-${crypto.randomUUID()}`,
      clientKind: 'mobileApp',
      appVersion: '1.0.0',
      capabilities: { workspaceHookReviewUi: true },
    };
    try {
      await client.call(CH_AGENT, 'initializeConversationV4', clientHello);
      rec('  ✓ initializeConversationV4 accepted');
    } catch (e) {
      rec(`  ✗ initializeConversationV4 → ${e.message.slice(0, 300)}`);
    }

    rec('');
    rec('── subscribe conversation ──────────────────────');
    for (const paramShape of [
      { workspaceKey: key, sessionId: activeTask },
      { workspacePath: key, sessionId: activeTask },
      { workspaceKey: key, workspacePath: key, sessionId: activeTask },
    ]) {
      try {
        const sub = await client.call(CH_AGENT, 'subscribeConversationV4', paramShape);
        rec(`  ✓ subscribe accepted with ${JSON.stringify(Object.keys(paramShape))}`);
        rec(`      ${JSON.stringify(sub).slice(0, 500)}`);
        writeFileSync('subscribe-result.json', JSON.stringify(sub, null, 2));
        break;
      } catch (e) {
        rec(`  ✗ ${JSON.stringify(Object.keys(paramShape))} → ${e.message.slice(0, 200)}`);
      }
    }

    rec('');
    rec('── listening for frames (8s) ───────────────────');
    await sleep(8000);
    rec(`  channel events received: ${events.length}`);
  }

  transport.close();
  writeFileSync('run-log.txt', lines.join('\n'));
}

main().then(() => process.exit(0), (e) => {
  console.error(`\nFATAL: ${e.stack ?? e.message}`);
  process.exit(1);
});
