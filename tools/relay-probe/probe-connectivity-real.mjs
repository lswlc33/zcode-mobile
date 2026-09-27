#!/usr/bin/env node
/** Focused: connectivity test SUCCESS shape — test one of the user's real enabled models (equivalent to clicking 测试 in settings). Read-only, no mutation. */
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const link = parseRemoteLink(
  `https://zcode.z.ai/remote/v4?sid=${process.env.SID}&hash=${encodeURIComponent(process.env.HASH)}&t=0&mid=${process.env.MID}`,
);

async function call(client, method, args, timeoutMs = 90000) {
  return Promise.race([
    client.call('provider-settings', method, ...args),
    new Promise((_, rej) => setTimeout(() => rej(new Error(`timeout ${timeoutMs}ms`)), timeoutMs)),
  ]);
}

async function main() {
  const transport = new RelayTransport(link, { log: () => {} });
  const client = new ChannelClient(transport, { log: () => {} });
  await transport.connect();
  const boot = await transport.bootstrap();
  const ws = boot.result?.initialViewState?.activeWorkspaceKey;
  await transport.openBridge(ws, {});
  const deadline = Date.now() + 8000;
  while (!client.initialized && Date.now() < deadline) await sleep(100);

  const view = await call(client, 'getView', []);
  // 选 preferred/effective selection 里的模型（当前实际在用的），其次第一个 enabled+executable
  const pref = view.effectiveSelection ?? view.preferredSelection ?? {};
  let providerId = pref.providerId;
  let modelId = pref.modelId;
  if (!providerId) {
    outer: for (const p of view.providers ?? []) {
      for (const m of p.models ?? []) {
        if (m.enabled && m.selectable) { providerId = p.providerId; modelId = m.modelId; break outer; }
      }
    }
  }
  console.log('testing provider=', providerId, 'model=', modelId);
  const c = await call(client, 'testModelConnectivity', [{ workspacePath: ws, providerId, modelId }]);
  console.log('connectivity:', JSON.stringify(c));
  transport.close();
  await sleep(300);
}
main().catch((e) => { console.error('FATAL', e); process.exit(1); });
