#!/usr/bin/env node
/** Focused: connectivity test against an ENABLED model on a throwaway provider (dummy key → real network failure shape). */
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const link = parseRemoteLink(
  `https://zcode.z.ai/remote/v4?sid=${process.env.SID}&hash=${encodeURIComponent(process.env.HASH)}&t=0&mid=${process.env.MID}`,
);

async function call(client, method, args) {
  return Promise.race([
    client.call('provider-settings', method, ...args),
    new Promise((_, rej) => setTimeout(() => rej(new Error('timeout 90s')), 90000)),
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

  const base = await call(client, 'getView', []);
  const baselineOrder = base.providerOrder;
  const probeId = (await call(client, 'createPersonalProvider', [{ providerName: `probe-conn-${Date.now().toString(36)}` }])).providerId;
  try {
    // 先 overlay 有效配置（指向必连不通的本地端口），再加启用模型
    await call(client, 'savePersonalProviderOverlay', [probeId, {
      access: { type: 'api-key', apiKey: 'sk-probe-dummy-000000000000' },
      api: { type: 'openai-chat-completions', baseUrl: 'http://127.0.0.1:9/v1' },
    }]);
    const v = await call(client, 'addPersonalModel', [probeId, 'conn-test-model', { enabled: true }, true]);
    const p = (v.providers ?? []).find((x) => x.providerId === probeId);
    const model = (p?.models ?? []).find((m) => m.modelId === 'conn-test-model');
    console.log('model added, enabled=', model?.enabled, 'executable=', p?.executable);
    await sleep(2000);
    const c = await call(client, 'testModelConnectivity', [{ workspacePath: ws, providerId: probeId, modelId: 'conn-test-model' }]);
    console.log('connectivity result:', JSON.stringify(c));
  } catch (e) {
    console.log('err:', e.message.slice(0, 400));
  } finally {
    await call(client, 'deletePersonalProvider', [probeId]).catch(() => {});
    const fin = await call(client, 'getView', []);
    console.log('order restored:', JSON.stringify(fin.providerOrder) === JSON.stringify(baselineOrder));
  }
  transport.close();
  await sleep(300);
}
main().catch((e) => { console.error('FATAL', e); process.exit(1); });
