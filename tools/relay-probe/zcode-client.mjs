/**
 * ZCode relay client — reference implementation of the full stack.
 *
 * Layers, bottom to top:
 *   1. RelayTransport  : wss://zcode.z.ai/ws JSON control plane + HMAC auth
 *   2. RpcFrameCodec   : base64 + crc32 framing of channel payloads
 *   3. ChannelClient   : VQL request/response/event multiplexing
 *   4. ZCodeClient     : bootstrap / bridge / v4 conversation calls
 *
 * Notable finding: on the relay path the 13-byte SocketProtocol header is
 * ABSENT. `dataBase64` carries the bare channel payload
 * (serialize(header) + serialize(body)); the relay frames it itself via
 * messageSeq / messageBytes / fragmentIndex.
 */

import { webcrypto } from 'node:crypto';

const RELAY_WS = 'wss://zcode.z.ai/ws';
const ROLE = 'terminal';

// ═══════════════════════════════════════════════════════════════════════════
// VQL serialization (mirrors packages/rpc/src/serialization.ts)
// ═══════════════════════════════════════════════════════════════════════════

const DataType = {
  Undefined: 0,
  String: 1,
  Buffer: 2,
  VSBuffer: 3,
  Array: 4,
  Object: 5,
  Int: 6,
};

export const RequestType = {
  Promise: 100,
  PromiseCancel: 101,
  EventListen: 102,
  EventDispose: 103,
};

export const ResponseType = {
  Initialize: 200,
  PromiseSuccess: 201,
  PromiseError: 202,
  PromiseErrorObj: 203,
  EventFire: 204,
};

class Writer {
  constructor() { this.chunks = []; }
  write(buf) { this.chunks.push(buf); }
  get buffer() { return Buffer.concat(this.chunks); }
}

class Reader {
  constructor(buf) { this.buf = buf; this.pos = 0; }
  read(n) {
    const out = this.buf.subarray(this.pos, this.pos + n);
    this.pos += out.length;
    return out;
  }
}

function writeVql(w, value) {
  if (value === 0) { w.write(Buffer.from([0])); return; }
  const bytes = [];
  for (let v = value; v !== 0; v = v >>> 7) {
    bytes.push(v & 0x7f);
  }
  for (let i = 0; i < bytes.length - 1; i++) bytes[i] |= 0x80;
  w.write(Buffer.from(bytes));
}

function readVql(r) {
  let value = 0;
  for (let n = 0; ; n += 7) {
    const b = r.read(1)[0];
    if (b === undefined) throw new Error('vql: truncated');
    value |= (b & 0x7f) << n;
    if (!(b & 0x80)) return value;
  }
}

export function serialize(w, data) {
  if (data === undefined) {
    w.write(Buffer.from([DataType.Undefined]));
  } else if (typeof data === 'string') {
    const b = Buffer.from(data, 'utf8');
    w.write(Buffer.from([DataType.String]));
    writeVql(w, b.length);
    w.write(b);
  } else if (Buffer.isBuffer(data) || data instanceof Uint8Array) {
    const b = Buffer.from(data);
    w.write(Buffer.from([DataType.Buffer]));
    writeVql(w, b.length);
    w.write(b);
  } else if (Array.isArray(data)) {
    w.write(Buffer.from([DataType.Array]));
    writeVql(w, data.length);
    for (const el of data) serialize(w, el);
  } else if (typeof data === 'number' && (data | 0) === data) {
    w.write(Buffer.from([DataType.Int]));
    writeVql(w, data);
  } else {
    const b = Buffer.from(JSON.stringify(data), 'utf8');
    w.write(Buffer.from([DataType.Object]));
    writeVql(w, b.length);
    w.write(b);
  }
}

export function deserialize(r) {
  const type = r.read(1)[0];
  switch (type) {
    case DataType.Undefined: return undefined;
    case DataType.String: return r.read(readVql(r)).toString('utf8');
    case DataType.Buffer: return r.read(readVql(r));
    case DataType.VSBuffer: return r.read(readVql(r));
    case DataType.Array: {
      const n = readVql(r);
      const out = [];
      for (let i = 0; i < n; i++) out.push(deserialize(r));
      return out;
    }
    case DataType.Object: return JSON.parse(r.read(readVql(r)).toString('utf8'));
    case DataType.Int: return readVql(r);
    default: throw new Error(`vql: unknown type tag ${type}`);
  }
}

export function encodeMessage(header, body) {
  const w = new Writer();
  serialize(w, header);
  serialize(w, body);
  return w.buffer;
}

export function decodeMessage(buf) {
  const r = new Reader(buf);
  return [deserialize(r), deserialize(r)];
}

// ═══════════════════════════════════════════════════════════════════════════
// crc32 (mirrors wire-binary.ts crc32WireBytes)
// ═══════════════════════════════════════════════════════════════════════════

export function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
  }
  return ((crc ^ 0xffffffff) >>> 0).toString(16).padStart(8, '0');
}

// ═══════════════════════════════════════════════════════════════════════════
// auth
// ═══════════════════════════════════════════════════════════════════════════

const b64url = (bytes) => Buffer.from(bytes).toString('base64')
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** proof = base64url_nopad(HMAC-SHA256(key=hash, msg=`nonce|role|sid`)) */
export async function calculateProof(passHash, nonce, role, deviceSid) {
  const enc = new TextEncoder();
  const key = await webcrypto.subtle.importKey(
    'raw', enc.encode(passHash), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await webcrypto.subtle.sign('HMAC', key, enc.encode(`${nonce}|${role}|${deviceSid}`));
  return b64url(new Uint8Array(sig));
}

export function parseRemoteLink(raw) {
  const q = new URL(raw.trim()).searchParams;
  const sid = q.get('sid'), hash = q.get('hash'), t = q.get('t');
  const ts = t ? Number(t) : NaN;
  if (!sid || !hash || !Number.isFinite(ts)) {
    throw new Error('Missing or invalid Web remote control relay parameters.');
  }
  return {
    deviceSid: sid, passHash: hash, timestamp: ts,
    deviceMid: q.get('mid') ?? undefined,
    deviceName: q.get('name') ?? undefined,
    appVersion: q.get('app_version') ?? undefined,
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// RelayTransport
// ═══════════════════════════════════════════════════════════════════════════

export class RelayTransport {
  constructor(link, { log = () => {} } = {}) {
    this.link = link;
    this.log = log;
    this.ws = null;
    this.control = new Map();   // requestId -> {resolve, match}
    this.pairStatus = null;
    this.terminalSid = null;
    this.bridge = null;
    this.onChannelPayload = () => {};
    this.onControl = () => {};
    this.outSeq = 0;
    this.outMessageSeq = 0;
    this.heartbeatTimer = null;
    this.closed = false;
  }

  get wsUrl() {
    const u = new URL(RELAY_WS);
    if (this.link.deviceMid?.trim()) u.searchParams.set('mid', this.link.deviceMid);
    return u.toString();
  }

  send(obj) { this.ws.send(JSON.stringify(obj)); }

  async connect() {
    await new Promise((resolve, reject) => {
      const ws = new WebSocket(this.wsUrl);
      this.ws = ws;
      const timer = setTimeout(() => reject(new Error('relay auth timeout')), 20000);
      ws.addEventListener('open', () => {
        this.send({
          type: 'auth_init', role: ROLE, device_sid: this.link.deviceSid,
          meta: { platform: 'web', version: this.link.appVersion ?? 'web', name: 'mobile-browser' },
          client_ts: Date.now(),
        });
      });
      ws.addEventListener('message', async (ev) => {
        let m; try { m = JSON.parse(typeof ev.data === 'string' ? ev.data : await ev.data.text()); } catch { return; }
        try {
          await this.#handle(m);
          if (m.type === 'auth_ack') { clearTimeout(timer); resolve(); }
        } catch (e) { clearTimeout(timer); reject(e); }
      });
      ws.addEventListener('error', () => reject(new Error('relay socket error')));
      ws.addEventListener('close', (ev) => {
        this.closed = true;
        this.#stopHeartbeat();
        this.log(`socket closed code=${ev.code} reason=${ev.reason || '(none)'}`);
      });
    });
  }

  async #handle(m) {
    switch (m.type) {
      case 'auth_challenge': {
        const proof = await calculateProof(this.link.passHash, m.nonce, ROLE, this.link.deviceSid);
        this.send({ type: 'auth_response', device_sid: this.link.deviceSid, proof, client_ts: Date.now() });
        break;
      }
      case 'auth_ack':
        this.terminalSid = m.terminal_sid ?? null;
        this.pairStatus = m.pair_status ?? null;
        this.log(`authenticated terminal_sid=${this.terminalSid} pair_status=${JSON.stringify(this.pairStatus)}`);
        this.#startHeartbeat();
        break;
      case 'pair_status_ack':
        this.pairStatus = m.pair_status ?? null;
        break;
      case 'data': {
        const p = m.payload ?? {};
        const kind = p.zcode_type;
        if (kind === 'rpc-frame') {
          this.#ackFrame(p);
          if (p.dataBase64) this.onChannelPayload(Buffer.from(p.dataBase64, 'base64'), p);
          return;
        }
        if (p.requestId && this.control.has(p.requestId)) {
          const entry = this.control.get(p.requestId);
          if (!entry.match || entry.match(p)) { this.control.delete(p.requestId); entry.resolve(p); }
        }
        this.onControl(p);
        break;
      }
      case 'error':
        throw new Error(`relay error ${m.code}${m.message ? `: ${m.message}` : ''}`);
      default:
        this.onControl(m);
    }
  }

  /** Acknowledge an inbound rpc-frame so the peer can release its buffer. */
  #ackFrame(p) {
    if (p.messageSeq == null) return;
    this.send({
      type: 'data',
      payload: {
        zcode_type: 'rpc-frame-ack',
        bridgeSessionId: p.bridgeSessionId,
        bridgeGeneration: p.bridgeGeneration,
        ackMessageSeq: p.messageSeq,
      },
      client_ts: Date.now(),
    });
  }

  #startHeartbeat() {
    const tick = () => {
      if (this.ws?.readyState !== 1) return;
      this.send({ type: 'pair_status_query', device_sid: this.link.deviceSid, client_ts: Date.now() });
      this.heartbeatTimer = setTimeout(tick, 10000);
    };
    this.heartbeatTimer = setTimeout(tick, 10000);
  }

  #stopHeartbeat() { if (this.heartbeatTimer) clearTimeout(this.heartbeatTimer); this.heartbeatTimer = null; }

  controlRequest(payload, match, timeoutMs = 20000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.control.delete(payload.requestId); reject(new Error(`timeout: ${payload.zcode_type}`)); }, timeoutMs);
      this.control.set(payload.requestId, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        match,
      });
      this.send({ type: 'data', payload, client_ts: Date.now() });
    });
  }

  async bootstrap() {
    const requestId = `bootstrap-${webcrypto.randomUUID()}`;
    return this.controlRequest(
      { zcode_type: 'bootstrap-request', requestId },
      (p) => p.zcode_type === 'bootstrap-response' && p.requestId === requestId,
    );
  }

  async openBridge(workspaceKey, { taskId, generation = 1 } = {}) {
    const requestId = `workspace-bridge-${webcrypto.randomUUID()}`;
    const bridgeSessionId = `bridge-${webcrypto.randomUUID()}`;
    const ready = await this.controlRequest(
      {
        zcode_type: 'workspace-bridge-open', requestId, bridgeSessionId, bridgeGeneration: generation,
        workspaceKey, ...(taskId ? { taskId } : {}),
      },
      (p) => p.zcode_type === 'workspace-bridge-ready' && p.bridgeSessionId === bridgeSessionId,
    );
    this.bridge = ready.bridge ?? { bridgeSessionId, bridgeGeneration: generation };
    return this.bridge;
  }

  /** Wrap a bare channel payload into a relay rpc-frame envelope. */
  sendChannelPayload(buf) {
    if (!this.bridge) throw new Error('no bridge open');
    this.outSeq += 1;
    this.outMessageSeq += 1;
    this.send({
      type: 'data',
      payload: {
        zcode_type: 'rpc-frame',
        bridgeSessionId: this.bridge.bridgeSessionId,
        bridgeGeneration: this.bridge.bridgeGeneration,
        seq: this.outSeq,
        messageSeq: this.outMessageSeq,
        messageBytes: buf.length,
        checksum: { algorithm: 'crc32', value: crc32(buf) },
        fragmentIndex: 0,
        fragmentCount: 1,
        dataBase64: buf.toString('base64'),
      },
      client_ts: Date.now(),
    });
  }

  close() { this.#stopHeartbeat(); try { this.ws?.close(1000, 'done'); } catch {} }
}

// ═══════════════════════════════════════════════════════════════════════════
// ChannelClient
// ═══════════════════════════════════════════════════════════════════════════

export class ChannelClient {
  constructor(transport, { log = () => {} } = {}) {
    this.transport = transport;
    this.log = log;
    this.lastRequestId = 0;
    this.handlers = new Map();
    this.pending = new Map();
    this.initialized = false;
    this.initWaiters = [];
    transport.onChannelPayload = (buf) => this.#onBuffer(buf);
  }

  #onBuffer(buf) {
    let header; let body;
    try { [header, body] = decodeMessage(buf); }
    catch (e) { this.log(`channel decode failed: ${e.message} (${buf.length}B, head=${buf.subarray(0, 12).toString('hex')})`); return; }

    const type = header?.[0];
    if (type === ResponseType.Initialize) {
      this.initialized = true;
      for (const w of this.initWaiters) w();
      this.initWaiters = [];
      this.log('channel initialized');
      return;
    }
    if (type === ResponseType.EventFire) {
      this.handlers.get(header[1])?.({ type, id: header[1], data: body });
      return;
    }
    const e = this.pending.get(header?.[1]);
    this.handlers.get(header?.[1])?.({ type, id: header[1], data: body });
    if (e) { this.pending.delete(header[1]); }
  }

  #send(header, body) {
    const buf = encodeMessage(header, body);
    this.transport.sendChannelPayload(buf);
  }

  /**
   * Invoke a service method. `args` must be the positional argument list:
   * the host dispatches via `target.apply(handler, args)`
   * (see ProxyChannel.fromService), so a single-parameter method still
   * travels as a one-element array.
   */
  call(channelName, method, ...args) {
    const id = this.lastRequestId++;
    return new Promise((resolve, reject) => {
      this.handlers.set(id, (res) => {
        switch (res.type) {
          case ResponseType.PromiseSuccess:
            this.handlers.delete(id); resolve(res.data); break;
          case ResponseType.PromiseError:
            this.handlers.delete(id);
            reject(new Error(`${res.data?.name ?? 'Error'}: ${res.data?.message ?? 'rpc error'}`)); break;
          case ResponseType.PromiseErrorObj:
            this.handlers.delete(id); reject(new Error(JSON.stringify(res.data))); break;
        }
      });
      this.pending.set(id, true);
      this.log(`→ call ${channelName}.${method} id=${id} argc=${args.length}`);
      this.#send([RequestType.Promise, id, channelName, method], args);
    });
  }

  listen(channelName, event, arg, onEvent) {
    const id = this.lastRequestId++;
    this.handlers.set(id, (res) => onEvent(res.data));
    this.#send([RequestType.EventListen, id, channelName, event], arg);
    return id;
  }

  disposeEvent(id) { this.#send([RequestType.EventDispose, id], undefined); this.handlers.delete(id); }
}
