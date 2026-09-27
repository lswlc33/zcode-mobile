#!/usr/bin/env node
/**
 * Live CRUD-lifecycle probe for the `provider-settings` channel.
 *
 * Creates a THROWAWAY personal provider, exercises every mutation on it
 * (add/rename/enable/model draft/reorder/overlay/connectivity), then deletes
 * it and verifies the registry is back to baseline. The user's real providers
 * are never touched: every mutation targets the probe id, and the final
 * getView must match the baseline providerOrder or the script says so loudly.
 *
 * Usage: SID=… HASH=… MID=… node probe-provider-crud.mjs
 */

import {
  RelayTransport, ChannelClient, parseRemoteLink,
  decodeMessage, ResponseType,
} from './zcode-client.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(...a);

const link = parseRemoteLink(
  `https://zcode.z.ai/remote/v4?sid=${process.env.SID}&hash=${encodeURIComponent(process.env.HASH)}&t=0&mid=${process.env.MID}`,
);

const WORKSPACE = 'E:\\open_trae_m';
const PROBE_NAME = `probe-crud-${Date.now().toString(36)}`;

/** Structural summary only — never print provider configs (they carry keys). */
function summarize(view) {
  if (!view || typeof view !== 'object') return view;
  return {
    revision: view.revision,
    providerOrder: view.providerOrder,
    providers: (view.providers ?? []).map((p) => ({
      providerId: p.providerId,
      providerName: p.providerName,
      enabled: p.enabled,
      executable: p.executable,
      models: (p.models ?? []).map((m) => ({ modelId: m.modelId, kind: m.kind, enabled: m.enabled, selectable: m.selectable })),
      issueCount: (p.issues ?? []).length,
    })),
    templateCount: (view.providerTemplates ?? []).length,
  };
}

function pickProbe(view, providerId) {
  return (view.providers ?? []).find((p) => p.providerId === providerId) ?? null;
}

async function main() {
  const transport = new RelayTransport(link, { log: () => {} });
  const client = new ChannelClient(transport, { log: () => {} });
  const CH = 'provider-settings';

  const results = [];
  async function call(method, args, note) {
    const entry = { method, args, note };
    try {
      const res = await Promise.race([
        client.call(CH, method, ...args),
        new Promise((_, rej) => setTimeout(() => rej(new Error('timeout 20s')), 20000)),
      ]);
      entry.ok = true;
      entry.summary = summarize(res);
      entry.raw = res;
      log(`  ✓ ${method}${note ? ` — ${note}` : ''}`);
    } catch (e) {
      entry.ok = false;
      entry.error = e.message;
      log(`  ✗ ${method} — ${e.message}`);
    }
    results.push(entry);
    return entry;
  }

  log('── connect ──');
  await transport.connect();
  const boot = await transport.bootstrap();
  const ws = boot.result?.initialViewState?.activeWorkspaceKey ?? WORKSPACE;
  await transport.openBridge(ws, {});
  const deadline = Date.now() + 8000;
  while (!client.initialized && Date.now() < deadline) await sleep(100);
  log(`  bridge ready, ws=${ws}`);

  // ── baseline ────────────────────────────────────────────────────────────
  log('── baseline getView ──');
  const base = await call('getView', []);
  const baselineOrder = base.raw?.providerOrder ?? [];
  const baselineRevision = base.raw?.revision;
  log(`  revision=${baselineRevision} order=${JSON.stringify(baselineOrder)}`);
  if (baselineOrder.some((id) => id.startsWith('probe-'))) {
    log('  !! a stale probe provider exists — aborting to avoid double-probe');
    transport.close();
    return;
  }

  let probeId = null;

  try {
    // ── create ────────────────────────────────────────────────────────────
    log('── createPersonalProvider ──');
    const created = await call('createPersonalProvider', [{ providerName: PROBE_NAME }], 'named probe');
    probeId = created.raw?.providerId ?? created.raw?.view?.providerOrder?.find((id) => !baselineOrder.includes(id));
    log(`  probeId=${probeId}`);
    if (!probeId) throw new Error('no providerId returned — cannot continue safely');

    // also probe the no-input variant on a second throwaway
    const created2 = await call('createPersonalProvider', [], 'no-arg variant');
    const probe2Id = created2.raw?.providerId;
    log(`  probe2Id=${probe2Id}`);

    const v1 = await call('getView', [], 'verify both probes present');
    log(`  probe in order: ${v1.raw?.providerOrder?.includes(probeId)}, probe2 in order: ${v1.raw?.providerOrder?.includes(probe2Id)}`);

    // ── model CRUD on probe ───────────────────────────────────────────────
    log('── addPersonalModel ──');
    await call('addPersonalModel', [probeId, 'probe-model', { enabled: true }, true], 'useRecommendedConfig=true');
    await call('renamePersonalModel', [probeId, 'probe-model', 'probe-model-r2']);
    await call('setPersonalModelEnabled', [probeId, 'probe-model-r2', false]);
    await call('resolveModelConfig', [{ providerId: probeId, modelId: 'probe-model-r2' }]);
    await call('reorderPersonalModels', [probeId, ['probe-model-r2']]);

    // ── overlay (replace provider config) ─────────────────────────────────
    log('── savePersonalProviderOverlay ──');
    const cur = await call('getView', [], 'fetch probe current config');
    const probeView = pickProbe(cur.raw, probeId);
    if (probeView?.effectiveConfig) {
      const overlay = JSON.parse(JSON.stringify(probeView.effectiveConfig));
      overlay.providerName = `${PROBE_NAME}-renamed`;
      await call('savePersonalProviderOverlay', [probeId, overlay], 'rename via overlay (effectiveConfig round-trip)');
    } else {
      log('  (no effectiveConfig on probe view — skipped)');
    }

    // ── connectivity test (dummy → expected failure, still proves the path)
    log('── testModelConnectivity ──');
    await call('testModelConnectivity', [{ workspacePath: ws, providerId: probeId, modelId: 'probe-model-r2' }], 'dummy key, expect failure result');

    // ── reorder providers (probe to front) ────────────────────────────────
    log('── reorderPersonalProviders ──');
    await call('reorderPersonalProviders', [[probeId, ...(baselineOrder.filter((id) => id !== probeId && id !== probe2Id))]]);

    // ── model draft (CAS via basedOnRevision) ─────────────────────────────
    log('── savePersonalModelDraft ──');
    const cur2 = await call('getView', [], 'fetch revision for draft');
    await call('savePersonalModelDraft', [{
      providerId: probeId,
      originalModelId: 'probe-model-r2',
      nextModelId: 'probe-model-r3',
      personalConfig: { enabled: true },
      basedOnRevision: cur2.raw?.revision,
    }]);

    // ── teardown ──────────────────────────────────────────────────────────
    log('── teardown ──');
    await call('deletePersonalModel', [probeId, 'probe-model-r3']);
    if (probe2Id) await call('deletePersonalProvider', [probe2Id]);
    await call('deletePersonalProvider', [probeId]);
  } catch (e) {
    log(`  !! step failed: ${e.message} — entering cleanup`);
  } finally {
    if (probeId) {
      // idempotent best-effort cleanup of anything left behind
      const after = await client.call(CH, 'getView').catch(() => null);
      for (const id of after?.providerOrder ?? []) {
        if (String(id).startsWith('probe-')) {
          log(`  cleanup: deleting leftover ${id}`);
          await client.call(CH, 'deletePersonalProvider', id).catch((e) => log(`  cleanup failed for ${id}: ${e.message}`));
        }
      }
    }
  }

  // ── verify registry restored ────────────────────────────────────────────
  log('── final getView ──');
  const fin = await call('getView', [], 'must match baseline');
  const finalOrder = fin.raw?.providerOrder ?? [];
  const same = JSON.stringify(finalOrder) === JSON.stringify(baselineOrder);
  log(`  order restored: ${same}`);
  if (!same) {
    log(`  baseline: ${JSON.stringify(baselineOrder)}`);
    log(`  final:    ${JSON.stringify(finalOrder)}`);
  }

  // structural result table (no secrets: ids/revisions/errors only)
  console.log('\n── result table ──');
  for (const r of results) {
    const line = { method: r.method, ok: r.ok };
    if (!r.ok) line.error = r.error;
    if (r.raw && typeof r.raw === 'object') {
      line.revision = r.raw.revision;
      line.returnedProviderId = r.raw.providerId;
      if (r.raw.connectivity) line.connectivity = r.raw.connectivity;
      if (r.method === 'testModelConnectivity') line.connectivityResult = r.raw;
      if (r.method === 'resolveModelConfig') {
        line.resolution = {
          issues: (r.raw.issues ?? []).map((i) => i.message ?? i.code ?? String(i)).slice(0, 3),
          inheritedKeys: Object.keys(r.raw.inheritedConfig ?? {}),
          effectiveKeys: Object.keys(r.raw.effectiveConfig ?? {}),
        };
      }
    }
    console.log(JSON.stringify(line));
  }

  transport.close();
  await sleep(300);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
