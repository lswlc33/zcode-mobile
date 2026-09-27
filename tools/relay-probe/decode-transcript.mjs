#!/usr/bin/env node
/**
 * Decode a capture transcript into a readable header/body table.
 *
 * Usage: node decode-transcript.mjs transcript.json [--full]
 */
import { readFileSync } from 'node:fs';
import { decodeMessage, RequestType, ResponseType } from './zcode-client.mjs';

const path = process.argv[2] ?? 'transcript-handshake.json';
const full = process.argv.includes('--full');
const t = JSON.parse(readFileSync(path, 'utf8'));

const reqName = Object.fromEntries(Object.entries(RequestType).map(([k, v]) => [v, k]));
const resName = Object.fromEntries(Object.entries(ResponseType).map(([k, v]) => [v, k]));

function preview(v, limit) {
  const s = JSON.stringify(v);
  if (!s) return String(v);
  return s.length > limit ? `${s.slice(0, limit)}…(${s.length}B)` : s;
}

console.log(`transcript ${path}`);
console.log(`  frames=${t.frames.length} events=${t.events.length} wire=${t.wire.length}`);

// ── control plane ──────────────────────────────────────────────────────────
console.log('\n═══ CONTROL PLANE (relay JSON) ═══');
const seen = new Map();
for (const w of t.wire) {
  if (w.dir !== 'in') continue;
  let m; try { m = JSON.parse(w.text); } catch { continue; }
  const kind = m.payload?.zcode_type ?? m.type;
  seen.set(kind, (seen.get(kind) ?? 0) + 1);
}
for (const [k, n] of seen) console.log(`  ${String(n).padStart(4)}× ${k}`);

// ── channel frames ─────────────────────────────────────────────────────────
function show(label, list) {
  console.log(`\n═══ ${label} ═══`);
  for (const f of list) {
    const type = reqName[f.type] ?? resName[f.type] ?? `?${f.type}`;
    const head = f.header;
    let line = `  [${type}] id=${f.id}`;
    if (f.type === RequestType.Promise) line += ` → ${head[2]}.${head[3]}`;
    if (f.type === RequestType.EventListen) line += ` ▷ ${head[2]}.${head[3]}`;
    console.log(line);
    if (full) console.log(`      header=${JSON.stringify(head)}`);
    const body = f.body;
    const limit = full ? 100000 : 600;
    if (Array.isArray(body)) {
      body.forEach((b, i) => console.log(`      arg[${i}] ${preview(b, limit)}`));
      if (!body.length) console.log('      (no args)');
    } else {
      console.log(`      body ${preview(body, limit)}`);
    }
  }
}

// Channel frames were captured from the wire; classify by header[0].
const reqFrames = [];
const resFrames = [];
for (const w of t.wire) {
  if (w.dir !== 'out') continue;
  let m; try { m = JSON.parse(w.text); } catch { continue; }
  if (m.payload?.zcode_type !== 'rpc-frame') continue;
  const buf = Buffer.from(m.payload.dataBase64, 'base64');
  try {
    const [header, body] = decodeMessage(buf);
    reqFrames.push({ type: header[0], id: header[1], header, body });
  } catch (e) { reqFrames.push({ type: -1, id: -1, header: ['decode-error', e.message], body: null }); }
}
show('CHANNEL REQUESTS (client → host)', reqFrames);
show('CHANNEL RESPONSES / EVENTS (host → client)', t.frames);

// ── events ─────────────────────────────────────────────────────────────────
if (t.events.length) {
  console.log(`\n═══ EVENT FIRES (${t.events.length}) ═══`);
  for (const e of t.events.slice(0, full ? 999 : 12)) {
    const d = e.data;
    const kind = d?.kind ?? d?.payload?.kind ?? '?';
    console.log(`  ▷ ${e.event} kind=${kind} topic=${d?.topic ?? ''} sub=${d?.subscriptionId ?? ''} ord=${d?.logicalFrameOrdinal ?? ''}`);
    if (full) console.log(`      ${preview(d, 4000)}`);
  }
}

// ── call results ───────────────────────────────────────────────────────────
if (t.calls?.length) {
  console.log('\n═══ CALL RESULTS ═══');
  for (const c of t.calls) {
    if (c.kind) { console.log(`  (${c.kind}) ${c.text ?? ''}`); continue; }
    console.log(`  ${c.ok ? '✓' : '✗'} ${c.channel}.${c.method} args=${preview(c.args, 200)}`);
    console.log(`      ${c.ok ? preview(c.result, full ? 100000 : 800) : c.error}`);
  }
}

// ── snapshot shape ─────────────────────────────────────────────────────────
const snapEvent = t.events.find((e) => {
  const f = e.data?.frame ?? e.data;
  return f?.payload?.kind === 'snapshot' || f?.kind === 'snapshot';
});
if (snapEvent) {
  console.log('\n═══ SNAPSHOT TOP-LEVEL KEYS ═══');
  const f = snapEvent.data.frame ?? snapEvent.data;
  const snap = f.payload?.snapshot ?? f.snapshot ?? {};
  console.log(`  frame keys : ${Object.keys(f).join(', ')}`);
  console.log(`  payload    : ${Object.keys(f.payload ?? {}).join(', ')}`);
  console.log(`  snapshot   : ${Object.keys(snap).join(', ')}`);
  if (snap.state) console.log(`  state      : ${Object.keys(snap.state).join(', ')}`);
  if (snap.rows) console.log(`  rows       : ${Object.keys(snap.rows).join(', ')}`);
}
