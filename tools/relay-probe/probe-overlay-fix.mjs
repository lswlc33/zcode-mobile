#!/usr/bin/env node
/** Focused re-test: savePersonalProviderOverlay with a schema-valid minimal config. */
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const link = parseRemoteLink(
  `https://zcode.z.ai/remote/v4?sid=${process.env.SID}&hash=${encodeURIComponent(process.env.HASH)}&t=0&mid=${process.env.MID}`,
);

async function call(client, method, args) {
  const res = await Promise.race([
    client.call('provider-settings', method, ...args),
    new Promise((_, rej) => setTimeout(() => rej(new Error('timeout 20s')), 20000)),
  ]);
  return res;
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
  const probeId = (await call(client, 'createPersonalProvider', [{ providerName: `probe-overlay-${Date.now().toString(36)}` }])).providerId;
  console.log('probeId=', probeId);

  // 修正后的 overlay：纯 config 对象，无 providerName（它在规则层）
  const overlay = {
    access: { type: 'api-key', apiKey: 'sk-probe-dummy-key-000000000000' },
    api: { type: 'openai-chat-completions', baseUrl: 'http://127.0.0.1:9/v1' },
  };
  try {
    const v = await call(client, 'savePersonalProviderOverlay', [probeId, overlay]);
    console.log('✓ savePersonalProviderOverlay revision=', v.revision);
    const after = await call(client, 'getView', []);
    const p = (after.providers ?? []).find((x) => x.providerId === probeId);
    console.log('probe after overlay:', JSON.stringify({
      executable: p?.executable,
      hasPersonalConfig: !!p?.personalConfig,
      accessType: p?.personalConfig?.access?.type,
      apiType: p?.personalConfig?.api?.type,
      baseUrl: p?.personalConfig?.api?.baseUrl,
      issueCount: (p?.issues ?? []).length,
    }));
    // 连通性测试（指向 127.0.0.1:9 必然连不通 → 验证失败结果形状）
    const c = await call(client, 'testModelConnectivity', [{ workspacePath: ws, providerId: probeId, modelId: (p?.models ?? [])[0]?.modelId ?? 'gpt-4o' }]);
    console.log('connectivity:', JSON.stringify(c).slice(0, 300));
  } catch (e) {
    console.log('✗ overlay:', e.message.slice(0, 400));
  } finally {
    await call(client, 'deletePersonalProvider', [probeId]).catch((e) => console.log('cleanup err', e.message));
    const fin = await call(client, 'getView', []);
    console.log('order restored:', JSON.stringify(fin.providerOrder) === JSON.stringify(baselineOrder));
  }
  transport.close();
  await sleep(300);
}
main().catch((e) => { console.error('FATAL', e); process.exit(1); });
