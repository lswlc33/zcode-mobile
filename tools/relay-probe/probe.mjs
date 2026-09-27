#!/usr/bin/env node
/**
 * ZCode relay protocol probe.
 *
 * Verifies the hosted relay handshake + control plane against a live
 * zcode.z.ai/remote/v4 link. Zero dependencies (Node >= 22).
 *
 * Usage:
 *   node probe.mjs --url "https://zcode.z.ai/remote/v4?sid=..&hash=..&t=..&mid=.."
 */

import { webcrypto } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const RELAY_WS = 'wss://zcode.z.ai/ws';
const ROLE = 'terminal';
const CLIENT_ID_PREFIX = 'zcode-mobile-probe';

// ── helpers ────────────────────────────────────────────────────────────────

const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** base64url, no padding — matches the web client's V2t(). */
function b64url(bytes) {
  return Buffer.from(bytes).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * proof = base64url_nopad( HMAC-SHA256( key = hash, msg = `${nonce}|${role}|${sid}` ) )
 * Mirrors the bundle's H2t(passHash, nonce, role, sid) feed.
 */
async function calculateProof(passHash, nonce, role, deviceSid) {
  const enc = new TextEncoder();
  const key = await webcrypto.subtle.importKey(
    'raw', enc.encode(passHash), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await webcrypto.subtle.sign('HMAC', key, enc.encode(`${nonce}|${role}|${deviceSid}`));
  return b64url(new Uint8Array(sig));
}

function newRequestId(prefix) {
  return `${prefix}-${webcrypto.randomUUID()}`;
}

// ── link parsing ───────────────────────────────────────────────────────────

export function parseRemoteLink(raw) {
  let u;
  try {
    u = new URL(raw.trim());
  } catch {
    throw new Error('Not a valid URL');
  }
  const q = u.searchParams;
  const sid = q.get('sid');
  const hash = q.get('hash');
  const t = q.get('t');
  const ts = t ? Number(t) : NaN;
  const theme = q.get('theme');
  if (!sid || !hash || !Number.isFinite(ts)) {
    throw new Error('Missing or invalid Web remote control relay parameters.');
  }
  return {
    deviceSid: sid,
    passHash: hash,
    timestamp: ts,
    ...(q.get('mid') ? { deviceMid: q.get('mid') } : {}),
    ...(q.get('name') ? { deviceName: q.get('name') } : {}),
    ...(q.get('app_version') ? { appVersion: q.get('app_version') } : {}),
    ...(theme ? { theme } : {}),
  };
}

// ── relay client ───────────────────────────────────────────────────────────

class RelayProbe {
  constructor(link) {
    this.link = link;
    this.ws = null;
    this.pending = new Map(); // requestId -> {resolve, reject, match}
    this.pairStatus = null;
    this.terminalSid = null;
    this.seenTypes = new Map();
    this.frames = [];
    this.heartbeatTimer = null;
  }

  get wsUrl() {
    const u = new URL(RELAY_WS);
    if (this.link.deviceMid?.trim()) u.searchParams.set('mid', this.link.deviceMid);
    return u.toString();
  }

  send(obj) {
    const text = JSON.stringify(obj);
    this.ws.send(text);
    log(`  ↑ ${obj.type}${obj.zcode_type ? `/${obj.zcode_type}` : ''} (${text.length}B)`);
  }

  /** Send a control-plane request and await its correlated response. */
  request(payload, match, { timeoutMs = 20000 } = {}) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(payload.requestId);
        reject(new Error(`timeout waiting for response to ${payload.zcode_type}`));
      }, timeoutMs);
      this.pending.set(payload.requestId, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
        match,
      });
      this.send({ type: 'data', payload, client_ts: Date.now() });
    });
  }

  async connect({ openTimeoutMs = 15000 } = {}) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.wsUrl);
      this.ws = ws;
      const timer = setTimeout(() => reject(new Error('relay auth timeout')), openTimeoutMs);

      ws.addEventListener('open', () => {
        log(`  socket open: ${this.wsUrl}`);
        this.send({
          type: 'auth_init',
          role: ROLE,
          device_sid: this.link.deviceSid,
          meta: {
            platform: 'web',
            version: this.link.appVersion ?? 'web',
            name: 'mobile-browser',
          },
          client_ts: Date.now(),
        });
      });

      ws.addEventListener('message', async (ev) => {
        let msg;
        try {
          msg = JSON.parse(typeof ev.data === 'string' ? ev.data : await ev.data.text());
        } catch {
          return;
        }
        try {
          await this.handle(msg, () => { clearTimeout(timer); resolve(this); }, reject);
        } catch (e) {
          reject(e);
        }
      });

      ws.addEventListener('error', () => reject(new Error('relay socket error')));
      ws.addEventListener('close', (ev) => {
        log(`  socket closed code=${ev.code} reason=${ev.reason || '(none)'}`);
        this.stopHeartbeat();
        if (ev.code !== 1000) reject(new Error(`relay closed: ${ev.code} ${ev.reason}`));
      });
    });
  }

  async handle(msg, onReady, reject) {
    switch (msg.type) {
      case 'auth_challenge': {
        const proof = await calculateProof(
          this.link.passHash, msg.nonce, ROLE, this.link.deviceSid,
        );
        this.send({
          type: 'auth_response',
          device_sid: this.link.deviceSid,
          proof,
          client_ts: Date.now(),
        });
        break;
      }
      case 'auth_ack': {
        this.terminalSid = msg.terminal_sid ?? null;
        this.pairStatus = msg.pair_status ?? null;
        log(`  ✓ authenticated  terminal_sid=${this.terminalSid}  pair_status=${JSON.stringify(this.pairStatus)}`);
        this.startHeartbeat();
        onReady();
        break;
      }
      case 'pair_status_ack': {
        this.pairStatus = msg.pair_status ?? null;
        break;
      }
      case 'data': {
        const p = msg.payload ?? {};
        const k = p.zcode_type ?? '(no zcode_type)';
        this.seenTypes.set(k, (this.seenTypes.get(k) ?? 0) + 1);

        // resolve correlated control-plane responses
        if (p.requestId && this.pending.has(p.requestId)) {
          const entry = this.pending.get(p.requestId);
          if (entry.match(p)) {
            this.pending.delete(p.requestId);
            entry.resolve(p);
          }
        }
        if (k === 'rpc-frame') {
          this.frames.push(p);
        } else {
          log(`  ← data/${k} (${JSON.stringify(p).length}B)`);
        }
        break;
      }
      case 'error': {
        const e = new Error(`relay error: ${msg.code}${msg.message ? ` — ${msg.message}` : ''}`);
        log(`  ✗ ${e.message}`);
        reject(e);
        break;
      }
      default:
        log(`  ← ${msg.type ?? 'unknown'}`);
    }
  }

  startHeartbeat() {
    const tick = async () => {
      if (this.ws?.readyState !== 1) return;
      this.send({ type: 'pair_status_query', device_sid: this.link.deviceSid, client_ts: Date.now() });
      this.heartbeatTimer = setTimeout(tick, 10_000);
    };
    this.heartbeatTimer = setTimeout(tick, 10_000);
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) clearTimeout(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  /** GET-equivalent: bootstrap returns the desktop's workspace list. */
  bootstrap() {
    const requestId = newRequestId('bootstrap');
    return this.request(
      { zcode_type: 'bootstrap-request', requestId },
      (p) => p.zcode_type === 'bootstrap-response' && p.requestId === requestId,
    );
  }

  /** Open an RPC bridge for one workspace; returns the bridge descriptor. */
  openBridge(workspaceKey, { taskId, recoveryId, generation = 1 } = {}) {
    const requestId = newRequestId('workspace-bridge');
    const bridgeSessionId = newRequestId('bridge');
    return this.request(
      {
        zcode_type: 'workspace-bridge-open',
        requestId,
        bridgeSessionId,
        bridgeGeneration: generation,
        ...(recoveryId ? { recoveryId } : {}),
        workspaceKey,
        ...(taskId ? { taskId } : {}),
      },
      (p) => p.zcode_type === 'workspace-bridge-ready' && p.bridgeSessionId === bridgeSessionId,
    );
  }

  close() {
    this.stopHeartbeat();
    try { this.ws?.close(1000, 'probe done'); } catch {}
  }
}

// ── main ───────────────────────────────────────────────────────────────────

async function main() {
  const idx = process.argv.indexOf('--url');
  const raw = idx !== -1 ? process.argv[idx + 1] : process.env.ZCODE_REMOTE_URL;
  if (!raw) {
    console.error('usage: node probe.mjs --url "https://zcode.z.ai/remote/v4?sid=..&hash=..&t=..&mid=.."');
    process.exit(2);
  }

  const link = parseRemoteLink(raw);
  const ageMin = (Date.now() - link.timestamp) / 60000;
  log('── parsed link ──────────────────────────────────────────');
  log(`  deviceSid : ${link.deviceSid}`);
  log(`  deviceMid : ${link.deviceMid ?? '(none)'}`);
  log(`  deviceName: ${link.deviceName ?? '(none)'}`);
  log(`  appVersion: ${link.appVersion ?? '(none)'}`);
  log(`  hash      : ${link.passHash.length} chars`);
  log(`  link age  : ${ageMin.toFixed(1)} min`);
  log('');

  const probe = new RelayProbe(link);
  log('── handshake ────────────────────────────────────────────');
  await probe.connect();

  log('');
  log('── bootstrap ────────────────────────────────────────────');
  const boot = await probe.bootstrap();
  writeFileSync('bootstrap.json', JSON.stringify(boot, null, 2));

  const res = boot.result ?? {};
  log(`  desktopAppVersion : ${res.desktopAppVersion ?? '?'}`);
  log(`  activeWorkspaceKey: ${res.initialViewState?.activeWorkspaceKey ?? '?'}`);
  log(`  activeTaskId      : ${res.initialViewState?.activeTaskId ?? '?'}`);

  const workspaces = res.workspaces ?? [];
  log(`  ✓ ${workspaces.length} workspace(s)   (full payload -> bootstrap.json)`);
  for (const w of workspaces) {
    log(`    • kind=${w.kind ?? w.workspaceKind ?? '?'}  path=${w.workspacePath ?? w.workspaceKey ?? '?'}  label=${w.workspaceLabel ?? ''}`);
  }
  const tasks = res.tasks ?? [];
  log(`  ✓ ${tasks.length} task(s)`);
  for (const t of tasks.slice(0, 8)) {
    log(`    • [${t.displayStatus ?? '?'}] ${t.taskId}  "${t.title ?? ''}"  ws=${t.workspaceLabel ?? ''}`);
  }

  // open a bridge on the active workspace so we can observe the RPC plane
  const first = workspaces.find((w) => (w.workspacePath ?? w.workspaceKey) === res.initialViewState?.activeWorkspaceKey) ?? workspaces[0];
  const key = first?.workspacePath ?? first?.workspaceKey;
  if (key) {
    log('');
    log('── workspace bridge ─────────────────────────────────────');
    log(`  opening bridge for ${key}`);
    const ready = await probe.openBridge(key);
    log(`  ✓ bridge ready: ${JSON.stringify(ready.bridge ?? ready).slice(0, 300)}`);

    log('');
    log('── observing rpc plane for 6s ───────────────────────────');
    await sleep(6000);
  }

  log('');
  log('── summary ──────────────────────────────────────────────');
  log(`  pair_status : ${JSON.stringify(probe.pairStatus)}`);
  log(`  terminal_sid: ${probe.terminalSid}`);
  log('  relay control-plane messages seen:');
  for (const [k, v] of probe.seenTypes) log(`    ${k.padEnd(28)} ${v}`);
  log(`  rpc-frames buffered: ${probe.frames.length}`);
  if (probe.frames.length) {
    writeFileSync('rpc-frames.json', JSON.stringify(probe.frames, null, 2));

    const shapes = new Map();
    for (const f of probe.frames) {
      const keys = Object.keys(f).sort().join(',');
      shapes.set(keys, (shapes.get(keys) ?? 0) + 1);
    }
    log('  distinct rpc-frame envelope key-sets:');
    for (const [k, v] of shapes) log(`    ${v}×  {${k}}`);

    const first = probe.frames[0];
    log('  first envelope (dataBase64 truncated):');
    log(`    ${JSON.stringify({ ...first, dataBase64: `${String(first.dataBase64).slice(0, 48)}…` })}`);

    // The OSS wire-codec base64-encodes the *logical JSON frame*, so a decode
    // should yield readable JSON. Fall back to a hex dump if it doesn't.
    const buf = Buffer.from(first.dataBase64, 'base64');
    log(`  decoded dataBase64: ${buf.length} bytes`);
    const head = buf.subarray(0, 16).toString('hex').replace(/(..)/g, '$1 ').trim();
    log(`    first 16 bytes: ${head}`);
    const asText = buf.toString('utf8');
    if (/^[\s\S]*[{[]/.test(asText.slice(0, 1))) {
      let pretty = asText;
      try { pretty = JSON.stringify(JSON.parse(asText), null, 2); } catch {}
      log('    decoded payload:');
      log(pretty.split('\n').slice(0, 40).map((l) => `      ${l}`).join('\n'));
    } else {
      log(`    (payload is not JSON at offset 0; first 120 chars: ${JSON.stringify(asText.slice(0, 120))})`);
    }
  }

  probe.close();
}

const isMain = import.meta.url === `file://${process.argv[1]?.replace(/\\/g, '/')}`;
if (isMain || process.argv[1]?.endsWith('probe.mjs')) {
  main().then(
    () => process.exit(0),
    (e) => { console.error(`\nFATAL: ${e.message}`); process.exit(1); },
  );
}
