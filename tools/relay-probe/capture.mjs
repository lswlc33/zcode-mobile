#!/usr/bin/env node
/**
 * ZCode relay full-wire capture harness.
 *
 * Records EVERY byte-level message in both directions (control plane JSON +
 * decoded channel payloads), then runs a scripted plan of channel RPC calls so
 * the request/response shapes of each method can be documented from real
 * traffic rather than guessed.
 *
 * Usage:
 *   node capture.mjs --url "<link>" [--plan plan.json] [--out transcript.json]
 *
 * The relay link is a credential: the `hash` param is redacted in all output.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  RelayTransport, ChannelClient, parseRemoteLink,
  decodeMessage, RequestType, ResponseType,
} from './zcode-client.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── args ───────────────────────────────────────────────────────────────────

function argOf(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const rawLink = argOf('url') ?? readFileSync(resolve(HERE, 'link.txt'), 'utf8').trim();
const planPath = argOf('plan');
const outPath = argOf('out') ?? resolve(HERE, 'transcript.json');
const workspaceOverride = argOf('workspace');
const sessionOverride = argOf('session');

const link = parseRemoteLink(rawLink);

/** Redact the credential before anything is written to disk. */
function redact(text) {
  if (typeof text !== 'string') return text;
  return text
    .replace(/"hash":"[^"]*"/g, '"hash":"<REDACTED>"')
    .replace(/(hash=)[^&"\s]*/g, '$1<REDACTED>')
    .replace(/"proof":"[^"]*"/g, '"proof":"<REDACTED>"');
}

/**
 * Deep-scrub provider secrets out of a decoded value.
 *
 * `model-selection.getView` and `provider-settings.getView` return the user's
 * real provider credentials in cleartext; a raw transcript is a credential
 * dump, so every captured object passes through this before it is written.
 */
function scrubSecrets(value) {
  if (typeof value === 'string') {
    // "sk-…" style keys, bare or embedded in a larger blob.
    return value.replace(/\bsk-[A-Za-z0-9_\-]{12,}/g, '<REDACTED-KEY>');
  }
  if (Array.isArray(value)) return value.map(scrubSecrets);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (/^(apiKey|authorization|x-api-key|api_key|secret|password|token)$/i.test(k)) {
        out[k] = '<REDACTED-KEY>';
      } else {
        out[k] = scrubSecrets(v);
      }
    }
    return out;
  }
  return value;
}

// ── wire recorder ──────────────────────────────────────────────────────────
// Patch the global WebSocket so the auth exchange (which happens before
// RelayTransport.connect() resolves) is captured too.

const wire = [];
const NativeWebSocket = globalThis.WebSocket;

class RecordingWebSocket extends NativeWebSocket {
  constructor(url, protocols) {
    super(url, protocols);
    wire.push({ dir: 'sys', ts: Date.now(), text: `open ${redact(String(url))}` });
    this.addEventListener('message', async (ev) => {
      const text = typeof ev.data === 'string' ? ev.data : await ev.data.text();
      wire.push({ dir: 'in', ts: Date.now(), text: redact(text) });
    });
    this.addEventListener('close', (ev) => {
      wire.push({ dir: 'sys', ts: Date.now(), text: `close code=${ev.code} reason=${ev.reason || ''}` });
    });
  }
  send(data) {
    if (typeof data === 'string') {
      wire.push({ dir: 'out', ts: Date.now(), text: redact(data) });
    } else {
      wire.push({ dir: 'out', ts: Date.now(), text: `<binary ${data?.length ?? 0}B>` });
    }
    return super.send(data);
  }
}

// zcode-client.mjs resolves the bare `WebSocket` global at call time, so this
// must be installed before the transport connects.
globalThis.WebSocket = RecordingWebSocket;

// ── call log ───────────────────────────────────────────────────────────────

const calls = [];

// ── main ───────────────────────────────────────────────────────────────────

async function main() {
  const transport = new RelayTransport(link, { log: () => {} });
  const client = new ChannelClient(transport, { log: () => {} });

  const frames = [];   // decoded inbound channel payloads
  const events = [];   // decoded event fires
  const decodeErrors = [];

  // ChannelClient installs its own onChannelPayload in its constructor; chain
  // onto it rather than replacing it, or every RPC response is dropped.
  const sinkPayload = transport.onChannelPayload;
  transport.onChannelPayload = (buf, p) => {
    let header; let body;
    try {
      [header, body] = decodeMessage(buf);
      const rec = { ts: Date.now(), messageSeq: p?.messageSeq, header, type: header?.[0], id: header?.[1], body };
      if (header?.[0] === ResponseType.EventFire) events.push(rec);
      else frames.push(rec);
    } catch (e) {
      decodeErrors.push({ bytes: buf.length, head: buf.subarray(0, 16).toString('hex'), error: e.message });
    }
    return sinkPayload(buf, p);
  };
  const sinkControl = transport.onControl;
  transport.onControl = (p) => sinkControl(p);

  log('── handshake ──');
  await transport.connect();
  log(`  terminal_sid=${transport.terminalSid} pair_status=${JSON.stringify(transport.pairStatus)}`);

  log('── bootstrap ──');
  const boot = await transport.bootstrap();
  const bootRes = boot.result ?? {};
  const workspaces = bootRes.workspaces ?? [];
  const tasks = bootRes.tasks ?? [];
  const activeWs = workspaceOverride ?? bootRes.initialViewState?.activeWorkspaceKey ?? workspaces[0]?.path ?? workspaces[0]?.key;
  const activeTask = sessionOverride ?? bootRes.initialViewState?.activeTaskId ?? tasks[0]?.id;
  log(`  ${workspaces.length} workspace(s), ${tasks.length} task(s); active ws=${activeWs}`);

  log('── workspace bridge ──');
  const bridge = await transport.openBridge(activeWs, activeTask ? { taskId: activeTask } : {});
  log(`  bridge=${bridge.bridgeSessionId} gen=${bridge.bridgeGeneration}`);

  // Wait for the channel Initialize frame.
  const deadline = Date.now() + 8000;
  while (!client.initialized && Date.now() < deadline) await sleep(100);

  const CH = 'zcode-agent';

  /** Call a method and record request+response. */
  async function call(channel, method, args = [], note, timeoutMs = 15000) {
    const entry = { channel, method, args, note, ts: Date.now() };
    const before = wire.length;
    let timer;
    try {
      const res = await Promise.race([
        client.call(channel, method, ...args),
        new Promise((_, rej) => { timer = setTimeout(() => rej(new Error(`no response within ${timeoutMs}ms`)), timeoutMs); }),
      ]);
      entry.ok = true;
      entry.result = res;
    } catch (e) {
      entry.ok = false;
      entry.error = e.message;
    } finally {
      clearTimeout(timer);
    }
    entry.wireDelta = wire.slice(before).map((w) => ({ dir: w.dir, text: w.text }));
    calls.push(entry);
    log(`  ${entry.ok ? '✓' : '✗'} ${channel}.${method}${entry.ok ? '' : ` — ${entry.error}`}`);
    return entry;
  }

  // ── the v4 conversation handshake, captured verbatim ────────────────────
  log('── v4 handshake ──');

  await call(CH, 'helloConversationV4', [], 'capability negotiation');
  await sleep(200);

  const clientId = `capture-${Math.random().toString(36).slice(2, 10)}`;
  const hello = {
    kind: 'clientHello',
    protocolVersion: 3,
    clientId,
    clientKind: 'mobileApp',
    appVersion: '3.14.3',
    capabilities: { workspaceHookReviewUi: true },
  };
  await call(CH, 'initializeConversationV4', [hello], 'register client + capabilities');

  // Register the frame listener BEFORE subscribing, or the first snapshot is lost.
  const listenId = client.listen(CH, 'onDynamicConversationFrame', { workspacePath: activeWs }, (data) => {
    events.push({ ts: Date.now(), event: 'onDynamicConversationFrame', data });
  });
  log(`  listening on onDynamicConversationFrame id=${listenId}`);

  await sleep(300);

  if (activeTask) {
    await call(CH, 'subscribeConversationV4', [{ workspacePath: activeWs, sessionId: activeTask }], 'open the conversation');
    await sleep(1500);
  }

  // ── plan steps ──────────────────────────────────────────────────────────
  // Placeholders resolved from earlier responses so a plan can chain calls.
  const vars = { CLIENT_ID: clientId, WORKSPACE: activeWs, SESSION: activeTask ?? '' };
  const substitute = (v) =>
    typeof v === 'string'
      ? v.replace(/<([A-Z_]+)>/g, (m, k) => (k in vars ? String(vars[k]) : m))
      : Array.isArray(v)
        ? v.map(substitute)
        : v && typeof v === 'object'
          ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, substitute(x)]))
          : v;

  if (planPath) {
    const plan = JSON.parse(readFileSync(planPath, 'utf8'));
    log(`── plan: ${planPath} (${plan.steps.length} step(s)) ──`);
    for (const step of plan.steps) {
      if (step.sleep) { await sleep(step.sleep); continue; }
      if (step.note) log(`  ── ${step.note}`);
      if (step.note && !step.method && !step.listen) { calls.push({ kind: 'note', text: step.note }); continue; }
      if (step.listen) {
        const id = client.listen(step.channel ?? CH, step.listen, substitute(step.arg), (d) => {
          events.push({ ts: Date.now(), event: step.listen, data: d });
        });
        log(`  ▷ listen ${step.channel ?? CH}.${step.listen} id=${id}`);
        calls.push({ kind: 'listen', channel: step.channel ?? CH, method: step.listen, arg: substitute(step.arg), note: step.note });
        continue;
      }
      const entry = await call(step.channel ?? CH, step.method, substitute(step.args ?? []), step.note);
      const subId = entry.result?.ack?.subscriptionId;
      if (subId) {
        vars.SUB_ID = subId;
        if (step.method === 'subscribeConversationV4') vars.CONV_SUB_ID = subId;
        if (step.method === 'subscribeSessionsIndexV4') vars.IDX_SUB_ID = subId;
        if (step.method === 'subscribeWorkspaceConfigV4') vars.WCS_SUB_ID = subId;
      }
    }
  }

  await sleep(500);

  // ── dump ────────────────────────────────────────────────────────────────
  const transcript = {
    capturedAt: new Date().toISOString(),
    link: {
      deviceSid: link.deviceSid,
      deviceMid: link.deviceMid,
      deviceName: link.deviceName,
      appVersion: link.appVersion,
      hash: '<REDACTED>',
    },
    terminalSid: transport.terminalSid,
    pairStatus: transport.pairStatus,
    bridge,
    activeWorkspace: activeWs,
    activeSession: activeTask,
    bootstrap: bootRes,
    calls,
    frames,
    events,
    decodeErrors,
    wire,
  };
  writeFileSync(outPath, JSON.stringify(scrubSecrets(transcript), null, 2));
  log(`\n── transcript → ${outPath}`);
  log(`   ${wire.length} wire messages, ${calls.length} calls, ${frames.length} frames, ${events.length} events`);
  if (decodeErrors.length) log(`   ⚠ ${decodeErrors.length} decode error(s)`);

  transport.close();
  await sleep(300);
}

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
