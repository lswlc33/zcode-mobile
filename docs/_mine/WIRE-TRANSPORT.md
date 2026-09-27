# ZCode Relay — Wire Framing & Transport Reference

Scope: byte-level documentation of the hosted relay WebSocket, its JSON control plane, the
RPC frame envelope, the VQL channel serialization, and the v4 snapshot/delta data model.
Everything below is derived from the Apache-2.0 `zai-org/ZCode` tree at
`E:/open_trae_m/ZCode_full` (v3.14.3) plus **live-relay captures** in
`E:/open_trae_m/zcode-mobile/tools/relay-probe/` (Node reference client + captured transcripts).
The relay server itself is NOT open source; its behaviour here is reconstructed from the public
web bundle (`E:/open_trae_m/_relay_probe/index-B-ilXaCQ.js`), DevTools captures, and the OSS schemas.

Source legend for citations:
- `PKG` = `E:/open_trae_m/ZCode_full/packages`
- `SHARED` = `PKG/shared/src/zcode-protocol-v4`
- `MOB` = `E:/open_trae_m/zcode-mobile`
- `BUNDLE` = `E:/open_trae_m/_relay_probe/index-B-ilXaCQ.js` (minified web client)

---

## 0. Layer stack

```
application : conversation snapshot / deltas, workspace + task lists, commands
  v4 protocol: clientHello, subscribe, snapshot/deltas   ← SHARED/*.ts
  channel RPC: request/response/event mux, VQL binary     ← PKG/rpc/src/serialization.ts
  relay frame: data/rpc-frame, base64 + crc32 + fragments ← external relay (reverse-engineered)
  WS control : auth_*, bootstrap, bridge, heartbeat       ← external relay (reverse-engineered)
```

**Load-bearing invariant:** on the relay path there is **no 13-byte SocketProtocol header**.
`rpc-frame.dataBase64` carries the **bare channel payload**. The 13-byte header exists only on the
direct socket path (CLI ↔ desktop `PersistentProtocol`). See `SHARED/wire-codec.ts:50-51`,
`MOB/protocol/.../RelayTransport.kt:105-112`, `MOB/docs/PROTOCOL.md:34-36`.

---

## 1. Endpoint + URL params

### 1.1 WebSocket endpoint

```
wss://zcode.z.ai/ws[?mid=<deviceMid>]
```

- `RELAY_WS = "wss://zcode.z.ai/ws"` — `MOB/protocol/src/main/kotlin/dev/zcodemobile/protocol/RelayTransport.kt:120`.
- URL builder appends `?mid=` only when `mid` is non-blank — `RelayTransport.kt:157-161`,
  `MOB/tools/relay-probe/zcode-client.mjs:212-216`.
- Web bundle derives the origin from `endpointOrigin: "https://zcode.z.ai"` and rewrites scheme to `ws`,
  appending `/ws` (`BUNDLE`: `new URL(this.options.relayWsUrl)` + `e.searchParams.set("mid", ...)`).
- Observed live capture: socket opened to `wss://zcode.z.ai/ws?mid=...`
  (`MOB/tools/relay-probe/transcript-handshake.json` `wire[0]`).

### 1.2 Share link

The link the user opens is `https://zcode.z.ai/remote/v4?<params>`. Parsed by
`RemoteLink.parse` (`RelayTransport.kt:26-58`) and `parseRemoteLink` (`zcode-client.mjs:176-189`).

| Param | Meaning | Required | Notes |
|---|---|---|---|
| `sid` | device session id (e.g. `d_QVhdHsDAk7nrURU3dThDmU`) | **yes** | used as `device_sid` in auth and `nonce|role|sid` proof input |
| `hash` | HMAC key (44-char base64 → 32 raw bytes) | **yes** | used verbatim as the UTF-8 key string, **not** decoded first |
| `t` | issue timestamp, **milliseconds** | **yes**, must parse as number | issue time, **not** expiry — a link still worked 76 min after issuance (`MOB/docs/PROTOCOL.md:65`) |
| `mid` | machine id | no | becomes the `?mid=` WS query param |
| `name` | host name | no | client does **not** send it up |
| `app_version` | desktop app version | no | echoed in `auth_init.meta.version` |
| `theme` | UI theme hint | no | observed parsed in `MOB/tools/relay-probe/probe.mjs:60-72` |

Failure: missing/invalid `sid`/`hash`/`t` →
`Missing or invalid Web remote control relay parameters.` (`RelayTransport.kt:40-48`,
`zcode-client.mjs:180-182`).

---

## 2. Auth handshake

Four JSON text frames, all **top-level `type`** (not wrapped in `data`).
Implementation: `RelayTransport.kt:163-235`, `zcode-client.mjs:220-284`; live capture in
`transcript-handshake.json`.

```
1. C→S  {"type":"auth_init","role":"terminal","device_sid":"<sid>",
         "meta":{"platform":"web","version":"<app_version|web>","name":"mobile-browser"},
         "client_ts":1790350895812}

2. S→C  {"type":"auth_challenge","server_ts":1790351069,"nonce":"xY2LU7Im3BbnQgn1rw2sGHaF"}

3. C→S  {"type":"auth_response","device_sid":"<sid>","proof":"<proof>","client_ts":1790350895868}

4. S→C  {"type":"auth_ack","server_ts":1790351069,"device_sid":"<sid>",
         "terminal_sid":"t_LxbbCzfyFCNyZnMRn2HDw3","pair_status":"matched"}
```

### 2.1 HMAC proof formula (verified byte-for-byte)

```
proof = base64url_nopad( HMAC-SHA256( key = <hash string, UTF-8 bytes>,
                                     msg = "<nonce>|<role>|<sid>" ) )
```

- `role` is the **literal** `terminal` (constant `ROLE`, `RelayTransport.kt:121`).
- Separator is a single `|`, order fixed `nonce|role|sid` (`RelayTransport.kt:130-135`,
  `zcode-client.mjs:166-174`, `probe.mjs:33-40`).
- `base64url_nopad`: standard base64, then `+`→`-`, `/`→`_`, strip `=` (`zcode-client.mjs:163-164`).
- The key is the `hash` param's **string bytes**, not its base64-decoded bytes.
- `t` is **not** part of the signature (open question, `MOB/docs/PROTOCOL.md:604`).

### 2.2 Heartbeat / presence

```
C→S  {"type":"pair_status_query","device_sid":"<sid>","client_ts":...}   // every 10 s (jittered)
S→C  {"type":"pair_status_ack","pair_status":"matched"}
```

`RelayTransport.kt:276-288`, `zcode-client.mjs:301-308`. `pair_status` observed value `matched`
(desktop paired/online).

### 2.3 Failure modes

- Message-level `error` frame: `{"type":"error","code":"<CODE>","message":"..."}`. Codes observed in
  bundle: `KICKED`, `DEVICE_OFFLINE`, `AUTH_FAILED`, `WRONG_PARAM`, `INTERNAL`
  (`BUNDLE` `handleRelayError`). `KICKED`→session-conflict; `DEVICE_OFFLINE`→recover;
  `AUTH_FAILED`/`WRONG_PARAM`→terminal invalid-mobile-connection.
- Close codes (documented): `4004` SessionNotFound, `4009` SessionConflict, `4010` DesktopDisconnected,
  `4011` SessionExpired, `4012` WorkspaceClosed, `4013` InvalidMobileConnection
  (`MOB/docs/PROTOCOL.md:119-133`).
- Client-side auth timeout: 20 000 ms (`RelayTransport.kt:200`, `zcode-client.mjs:224`).
- On any `error` before `auth_ack`, `connect()` rejects.

---

## 3. Control plane

Every control message carrying a `zcode_type` is wrapped as a `data` frame:

```
C→S  {"type":"data","payload":{ "zcode_type": "...", ... },"client_ts":<ms>}
S→C  {"type":"data","server_ts":<s>,"payload":{ "zcode_type": "...", ... }}
```

`server_ts` is in **seconds**; `client_ts` is in **milliseconds** (observed).
Correlation is by `requestId`; the response echoes it.

### 3.1 `bootstrap-request` / `bootstrap-response`

```
C→S {"zcode_type":"bootstrap-request","requestId":"bootstrap-<uuid>"}

S→C {"zcode_type":"bootstrap-response","requestId":"bootstrap-<uuid>","success":true,
     "result":{
       "desktopAppVersion":"3.14.3",
       "initialViewState":{"activeTaskId":"sess_...","activeWorkspaceKey":"E:\\open_trae_m","updatedAt":<ms>},
       "mobileViewState":{"activeTaskId":"sess_...","activeWorkspaceKey":"E:\\open_trae_m","updatedAt":<ms>},
       "windowControlSessionId":"d_...",
       "workspaces":[{"kind":"local","label":"open_trae_m","workspacePath":"E:\\open_trae_m",
                      "workspacePurpose":"project|conversation"}],
       "tasks":[{"taskId":"sess_...","title":"...","displayStatus":"running|completed|error",
                 "provider":"glm","workspaceKind":"local","workspaceLabel":"open_trae_m",
                 "workspacePath":"E:\\open_trae_m","createdAt":<ms>,"updatedAt":<ms>,"unreadAt":<ms>}]
     }}
```

Observed `bootstrap.json` top-level keys: `requestId, result, success, zcode_type`
(`MOB/tools/relay-probe/bootstrap.json`). `workspaces[]` carry `workspacePath` + `label` (not
`workspaceKey` in the current build); the desktop can repeat a `taskId` (stale + live) — collapse by id
(`RelayTransport.kt:326-329`).

### 3.2 `workspace-bridge-open` / `workspace-bridge-ready`

**A bridge must be open before any channel (rpc-frame) traffic.**

```
C→S {"zcode_type":"workspace-bridge-open","requestId":"workspace-bridge-<uuid>",
     "bridgeSessionId":"bridge-<uuid>","bridgeGeneration":1,
     "recoveryId":"<opaque>",              // optional, resend/reconnect only
     "workspaceKey":"E:\\open_trae_m",
     "taskId":"sess_..."}                  // optional

S→C {"zcode_type":"workspace-bridge-ready","requestId":"...","bridgeSessionId":"bridge-<uuid>",
     "bridgeGeneration":1,
     "bridge":{"bridgeSessionId":"bridge-<uuid>","bridgeGeneration":1,"kind":"local",
               "workspaceKey":"E:\\open_trae_m","workspacePath":"E:\\open_trae_m",
               "initialTaskId":"sess_...","recoveryId":"<opaque>"}}
```

`recoveryId` is present in the web bundle's open call and echoed on `bridge` (`BUNDLE`: identity =
`{bridgeSessionId, bridgeGeneration?, recoveryId?}`). Live capture shows `bridgeSessionId`,
`bridgeGeneration`, `kind`, `workspaceKey`, `workspacePath`, `initialTaskId`.

### 3.3 `rpc-frame` / `rpc-frame-ack` — see §4.

### 3.4 Other control messages

| `zcode_type` | Direction | Shape |
|---|---|---|
| `workspace-reconnect-request` | C→S | `{zcode_type, requestId, workspaceKey}` |
| `workspace-reconnect-response` | S→C | `{zcode_type, requestId, workspaceKey, ...}` |
| `mobile-view-state-update` | C→S | `{zcode_type, viewState:{activeWorkspaceKey,[activeTaskId],updatedAt}, deviceInfo:{appVersion}}` |
| `mobile-diagnostic` | C→S | `{zcode_type, event, timestamp, state, previousState?, pairStatus?, closeCode?, closeReason?, ...}` |

(`BUNDLE`: `workspace-reconnect-request`/`-response`, `mobile-view-state-update`, `mobile-diagnostic`;
`MOB/docs/PROTOCOL.md:179-180`.)

### 3.5 State machine (relay client)

```
idle → open socket → send auth_init → authenticating
  authenticating --auth_challenge--> send auth_response
  authenticating --auth_ack--------> paired  (terminal_sid, pair_status; start heartbeat)
  authenticating --error-----------> terminal failure
paired --pair_status_query (10s)--> pair_status_ack
paired --> bootstrap-request --> workspace-bridge-open --> workspace-bridge-ready
         --> channel Initialize frame --> v4 hello/clientHello/subscribe
```

---

## 4. RPC frame envelope

### 4.1 `rpc-frame` payload fields

Observed envelope accepted by the live relay (`rpc-frames.json`, `transcript-handshake.json`):

```jsonc
{
  "zcode_type": "rpc-frame",
  "bridgeSessionId": "bridge-c7632ec6-...",
  "bridgeGeneration": 1,
  "recoveryId": "<opaque>",          // optional (web bundle identity)
  "seq": 1,                          // per-direction monotonic counter
  "messageSeq": 1,                   // per-direction monotonic counter (ack key)
  "messageBytes": 6,                 // length of the logical (un-fragmented) message
  "checksum": { "algorithm": "crc32", "value": "b4ff6360" },
  "fragmentIndex": 0,
  "fragmentCount": 1,
  "dataBase64": "BAEGyAEA"
}
```

- `seq` and `messageSeq` both increment once per outbound message; observed equal in all captures.
- `messageBytes` = decoded length of the **whole logical message**, not the fragment.
- `fragmentIndex`/`fragmentCount` observed `0`/`1` (single frame) in every live capture.
- `checksum.algorithm` is the literal `"crc32"`; `value` matches `^[0-9a-f]{8}$`
  (`SHARED/wire.ts:7-13`).
- Envelope `dataBase64` uses **standard** base64 with padding (Node `buf.toString('base64')`).

### 4.2 `rpc-frame-ack`

```jsonc
{ "zcode_type": "rpc-frame-ack", "bridgeSessionId": "bridge-...", "bridgeGeneration": 1,
  "ackMessageSeq": 1 }
```

Sent by the receiver to release the peer's replay buffer (`RelayTransport.kt:260-274`,
`zcode-client.mjs:287-299`). The web bundle builds it as
`{zcode_type:"rpc-frame-ack", ...identity, ackMessageSeq}` so `recoveryId` may also appear.

### 4.3 What `dataBase64` carries

`dataBase64 = base64( serialize(header) + serialize(body) )` — the **bare channel payload**
(`zcode-client.mjs:132-142`, `MOB/docs/PROTOCOL.md:204`). Decoding the first server frame yields
`04 01 06 c8 01 00` = `[[200], undefined]` = `ResponseType.Initialize` (see §5).

### 4.4 13-byte header: relay vs direct socket

| Path | Header |
|---|---|
| Relay WS (`rpc-frame.dataBase64`) | **absent** — bare channel payload |
| Direct socket (CLI/desktop `SocketProtocol`/`PersistentProtocol`) | **present**, fixed 13 bytes prepended to the channel payload |

Direct-socket header (`PKG/rpc/src/protocol.ts:183-230`):

```
┌ type(1) ┬ id(4 BE) ┬ ack(4 BE) ┬ length(4 BE) ┐   HEADER_SIZE = 13
```

`wire-codec.ts:12-13` declares `SOCKET_PROTOCOL_HEADER_BYTES = 13` and `wire-codec.ts:50-51` adds it
only when accounting for the `SocketProtocol`/`PersistentProtocol` path.

### 4.5 Checksum algorithm

CRC-32, polynomial `0xEDB88320`, init `0xFFFFFFFF`, final XOR `0xFFFFFFFF`, lowercase 8-hex
(`SHARED/wire-binary.ts:10-19`; Kotlin `RelayTransport.kt` `Crc32.hex`; Node `zcode-client.mjs:148-157`).
Computed over the **decoded** bytes. Verified: `base64("BAEGyAEA")` → `b4ff6360`.

### 4.6 Fragment fields + reassembly rules

On the relay envelope: `fragmentIndex` (0-based), `fragmentCount`, `messageBytes` (logical length),
`checksum` over the whole logical message. (The relay-side reassembly is not open source; the OSS
v4 wire layer has its own independent fragment scheme in §8.)

---

## 5. Channel payload serialization (VQL)

Source: `PKG/rpc/src/serialization.ts` (mirrored in `zcode-client.mjs:21-142`).

### 5.1 Message = header + body

```
message = serialize(header) + serialize(body)
```

| Direction | header array |
|---|---|
| Request | `[RequestType, id, channelName, methodName]` |
| Request (cancel) | `[RequestType, id]` |
| Response | `[ResponseType, id]` (Initialize = `[200]`) |
| Event fire | `[ResponseType.EventFire, id]` |

`serialization.ts:143-151`. Each RPC message is two independently serialized values back-to-back.

### 5.2 Type tags (`serialization.ts:109-117`)

| Tag | Name | Layout after tag |
|---|---|---|
| 0 | Undefined | (no length, no data) |
| 1 | String | VQL len + UTF-8 bytes |
| 2 | Buffer | VQL len + raw bytes (Uint8Array → Buffer) |
| 3 | VSBuffer | VQL len + raw bytes |
| 4 | Array | VQL count + each element serialized recursively |
| 5 | Object | VQL len + UTF-8 JSON (`JSON.stringify`) |
| 6 | Int | VQL value |

### 5.3 VQL (varint)

7 bits per byte, high bit = continuation. `0`→`[0x00]`, `127`→`[0x7F]`, `128`→`[0x80,0x01]`
(`serialization.ts:78-103`). Reader accumulates `(b & 0x7f) << (7*n)` (`serialization.ts:67-76`).
Note: `writeInt32VQL` treats negative/`>2^31` values via `>>> 7`; non-31-bit-safe integers fall back
to the `Object` JSON encoding (`wire-codec.ts:38-42`).

### 5.4 RequestType / ResponseType numeric values

```
RequestType  = { Promise: 100, PromiseCancel: 101, EventListen: 102, EventDispose: 103 }
ResponseType = { Initialize: 200, PromiseSuccess: 201, PromiseError: 202,
                 PromiseErrorObj: 203, EventFire: 204 }
```

`zcode-client.mjs:35-48`; the `204` constant also appears as `CHANNEL_EVENT_RESPONSE_TYPE` in
`SHARED/wire-codec.ts:12`.

### 5.5 Observed initialize frame `04 01 06 c8 01 00`

```
04       Array           (DataType.Array)
01       VQL count = 1
06       Int             (DataType.Int)
c8 01    VQL value = 200 (0x48 | 0x80 = 0xc8, then 0x01 → 0x48 + 128 = 200)
00       Undefined       (DataType.Undefined)
```

= `serialize([200]) + serialize(undefined)` = **`ResponseType.Initialize`** — the channel-ready
signal. Byte-for-byte verified by `decodeMessage` → `[[200], undefined]`.

Other observed frames (`transcript-handshake.json`):

- hello call: header `[100,0,"zcode-agent","helloConversationV4"]`, body `[]`
  → bytes `04 04 06 64 06 00 01 0b "zcode-agent" 01 13 "helloConversationV4" 04 00`.
  (`06 64` = Int 100, `06 00` = Int 0, `01 0b` = String len 11, `01 13` = String len 19,
  `04 00` = empty array body.)
- `initializeConversationV4` body is an `Array` of one `Object` (JSON) element (`04 01 05 <len> <json>`).
- Event listen `onDynamicConversationFrame` body is a bare `Object` (tag `05`), **not** an array.

### 5.6 Argument convention (easy to get wrong)

The host dispatches via `target.apply(handler, args)` (`ProxyChannel.fromService`), so `call` args
**must be a positional array** — even a single parameter travels as a one-element list.
`listen` takes the single arg **directly** (not an array). `zcode-client.mjs:414-445`,
`MOB/docs/PROTOCOL.md:258-271`.

---

## 6. Handshake / negotiation RPCs

Channel name: **`zcode-agent`**. Method surface (`PKG/services/src/zcode-agent/zcodeAgent.ts:732-809`,
`zcodeAgentService.ts:4901-5044`).

| Method | Args (positional) | Returns |
|---|---|---|
| `helloConversationV4` | `[]` | `HelloMessage` |
| `initializeConversationV4` | `[clientHello]` | `void` |
| `subscribeConversationV4` | `[{workspacePath, sessionId, base?, visibility?}]` | `{ack}` |
| `unsubscribeConversationV4` | `[{...target, subscriptionId}]` | `void` |
| `resyncConversationV4` | `[{subscriptionId, base, forceSnapshot?}]` | `{ack}` |
| `sendConversationCommandV4` | `[{workspacePath, envelope}]` | `CommandAck` |
| `queryConversationCommandsV4` | `[{workspacePath, commands:[{sessionId,commandId}]}]` | results |
| `conversationRowsRangeV4` | `[{sessionId, beforeRowId?, limit<=200}]` | `{rows, atSeq, atRevision, atLogEpoch, hasMore}` |
| `conversationPlansV4` | `[{sessionId}]` | `{plans, atSeq, atLogEpoch}` |
| `conversationFileChangesV4` | `[{sessionId, target:{rowId,entityId}, baseRevision, baseLogEpoch}]` | file change summary |
| `attachment{Begin,Chunk,Commit,Abort}V4` | chunked upload, chunk ≤ 512 KiB | — |

Event (listen, arg is a bare object): **`onDynamicConversationFrame`** with `{workspacePath}` —
returns `ConversationTopicWireCandidate` (see §8). Registered **before** subscribe, or the first
snapshot is lost (`capture.mjs:181-185`).

### 6.1 `helloConversationV4` → `HelloMessage`

Schema `helloMessageSchema` (`SHARED/transport.ts:41-65`). Live response:

```json
{"kind":"hello","protocolVersion":3,
 "connectionId":"host-rpc-b8371591-f1c0-4411-898f-ce0a7c94ba8b",
 "clientMode":"web-remote-replayable","deliveryProfile":"replayable",
 "serverTime":1790350896263,
 "capabilities":{"nativeDialogs":false,"localTerminal":false,"binaryFrames":false,
                 "compression":"none","workspaceHookReview":true,"independentPlanState":true,
                 "workflowRunDeltas":true},"auth":{}}
```

`clientMode ∈ {desktop-continuous, web-remote-replayable}`; `deliveryProfile ∈ {continuous, replayable}`.
The schema `.superRefine`s that `clientMode === "desktop-continuous"` ⇔ `deliveryProfile === "continuous"`
(`transport.ts:55-64`). The relay path is **always** `web-remote-replayable` / `replayable`
(`createHello` + `deliveryProfileFor`, `zcodeAgentConnectionScope.ts:201-227`).
`hostCapabilitiesSchema` (`transport.ts:23-38`) keys: `nativeDialogs, localTerminal, binaryFrames,
compression ("none"|"permessage-deflate"), workspaceHookReview?, independentPlanState?,
workflowRunDeltas?`.

### 6.2 `initializeConversationV4(clientHello)` → `void`

`clientHelloSchema` (`SHARED/transport.ts:68-90`):

```jsonc
{"kind":"clientHello","protocolVersion":3,"clientId":"zcode-mobile-<uuid>",
 "clientKind":"mobileApp",                 // desktop | web | mobileRemote | mobileApp
 "appVersion":"0.1.0",
 "capabilities":{"workspaceHookReviewUi":true}   // OPTIONAL, .strict()
}
```

- `clientKind` enum: `desktop`, `web`, `mobileRemote`, `mobileApp` (optional).
- `capabilities` is **`.strict()`** and **optional**. ⚠ Strictness is **one-way**: a client may only
  declare keys the host already advertised in its `hello` (e.g. `workflowRunDeltas`); an unknown key
  makes the *whole* clientHello fail to parse and the handshake never completes
  (`transport.ts:79-84`).
- `clientId` is **bound** at initialize and must be reused verbatim in every
  `sendConversationCommandV4` envelope, else `fault.command.clientMismatch`
  (`zcodeAgentConnectionScope.ts:702-710`; `MOB/docs/PROTOCOL.md:479-481`).

### 6.3 Handshake enforcement (state machine, host side)

`zcodeAgentConnectionScope.ts` (`role = context.role ?? "terminal-client"`):

- `helloConversationV4` sets `helloIssued = true`.
- `initializeConversationV4` requires `helloIssued`, else **`fault.connection.helloRequired`**;
  a second, different `clientId` → **`fault.connection.clientChanged`**; success sets
  `handshakeComplete = true` and records `clientWorkflowRunDeltas`.
- Any gated RPC (subscribe/rows/attachments) before handshake → **`fault.connection.handshakeRequired`**
  (`zcodeAgentConnectionScope.ts:287`).
- `role === "trusted-host-relay"` starts already handshaked (the relay moves the downstream
  clientHello through).

### 6.4 `subscribeConversationV4`

Observed request/response:

```jsonc
// args[0]
{"workspacePath":"E:\\open_trae_m","sessionId":"sess_..."}   // "workspacePath", NOT "workspaceKey"
// response
{"ack":{"subscriptionId":"sub-mugp7yvi-dzgfqnud-48","mode":"snapshot",
        "logEpoch":"mugp7yvi-dzgfqnud",
        "openTiming":{"version":1,"initialFrameEncodeMs":7,"sessionRuntimeState":"warm",
                      "snapshotRowCount":1614,"hostPrepareMs":1,"cliProcessState":"reused",
                      "providerRegistrySyncMs":0,"taskMetaReadMs":1,"cliRequestMs":9}}}
```

Passing `workspaceKey` instead of `workspacePath` fails with
`Invalid params — workspace.workspacePath: ... expected string, received undefined`
(`run-log.txt`). `mode ∈ {snapshot, resume}` — `resume` only when a valid `base` water-mark was sent
(`transport.ts:121-127`). Response is ACK-only; the initial snapshot/resume frame is emitted as a
separate notification **after** the response line (`transport.ts:440-447`).

---

## 7. Snapshot + delta model

### 7.1 Topic frame wrapper

`createTopicFrameSchema` (`SHARED/transport.ts:159-177`):

```jsonc
{"topic":"conversation/sess_...","subscriptionId":"sub-...",
 "fromSeq":0,"toSeq":244033,"sentAt":1790350896961,
 "payload":{"kind":"snapshot","snapshot":{...}}}          // or
 "payload":{"kind":"deltas","deltas":[ ... ]}}
```

`fromSeq`/`toSeq` is a half-open range `(fromSeq, toSeq]`; a snapshot frame has `fromSeq = 0`
(`transport.ts:168-169`). Conversation frames additionally may carry `ttft`/`ttftRelated`
(`transport.ts:191-204`).

### 7.2 `ConversationSnapshot` — every top-level key

`conversationSnapshotSchema` (`SHARED/snapshot.ts:470-509`). Live snapshot top-level keys observed:
`protocolVersion, sessionId, logEpoch, seq, revision, control, availability, inputRouting, meta,
config, modelTransition, usage, queue, pendingInteractions, pendingCommands, backgroundWorks,
subagents, goal, plan, workspaceHookAdmission, rows`. (`sharedContextImport` and `workflowRuns` are
optional and were absent in the captured snapshot.)

| Key | Schema | Shape |
|---|---|---|
| `protocolVersion` | literal `1` | snapshot version (independent of wire version 3) |
| `sessionId` | string | `sess_...` |
| `logEpoch` | string | epoch id; `rowId` only meaningful within one epoch |
| `seq` | number | alignment water-mark (= frame `toSeq`) |
| `revision` | number | CAS revision |
| `control` | `sessionControlSchema` | `phase (draft\|prewarming\|running\|completedSuccess\|completedInterrupted\|error)`, `sessionEnded`, `canStop`, `stopState (idle\|stoppable\|stopping)`, `stopTargetKind`, `activeWorks[]{kind,foregroundExecutionId?,startedAt}`, `lastError` (nullable), `apiRetry` (nullable) |
| `availability` | `sessionActionAvailabilitySchema` | keys `fork, compact, switchModelConfig, setFollowupMode, queueEdit, sendQueuedNow, pauseGoal, resumeGoal`; each `{allowed:true}` or `{allowed:false, reasonCode}` |
| `inputRouting` | `inputRoutingSchema` | `{mode: startNow\|enqueue\|guide\|reject\|choice, reasonCode?}` |
| `meta` | `sessionMetaStateSchema` (default) | `{title, titleSource: default\|generated\|custom}` |
| `sharedContextImport` | optional | share-import provenance |
| `config` | `sessionConfigStateSchema` | `{provider, model, thought, thoughtLevels[], followupMode: queue\|guide, mode, modelSelection?, planEnabled?, permissionGrant?, planTransition?}` |
| `modelTransition` | nullable, default `null` | `{eventId, origin:"registryFallback", from:{provider,model}, to:{provider,model}}` |
| `usage` | `sessionUsageStateSchema` | `{contextWindow: {usedTokens,maxTokens,autoCompactThresholdTokens,cache?,breakdown?} \| null, cumulative:{inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens}}` |
| `queue` | `queueStateSchema` | `{items:[queueItem], autoDrain, pauseReason?: stopped\|manual\|error}` |
| `pendingInteractions` | array | `{interactionId, kind: permission\|userInput\|workspaceHookReview, anchorRowId, createdAt, autoResolution?, payload}`; `kind` must equal `payload.kind` |
| `pendingCommands` | array | `{commandId, clientId, type, state: accepted\|executing, at}` |
| `backgroundWorks` | array | `{workId, kind: bash\|subagent\|workflow, title, status: running\|resultPending\|failed\|cancelled, startedAt, endedAt?, cancellable?, blocked?, anchorRowId, childSessionId?}` |
| `subagents` | optional | `{revision, childSessionIds[], running:[{childSessionId,agentId?,toolCallId?,subagentType,title,summary?,status,startedAt?}], endedTotal}` |
| `workflowRuns` | optional | `workflowRunsStateSchema` (absent in capture) |
| `goal` | nullable | `{targetId, objective, summaryTitle, timeUsedSeconds, activeRunStartedAtMs, status: active\|paused\|verifying\|verified\|notSatisfied\|failed, iteration, verifications[], iterations[]}` |
| `plan` | nullable | `{items:[{id,content,status: pending\|inProgress\|completed}], updatedAt}` |
| `workspaceHookAdmission` | nullable, default `null` | `{pendingCount, bundleDigest, workspaceIdentity?}` |
| `rows` | `rowsWindowSchema` | `{window:[ConversationRow] (rowId asc, tail window, ≤60), totalCount, firstRowId}` |

A-zone update semantics = **field-level whole replacement** via `state.updated`; never deep merge
(`snapshot.ts:3`).

### 7.3 `ConversationDelta` — every op

`conversationDeltaSchema` (`SHARED/delta.ts:94-144`). Closed set of 7 ops:

| op | Fields | Semantics |
|---|---|---|
| `row.appended` | `row` | append to tail (~99%) |
| `row.upserted` | `row` | whole-row replace by `rowId`; append if new |
| `row.removed` | `fromRowId` | delete this row and **all** rows with `rowId >= fromRowId` |
| `row.delta` | `rowId`, `path`, `append` | streaming text append; `path ∈ {text, inputText, output.text, summaryText}`; top-level fields (no `target` wrapper) |
| `state.updated` | `patch` (`statePatchSchema`) | key-level whole replacement (see §7.5) |
| `workflowRun.updated` | `runId`, `revision`, `run?`, `cleared?`, `removedActors?`, `removedNodes?`, `actors?`, `nodes?` | key-level delta for one run; apply order header → removes → upserts |
| `workflowRun.removed` | `runId`, `revision` | producer evicted this run |

There is **no** `row.inserted`, `row.moved`, or field-level JSON patch; anything the model can't
express is sent as a snapshot resync (`delta.ts:1-8`).

### 7.4 Row discriminant `kind` + every row kind

`conversationRowSchema = z.discriminatedUnion("kind", [...])` (`SHARED/rows.ts:420-432`).

**RowBase** (`rows.ts:10-30`): `rowId`, `turnId`, `entityId?`, `productTurnId?`,
`visibility?: "visible"`, `createdAt`, `createdAtSeq`, `actions?: {canFork?,canEdit?,canRetry?,canRewindFiles?,editDisposition?: rewind|fork}`.

| `kind` | Exclusive fields |
|---|---|
| `turnHeader` | `origin (userInput\|backgroundResult\|goalContinuation\|editRerun\|workflowLaunch)`, `executionKind?: agent\|controlOnly`, `sourceCommandId?`, `historyRoundCount?`, `state (running\|completedSuccess\|completedInterrupted\|failed)`, `startedAt`, `endedAt?`, `activeMs?`, `workSegments?`, `originMeta?`, `workflowLaunch?`, `fileChanges?{additions,deletions,files,state?}` |
| `userInput` | `text`, `epilogueStart?`, `origin (realUser\|backgroundResult\|goalContinuation\|mailbox\|synthetic\|workflowLaunch)`, `originMeta?{backgroundSource?,workId?,senderSessionId?,senderLabel?}`, `workflowLaunch?`, `guided?: true`, `sourceCommandId?`, `rootSourceCommandId?`, `clientId?`, `attachments?[]` |
| `assistantText` | `assistantResponseId?`, `text`, `state (streaming\|complete\|interrupted\|failed)`, `model?`, `feedback?: like\|dislike` |
| `reasoning` | `assistantResponseId?`, `text`, `state (streaming\|complete\|interrupted)`, `durationMs?` |
| `toolCall` | `assistantResponseId?`, `toolCallId`, `toolName`, `status (inputStreaming\|pendingApproval\|running\|success\|error\|cancelled)`, `inputText`, `input?`, `cuaApp?`, `output?`, `display?`, `error?{code,message}`, `progress?`, `outputPreview?`, `approvalInteractionId?`, `backgrounded?: true`, `workId?`, `startedAt?`, `endedAt?` |
| `artifact` | `artifactVersionId`, `logicalArtifactKey`, `displayName`, `artifactType (pdf\|pptx\|docx\|xlsx\|image\|html\|md\|text)`, `mimeType`, `sizeBytes`, `sha256`, `ref`, `state:"current"` |
| `subagent` | `parentToolCallId?`, `subagentType`, `status (running\|success\|failed\|cancelled)`, `summaryText`, `childSessionId?`, `backgrounded?: true`, `workId?`, `startedAt?`, `endedAt?` |
| `hookInvocation` | `hookInvocationId`, `hookEventName`, `hookCount`, `state (running\|completed\|failed)`, `startedAt`, `endedAt?`, `durationMs?`, `lane (assistantWork\|toolBefore\|toolAfter)`, `anchorToolCallId?`, `executions[]` |
| `timelineMarker` | `sourceCommandId?`, `lane?`, `marker` (union: `compact`, `forkNotice`, `forkCreated`, `modelChange`, `goalSet`, `goalVerify`, `retryNotice`, `checkpointRestored`) |

Live capture window kinds: `toolCall`, `reasoning`, `assistantText`, `turnHeader`, `userInput`.

### 7.5 `state.updated` merge semantics

`statePatchSchema` (`SHARED/delta.ts:39-62`) keys:
`revision, control, sharedContextImport, availability, inputRouting, meta, config, modelTransition,
usage, queue, pendingInteractions, pendingCommands, backgroundWorks, subagents, workflowRuns, goal,
plan, workspaceHookAdmission`.

- **Key-level whole replacement (Object.assign). Never deep merge. Key set is closed.**
  (`delta.ts:38`)
- Absent keys are untouched; a key present with a value (including `[]`, `null`) replaces the whole key.
  `pendingInteractions: []` means "clear" — clients must key on **key presence**, not truthiness, or
  approval dialogs won't dismiss (`MOB/docs/PROTOCOL.md:448-449`).
- `revision` is the CAS/`baseRevision` source; `stale` command ACKs mean a version mismatch.
- `goal`/`plan`/`modelTransition`/`workspaceHookAdmission` are nullable in the patch.
- **Coalesce rules** (`SHARED/coalesce.ts`): adjacent `state.updated` shallow-merge patches
  (later key wins, safe because values are whole-replaced); `row.removed` is a barrier; adjacent same
  `(rowId, path)` `row.delta` concatenate; a `row.upserted` swallows earlier same-rowId `row.delta`;
  same-runId `workflowRun.updated` merges toward the **last** one unless a `workflowRuns` barrier sits
  between them.

---

## 8. Frame wire envelope (`onDynamicConversationFrame`)

`TopicWireFrame` (`SHARED/wire.ts:19-43`); candidate validation `topicWireFrameCandidateSchema`
(`wire.ts:49-83`). Live event payload (`transcript-handshake.json` `events[]`):

```jsonc
// complete
{"wireVersion":3,"kind":"complete","deliveryKind":"initial",
 "logicalFrameId":"sub-mugp7yvi-dzgfqnud-48-lf-43381","logicalFrameOrdinal":1,
 "topic":"conversation/sess_...","subscriptionId":"sub-mugp7yvi-dzgfqnud-48",
 "frame":{ "topic":"conversation/sess_...","subscriptionId":"sub-...",
           "sentAt":1790350896961,"fromSeq":0,"toSeq":244033,
           "payload":{"kind":"snapshot","snapshot":{...}} }}

// fragment
{"wireVersion":3,"kind":"fragment","deliveryKind":"online",
 "logicalFrameId":"...","logicalFrameOrdinal":2,
 "topic":"conversation/sess_...","subscriptionId":"sub-...",
 "fragmentIndex":0,"fragmentCount":3,"logicalBytes":12345,
 "checksum":{"algorithm":"crc32","value":"a1b2c3d4"},"dataBase64":"..."}
```

| Field | Notes |
|---|---|
| `wireVersion` | literal `3` (`V4_WIRE_PROTOCOL_VERSION`, `SHARED/core.ts:7`) |
| `kind` | `"complete"` \| `"fragment"` (discriminant) |
| `deliveryKind` | `"initial"` \| `"online"` \| `"recovery"` (`wire.ts:16`) — publisher-authoritative, consumers must not infer from RPC order |
| `logicalFrameId` | opaque id; fragment group key |
| `logicalFrameOrdinal` | positive int, monotonic **per (topic, subscriptionId)**; used for ordering/tombstones |
| `topic` | `conversation/<sessionId>` \| `sessions-index/<workspaceId>` \| `workspace-config/<workspaceId>` |
| `subscriptionId` | generation id, prevents old-stream interleave |
| `frame` | complete only; the `ConversationTopicFrame` (must match `topic`/`subscriptionId`) |
| `fragmentIndex` | 0-based |
| `fragmentCount` | ≥1, ≤ `logicalFrameAssemblyMaxFragments` (1024) |
| `logicalBytes` | UTF-8 byte length of the whole JSON frame |
| `checksum` | `{algorithm:"crc32", value:"^[0-9a-f]{8}$"}` over the reassembled UTF-8 bytes |
| `dataBase64` | base64 of one UTF-8 byte slice (`topicWireBase64Schema`) |

### 8.1 Distinguishing snapshot vs deltas

Inner `frame.payload` is a discriminated union on `kind`:
`{kind:"snapshot", snapshot}` | `{kind:"deltas", deltas:[...]}` (`SHARED/transport.ts:172-175`).
Snapshot frames carry `fromSeq = 0`; delta frames carry the `(fromSeq, toSeq]` range.

### 8.2 Reassembly + limits

`encodeTopicWireFrames` (`wire-codec.ts:169-240`) emits `complete` when the measured envelope fits,
else splits the **UTF-8 bytes** into `fragmentCount` chunks (binary-search byte budget).
Assembler/reassembler (`wire-assembler.ts`, `wire-reassembly.ts`) accept fragments out of order,
reject conflicts, and only produce a frame after **length + crc32 + UTF-8 + JSON + zod schema** all pass.
Limits (`SHARED/core.ts:64-100`): `maxFrameBytes` 1 MiB, `logicalFrameAssemblyMaxBytes` 16 MiB,
`logicalFrameAssemblyMaxFragments` 1024, `...MaxConcurrent` 32, `...MaxStagedBytes` 32 MiB,
`...TimeoutMs` 30 000.

---

## 9. Gotchas

1. **No 13-byte header on the relay path.** `rpc-frame.dataBase64` = bare
   `serialize(header)+serialize(body)`. Prepending the SocketProtocol header corrupts the payload.
2. **Args are positional arrays** for `call`; `listen` takes a bare arg. A single-parameter method
   still travels as a one-element array.
3. **`clientHello.capabilities` is `.strict()` and one-way.** Only declare keys the host advertised
   in `hello`. An unknown key fails the whole clientHello → connection never handshakes.
4. **`clientId` is bound at `initializeConversationV4`** and must be identical in every command
   envelope, else `fault.command.clientMismatch` (silently rejected otherwise).
5. **`subscribeConversationV4` needs `workspacePath`, not `workspaceKey`.**
6. **Register `onDynamicConversationFrame` before subscribing**, or the initial snapshot is lost.
7. **`row.upserted` is a whole-row replace, not an append.** Treating it as append duplicates the
   transcript.
8. **Streaming append op is `row.delta`** with top-level `rowId`/`path`/`append` — there is no
   `row.path.appended` and no `target` wrapper.
9. **`state.updated` is key-level whole replacement, never deep merge.** `pendingInteractions: []`
   clears; key on presence.
10. **Ordering:** `logicalFrameOrdinal` is monotonic per `(topic, subscriptionId)`; late lower
    ordinals are dropped, and a same-ordinal/different-id conflict is a fault. Only an authoritative
    `deliveryKind:"recovery"` frame may supersede a partial assembly without faulting.
11. **Fragment reassembly:** accept out-of-order, dedupe identical re-sends, reject byte conflicts;
    verify `crc32` over the concatenation, then UTF-8 decode with `fatal:true`, then `JSON.parse`,
    then zod. The `frame.topic`/`frame.subscriptionId` must equal the envelope's.
12. **`checksum.value` is 8 lowercase hex** (zero-padded). `crc32` = poly `0xEDB88320`, init/final
    `0xFFFFFFFF`.
13. **Ack every inbound `rpc-frame`** with `rpc-frame-ack` `ackMessageSeq = messageSeq`; otherwise the
    peer's replay buffer grows unbounded.
14. **`server_ts` is seconds; `client_ts` is milliseconds.** Timestamps are CLI-clock only; never
    subtract local clock (`SHARED/core.ts:18`).
15. **`availability.allowed:false` is a UI hint, not a server gate** — commands may still be accepted
    (`MOB/docs/PROTOCOL.md:571-573`).
16. **`t` in the link is issue time, not expiry** — a link stayed valid ~76 min.
17. **Deterministic content faults** (`proto.frameAssemblyInvalidPayload`, a schema rejection after
    crc32/JSON pass) are **not** transient: a resend re-delivers identical bytes and is rejected
    identically. Escalate to a snapshot instead of retrying (`SHARED/wire-fault.ts:21-39`).
18. **`logEpoch` gates `rowId`.** A `rowId` is only meaningful within one epoch; on epoch change
    discard the whole read result (`transport.ts:524-527`, `630-643`).

---

## Appendix — observed artifacts

| File | Contents |
|---|---|
| `MOB/tools/relay-probe/transcript-handshake.json` | full both-direction wire log + decoded channel frames/events |
| `MOB/tools/relay-probe/rpc-frames.json` | relay `rpc-frame` envelopes (initialize frame `BAEGyAEA`) |
| `MOB/tools/relay-probe/bootstrap.json` | `bootstrap-response.result` (workspaces, tasks, view state) |
| `MOB/tools/relay-probe/snapshot.json` | one complete `ConversationTopicFrame` snapshot |
| `MOB/tools/relay-probe/deltas.json` | observed `row.upserted` / `state.updated` deltas |
| `MOB/tools/relay-probe/zcode-client.mjs` | Node reference client (transport + channel + VQL) |
| `MOB/protocol/src/main/kotlin/dev/zcodemobile/protocol/RelayTransport.kt` | Kotlin reference transport |
| `MOB/docs/PROTOCOL.md` | prior reverse-engineering notes |
