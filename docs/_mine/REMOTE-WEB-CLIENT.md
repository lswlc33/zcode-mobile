# Remote Web Client (`https://zcode.z.ai/remote/v4`) — Protocol Reconstruction

Scope: ZCode 3.14.3 OSS tree at `E:/open_trae_m/ZCode_full` plus the live served bundle
`E:/open_trae_m/_relay_probe/index-B-ilXaCQ.js` (4.8 MB, minified) and its lazily-loaded
sibling chunk `assets/src-wmk2orCZ.js` (285 KB, downloaded from the CDN during this analysis
to `E:/open_trae_m/_relay_probe/src-wmk2orCZ.js`).

**Headline finding:** the *phone/relay* remote-web client (`/remote/v4?sid=..&hash=..`) is
**not** present as TypeScript in this OSS snapshot. Neither is the relay host (the thing that
issues `sid`/`hash`, runs `auth_challenge`, and enforces "one phone at a time"). Those are
closed-source components delivered as the served bundle and the `zcode.z.ai` relay service.
What *is* in OSS is (a) the shared v4 wire protocol, (b) the UI-side v4 transport layer that
the SPA reuses, (c) the self-hosted `zcode-server` WebSocket channel that is the OSS analogue
of the relay, and (d) the `/remote/v3`-style web SPA entry (`packages/web`). Everything
phone-specific below is reconstructed from the bundle, with exact minified snippets.

Evidence locations:
- `index-B-ilXaCQ.js` offsets are byte offsets into that file.
- `src-wmk2orCZ.js` is the Vite chunk that the entry imports as `src-wmk2orCZ.js`; the entry's
  alias map line `...,oc as Mae,...}from"./src-wmk2orCZ.js"` at index offset ~64655.

---

## 1. Where the SPA is built / served, and which files implement it

### 1.1 Build + serving
- The bundle's own `import.meta.env` shim proves the deployed build:
  ```js
  function jT(){return{BASE_URL:`/remote/v4/latest/`,DEV:!1,MODE:`production`,PROD:!0,SSR:!1,VITE_WEB_R...}}
  ```
  (index offset ~795652). So the SPA is served as **static assets under `/remote/v4/latest/`**,
  with `index.html` as the shell (already saved as `E:/open_trae_m/_relay_probe/index.html`).
  All chunk/image URLs are absolute under that base, e.g.
  `` new URL(`/remote/v4/latest/assets/icon_512@2x-BnwZhfO-.png`) `` (offset ~2561227).
- The route segment is version-selected by the endpoint config in `src-wmk2orCZ.js`:
  ```js
  a = Zd(t.appVersion) ? `v4` : `v3`;
  return { origin:n, apiBaseUrl:`${n}/api/v1`, remoteUrl:`${n}/remote/${a}`,
           webRemoteCallbackUrl:`${n}/web-remote/callback`,
           webShareCallbackUrl:`${n}/cn/share/callback`, relayWsUrl:`${i}/ws`, ... }
  ```
  (chunk offset ~136711). `Zd()` is a semver validator, so `app_version=3.14.3` ⇒ `/remote/v4`.
- In the OSS tree this maps to `packages/web` (Vite app, `packages/web/vite.config.ts`,
  `packages/web/src/main.tsx`). Its route/env plumbing explicitly anticipates the versioned
  remote build: `packages/web/src/env.d.ts:6` ("手机远控按构建 base 区分 /remote 和 /remote/v3")
  and declares `VITE_WEB_REMOTE_CONTROL_ROUTE_PATH` (env.d.ts:14) and
  `VITE_ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL` (env.d.ts:17). Those two env vars are
  **declared but never read anywhere in the OSS source** (grep: only env.d.ts + 
  `packages/web/src/auth/webZaiOAuthConfig.ts:16,65` for the unrelated `...ALLOW_DEV_RETURN_TO`),
  confirming the remote-control entry point is injected by the closed build.
- The OSS web entry (`packages/web/src/main.tsx`) *is* the same code as the bundle's `K4t`/`M4t`/
  `u0t` functions (identical strings `ZCode - Web + Server`, `/api/server-info`,
  `connectViaWebSocket`). So `packages/web` is the base of the SPA; the phone routes are added on
  top in the closed build. The bundle's `K4t()` boot dispatcher is:
  ```js
  function K4t(){let e=new URLSearchParams(window.location.search);
    if(y4t(e)){C4t();return}                       // /web-remote/callback OAuth
    if(B2t(window.location.pathname)){await w4t();return}  // /share landing
    if(L4t()){await W4t(e);return}                 // <<< /remote/v4 phone flow
    if(e.has(`remoteControlToken`)){...G4t(e)...}  // desktop window-control flow
    if(x4t(e)){...}                                // /web-remote login
    let t;try{t=await M4t()}catch...;              // plain self-hosted web SPA
    ...connectViaWebSocket(t.wsUrl...)
  }
  ```
  (index offset ~4798188). `L4t()` decides the phone flow by path:
  ```js
  function L4t(){return Cre(window.location.pathname, Noe(`/remote/v4`?.trim()||`/remote/v4/latest/`))}
  ```
  (offset ~4778903) — a prefix compare against `/remote/v4`.

### 1.2 What the host side does (OSS analogue)
The relay host itself is closed source. The OSS `zcode-server` implements the equivalent
host role and is the reference for `clientMode`/handshake semantics:

- `packages/server/src/http.ts:86-125` — `setupChannelServer(ws, services, clientMode)`.
  It builds a `ChannelServer`, wraps the agent service in
  `createZCodeAgentConnectionScope(agentService, { connectionId, clientMode,
  role: clientMode === "desktop-continuous" ? "trusted-host-relay" : "terminal-client" })`,
  and **strips privileged services** for non-desktop clients:
  ```ts
  // http.ts:109-119
  if (clientMode !== "desktop-continuous" && services.getOptional(IProviderProvisioningTargetService)) {
    overrides.set(IProviderProvisioningTargetService.channelName, {
      apply: async () => { throw new Error("Provider Provisioning 仅支持受信 Desktop Host"); },
    });
  }
  ```
- Routes: `/ws` is **always** `web-remote-replayable` (`http.ts:325-332`), `/ws/host` is
  `desktop-continuous` gated by a one-shot host capability header (`http.ts:334-346`), and
  `/ws/remote/:id` (`http.ts:418-447`) bridges a `RemoteConnection` and is **consumed on first
  use** (`remoteConnections.delete(id)` before upgrade) — a single-use remote endpoint.
- `packages/services/src/zcode-agent/zcodeAgentConnectionScope.ts:205-227` — the trusted host's
  `hello` is generated here (`createHello`). This is the authoritative shape of the `hello`
  the phone client receives (see §2).

### 1.3 The relay host (closed) — what we can still prove
- Default relay endpoint constant lives in the bundle chunk:
  ```js
  ... Yd=`wss://zcode.z.ai/ws`, Xd=`3.4.0`;
  ```
  (chunk offset ~134364).
- Resolver used by the phone client (`Mae` = export `oc` of the chunk):
  ```js
  function sf(e){return e?.overrideUrl?.trim() ||
    (e?.endpointOrigin?.trim()===`https://zcode.chatglm.site`?`wss://zcode.chatglm.site/ws`:Yd)}
  function B4t(){return Mae({endpointOrigin:`https://zcode.z.ai`?.trim()||`https://zcode.z.ai`?.trim(),overrideUrl:void 0})}
  ```
  (chunk offset ~136008; index offset ~4787149). ⇒ phone WS URL is `wss://zcode.z.ai/ws`.
- The relay's HTTP/WS surface (from the *other* `remoteControlToken` flow, `G4t`/`N4t`/`F4t`):
  `GET ${relayOrigin}/api/remote-control/windows/bootstrap/${token}`,
  `WS  ${wsOrigin}/ws/remote-control/window/${token}`,
  `POST ${relayOrigin}/api/remote-control/platform/${token}` body `{method,args}`.
  These are for the desktop-window flow, **not** the `sid`/`hash` phone flow; the phone flow
  speaks only the raw relay protocol on `/ws`.

---

## 2. Client connect sequence (v4 UI transport)

Two layers must be distinguished:

### 2.1 The v4 application handshake (shared OSS code, used by the phone SPA)
Implemented in `packages/ui/src/v4/agentV4ConnectionHandshake.ts`. One handshake **per
`agentService` proxy**, memoised in a `WeakMap` so all conversation + sessions-index
transports share it (`agentV4ConnectionHandshake.ts:18-51`). Exact sequence:

1. `hello = helloMessageSchema.parse(await service.helloConversationV4())`
   — host→client `hello` (`transport.ts:41-65`).
2. Build capabilities (note the one-way rule and `.strict()`):
   ```ts
   const capabilities = {
     workspaceHookReviewUi: true,
     ...(hostSupportsWorkflowRunDeltas(hello.capabilities) ? { workflowRunDeltas: true } : {}),
   };
   ```
3. `await service.initializeConversationV4({ kind:"clientHello", ... })` — client→host.
4. Then (per transport, not here) subscribe; frames flow.

`helloMessageSchema` (host→client), `packages/shared/src/zcode-protocol-v4/transport.ts:41-65`:
```ts
z.object({
  kind: z.literal("hello"),
  protocolVersion: z.literal(V4_WIRE_PROTOCOL_VERSION),   // = 3 (core.ts:7)
  connectionId: z.string(),
  clientMode: z.enum(["desktop-continuous", "web-remote-replayable"]),
  deliveryProfile: z.enum(["continuous", "replayable"]),
  serverTime: timestampSchema,
  capabilities: hostCapabilitiesSchema,   // nativeDialogs, localTerminal, binaryFrames,
                                          // compression, workspaceHookReview?, independentPlanState?,
                                          // workflowRunDeltas?
  auth: z.object({ userId: z.string().optional() }),
}).strict().superRefine(...)   // deliveryProfile must equal continuous<=>desktop-continuous
```

`clientHelloSchema` (client→host), `transport.ts:68-90`:
```ts
z.object({
  kind: z.literal("clientHello"),
  protocolVersion: z.literal(3),
  clientId: z.string(),
  clientKind: z.enum(["desktop","web","mobileRemote","mobileApp"]).optional(),
  appVersion: z.string(),
  capabilities: z.object({ workspaceHookReviewUi: z.boolean().optional(),
                           workflowRunDeltas: z.boolean().optional() }).strict().optional(),
}).strict()
```

**Literal clientHello the deployed phone SPA actually sends** (index offset ~496591, exactly
matching `agentV4ConnectionHandshake.ts:35-44`):
```js
await e.initializeConversationV4({
  kind:`clientHello`,
  protocolVersion:3,
  clientId:xCe(),                                        // persisted V4 clientId (getV4ClientId)
  clientKind: t.clientMode===`desktop-continuous`?`desktop`:`web`,
  appVersion:`unknown`,
  capabilities:{workspaceHookReviewUi:!0}
})
```
Because the phone connection's `clientMode` is `web-remote-replayable` (§5), `clientKind` is
**`"web"`**, not `mobileRemote`. The bundled client's own `clientHelloSchema` copy even omits
`workflowRunDeltas`:
```js
Ta({kind:la(`clientHello`),protocolVersion:la(3),clientId:Vi(),
    clientKind:La([`desktop`,`web`,`mobileRemote`,`mobileApp`]).optional(),appVersion:Vi(),
    capabilities:Ta({workspaceHookReviewUi:Vo().optional()}).strict().optional()}).strict()
```
(index offset ~102012). So the deployed phone client never declares `workflowRunDeltas`.

Host enforcement of this handshake, `zcodeAgentConnectionScope.ts:668-683`:
```ts
async helloConversationV4() { assertOpen(); helloIssued = true; return createHello(context); }
async initializeConversationV4(clientHello) {
  assertOpen();
  if (!helloIssued) throw new Error("fault.connection.helloRequired");
  const parsed = clientHelloSchema.parse(clientHello);
  if (boundClientId !== null && boundClientId !== parsed.clientId)
    throw new Error("fault.connection.clientChanged");   // one clientId per connection
  boundClientId = parsed.clientId;
  clientWorkflowRunDeltas = clientSupportsWorkflowRunDeltas(parsed);
  handshakeComplete = true;
}
```
`createHello` always advertises `capabilities:{ nativeDialogs:continuous, localTerminal:continuous,
binaryFrames:false, compression:"none", workspaceHookReview:true, independentPlanState:true,
workflowRunDeltas:true }` and `auth:{}` (`zcodeAgentConnectionScope.ts:205-227`).

### 2.2 Subscribe → listen ordering (conversation)
`packages/ui/src/v4/agentConversationTransport.ts:187-272` (`subscribe`) and `:527-575`
(`onFrame`):
1. `await ensureHandshake()` (runs §2.1 once).
2. `pending = barrier.begin(topic)` — registers a pending ACK barrier **before** the RPC.
3. `result = await agentService.subscribeConversationV4({workspacePath, workspaceIdentity?,
   sessionId, base?, visibility?})`.
4. `barrier.bind(pending, result.ack.subscriptionId)`; on bind failure the client
   `unsubscribeConversationV4` to undo the host subscription (`:229-243`).
5. `topicBySubscriptionId.set(result.ack.subscriptionId, params.topic)`.
6. Frames: `onFrame(listener)` lazily attaches the upstream on first listener —
   `agentService.onDynamicConversationFrame(workspace)(frame => barrier.accept(frame))`
   (`:546-547`). Listeners are attached **before** the first subscribe: the store registers
   `transport.onFrame(...)` then calls `transport.subscribe(...)` then `transport.activate(...)`
   (`packages/ui/src/v4/sessionsIndexStore.ts:216, 240, 262`; conversation analogue in
   `sessionDataLayer.ts:62`). Frames that arrive before the ACK are staged by the barrier and
   released by `activate()`.

`sessions-index` is isomorphic: `agentSessionsIndexTransport.ts:116-151` subscribes with
`runtimePolicy:"existing-only"`, topic = `sessions-index/<workspaceKey>`; registry/reuse and
30 s keep-warm live in `sessionsIndexRegistry.ts:143-157` and
`workspaceConnectionRegistry.ts:159-227`.

### 2.3 The relay-level connect sequence (phone transport, bundle-only)
The phone SPA does **not** speak v4 directly over WS. It speaks a relay control protocol,
inside which the v4 RPC is tunnelled. `W4t(e)` (index offset ~4787903) is the entry:

```js
async function W4t(e){                       // e = URLSearchParams
  let t = goe(e);                            // = pO(e): parse sid/hash/t/mid/name/app_version/theme
  if(!t){ G9(K9(`invalid-mobile-connection`,`Missing or invalid Web remote control relay parameters.`)); return }
  r4t(t.theme); R4t(t.deviceSid);            // theme + per-device clientId in localStorage
  ...
  let E = new X2t({                          // the relay transport (class X2t)
    relayWsUrl:B4t(),                        // wss://zcode.z.ai/ws
    deviceSid:t.deviceSid, passHash:t.passHash, deviceMid:t.deviceMid, appVersion:t.appVersion,
    authProvider:U2t(),                      // {calculateProof:H2t}
    onRawTransportPayload:e=>d?.acceptPayload(e)??!1, ...
  });
  ...
}
```

Exact ordered message flow (`X2t` class, index offset ~4739263):

1. **WS open** → `auth_init` (raw JSON, not wrapped):
   ```js
   t.addEventListener(`open`,()=>{ ...
     this.setState(`authenticating`),
     this.send({type:`auth_init`,role:`terminal`,device_sid:this.options.deviceSid,
                meta:{platform:`web`,version:this.options.appVersion??`web`,name:`mobile-browser`},
                client_ts:Date.now()}) })
   ```
2. **`auth_challenge`** (server→client, carries `nonce`) → `auth_response`:
   ```js
   case`auth_challenge`:this.send({type:`auth_response`,device_sid:this.options.deviceSid,
     proof:await this.options.authProvider.calculateProof(this.options.passHash,t.nonce,`terminal`,this.options.deviceSid),
     client_ts:Date.now()});break;
   ```
3. **`auth_ack` / `pair_status_ack`** → `applyPairStatus(t.pair_status)` where `pair_status`
   ∈ `{"waiting","matched"}`. `matched` ⇒ state `paired`, heartbeat starts, and
   `onSendReady` triggers `replayUnacknowledged()`.
4. **`data`** (both directions) → payload dispatched. Client→server control payloads are
   wrapped by `S0t`:
   ```js
   prepare(e,t=Date.now()){ ... let r={type:`data`,payload:e,client_ts:t}, i=JSON.stringify(r) ... }
   ```
   (index offset ~4700302). Server→client `data` payloads are dispatched through
   `onPayload` / the RPC bridge.
5. **Heartbeat**: `pair_status_query` every ~10 s (jitter), 30 s ACK watchdog:
   ```js
   sendPairStatusQuery(){return this.send({type:`pair_status_query`,device_sid:this.options.deviceSid,client_ts:Date.now()})}
   ```
6. **`error`** → `handleRelayError(code,message)`; codes `KICKED`, `DEVICE_OFFLINE`,
   `AUTH_FAILED`, `WRONG_PARAM`, `INTERNAL` (see §6).

After pairing, the client bootstraps the workspace over the control plane (all via
`sendPayload`, i.e. wrapped in `{type:"data",payload,...}`), in order:

| step | client sends (`zcode_type`) | expects reply |
|---|---|---|
| bootstrap | `bootstrap-request {requestId}` | `bootstrap-response {requestId,result.workspaces[]}` |
| list | `workspace-list-request {requestId}` | `workspace-list-response {requestId,result}` |
| open bridge | `workspace-bridge-open {requestId,bridgeSessionId,bridgeGeneration,recoveryId?,workspaceKey,taskId?}` | `workspace-bridge-ready {bridgeSessionId, bridge:{...}}` |
| view state | `mobile-view-state-update {viewState:{activeWorkspaceKey,activeTaskId?,updatedAt}, deviceInfo}` | (fire-and-forget) |
| reconnect | `workspace-reconnect-request {requestId,workspaceKey}` | `workspace-reconnect-response {requestId,workspaceKey,success,error?}` |
| platform | `platform-request {requestId,method,args}` | `platform-response {requestId,method,success,result/error}` |

The bridge then carries the v4 RPC as `rpc-frame` messages (see §4). `deviceInfo` is built by
`u4t()` and is always `{platform:"web", version:appVersion??"web", name:"mobile-browser", userAgent?,
language?, languages?, browserPlatform?, viewport?, screen?, timezone?, online?, updatedAt}`
(index offset ~4763771).

---

## 3. Confirmed auth-proof formula

**Confirmed, with one correction to the hypothesis:** the role segment is the literal string
`terminal` (not a variable role), and the third segment is the `sid` query param (called
`deviceSid`).

```
proof = base64url_nopad( HMAC_SHA256( key = hash, msg = "<nonce>|<role>|<sid>" ) )
      = base64url_nopad( HMAC_SHA256( key = hash, msg = nonce + "|" + "terminal" + "|" + sid ) )
```
where `nonce` comes from the server's `auth_challenge`, `hash` is the `hash` query parameter,
and `sid` is the `sid` query parameter.

Exact minified evidence (index offset ~4738700 and ~4738200):
```js
function H2t(e,t,n,r){let i=new TextEncoder,
  a=await globalThis.crypto.subtle.importKey(`raw`,i.encode(e),{name:`HMAC`,hash:`SHA-256`},!1,[`sign`]),
  o=await globalThis.crypto.subtle.sign(`HMAC`,a,i.encode(`${t}|${n}|${r}`));
  return V2t(new Uint8Array(o))}
function U2t(){return{calculateProof:H2t}}

function V2t(e){return btoa(String.fromCharCode(...e))
  .replace(/\+/g,`-`).replace(/\//g,`_`).replace(/=+$/,``)}
```
Call site (index offset ~4743628):
```js
case`auth_challenge`:this.send({type:`auth_response`,device_sid:this.options.deviceSid,
  proof:await this.options.authProvider.calculateProof(this.options.passHash,t.nonce,`terminal`,this.options.deviceSid),
  client_ts:Date.now()});break;
```
So `e=passHash` (the `hash` param), `t=nonce`, `n="terminal"`, `r=deviceSid` (the `sid` param).
`V2t` is standard base64 then `+`→`-`, `/`→`_`, strip `=`. No URL-encoding of the `|` inside the
message (it is signed raw). The proof is sent verbatim in `auth_response.proof`.

Query-param parser (chunk offset ~275470, exported as `goe`/`pO`):
```js
function lO(e,t){return e.get(t)?.trim()||void 0}
function uO(e){return e===`light`||e===`dark`||e===`zai-light`||e===`zai-dark`||e===`system`}
function pO(e){
  let t=lO(e,`sid`), n=lO(e,`hash`), r=lO(e,`t`), i=r?Number(r):NaN, a=lO(e,`theme`);
  return !t||!n||!Number.isFinite(i) ? null : {
    deviceSid:t, passHash:n, timestamp:i,
    ...lO(e,`mid`)?{deviceMid:lO(e,`mid`)}:{},
    ...lO(e,`name`)?{deviceName:lO(e,`name`)}:{},
    ...lO(e,`app_version`)?{appVersion:lO(e,`app_version`)}:{},
    ...uO(a)?{theme:a}:{}
  }}
```
- **Required**: `sid`, `hash`, `t` (must parse to a finite Number). Missing any ⇒ `null` ⇒
  fatal `invalid-mobile-connection`.
- **Optional**: `mid` → `deviceMid` (added to the WS URL as `?mid=`), `name` → `deviceName`
  (parsed but not visibly used by the phone transport), `app_version` → `appVersion`
  (used only as `auth_init.meta.version`; the v4 `clientHello.appVersion` is hard-coded
  `"unknown"`), `theme` ∈ {light,dark,zai-light,zai-dark,system}.
- `t` is a timestamp; it is **not** part of the proof. Its server-side use (session expiry) is
  in the closed relay.

---

## 4. Control-plane message types and v4 method names

### 4.1 Relay control-plane (`type` field on the raw WS envelope)
Server→client: `auth_challenge`, `auth_ack`, `pair_status_ack`, `data`, `error`.
Client→server: `auth_init`, `auth_response`, `pair_status_query`.
(Index offset ~4743628 and ~4746627.)

### 4.2 Control-plane `zcode_type` values (inside `data.payload`)
Distinct literals in the bundle:
- Client→server: `bootstrap-request`, `workspace-list-request`, `workspace-bridge-open`,
  `workspace-reconnect-request`, `platform-request`, `mobile-view-state-update`,
  `mobile-diagnostic`, `rpc-frame`, `rpc-frame-ack`.
- Server→client: `bootstrap-response`, `workspace-list-response`, `workspace-list-updated`,
  `workspace-bridge-ready`, `workspace-bridge-error`, `bridge-degraded`,
  `workspace-reconnect-response`, `platform-response`, `app-error`,
  `rpc-frame`, `rpc-frame-ack`.

Evidence (index offset ~4791027 etc.):
```js
O({zcode_type:`platform-request`,requestId:n,method:e,args:t},
  t=>t.zcode_type===`platform-response`&&t.requestId===n&&t.method===e)
O({zcode_type:`workspace-list-request`,requestId:e},
  t=>t.zcode_type===`workspace-list-response`&&t.requestId===e)
O({zcode_type:`workspace-bridge-open`,requestId:X9(`workspace-bridge`),bridgeSessionId:n,
   bridgeGeneration:r,...l?{recoveryId:l}:{},workspaceKey:e,...t?.taskId?{taskId:t.taskId}:{}},
  e=>e.zcode_type===`workspace-bridge-ready`&&e.bridgeSessionId===n)
E.sendPayload({zcode_type:`mobile-view-state-update`,viewState:{activeWorkspaceKey:e,...},deviceInfo:u4t({appVersion:t.appVersion})})
O({zcode_type:`workspace-reconnect-request`,requestId:t,workspaceKey:e},
  n=>n.zcode_type===`workspace-reconnect-response`&&n.requestId===t&&n.workspaceKey===e,{timeoutMs:Rie})
O({zcode_type:`bootstrap-request`,requestId:e},t=>t.zcode_type===`bootstrap-response`&&t.requestId===e)
```
The RPC tunnelling payloads:
```js
{ zcode_type:`rpc-frame`, bridgeSessionId, bridgeGeneration, recoveryId, seq, dataBase64 }
{ zcode_type:`rpc-frame-ack`, bridgeSessionId, bridgeGeneration, recoveryId, ackMessageSeq }
```
Detected by `_0t`: `t===`rpc-frame`||t===`rpc-frame-ack``. Frame size is measured against
`` {method:`v4/conversation/frame`,params:e} `` (index offset ~113115), which shows the logical
RPC envelope is `{method, params}` JSON encoded into `dataBase64`.

### 4.3 v4 channel method names the client calls
The SPA obtains an `agentService` proxy over the bridge and calls the v4 methods by property
name (the RPC layer is `ChannelClient.getChannel(name).call(method,args)` →
`toService` proxy, index offset ~265114 / ~268416). The names invoked by the UI transports
(source of truth: `packages/ui/src/v4/agentConversationTransport.ts:54-84`,
`agentSessionsIndexTransport.ts:51-64`, `workspaceConnectionRegistry.ts:18-47`):

Conversation: `helloConversationV4`, `initializeConversationV4`, `subscribeConversationV4`,
`resyncConversationV4`, `unsubscribeConversationV4`, `sendConversationCommandV4`,
`queryConversationCommandsV4`, `conversationRowsRangeV4`, `conversationPlansV4`,
`conversationWorkflowRunEventsV4`, `conversationWorkflowRunsV4`,
`conversationWorkflowRunArtifactsV4`, `conversationWorkflowRunArtifactDataV4`,
`conversationWorkflowRunArtifactReadV4`, `conversationWorkflowRunWorkspaceV4`,
`conversationWorkflowRunNodeResultV4`, `conversationFileChangesV4`,
`conversationFileRewindPreviewV4`.

Attachments: `attachmentBeginV4`, `attachmentChunkV4`, `attachmentCommitV4`,
`attachmentAbortV4`, `attachmentPreviewSourceV4`, `attachmentReadV4`.

Sessions index: `subscribeSessionsIndexV4`, `resyncSessionsIndexV4`, `unsubscribeSessionsIndexV4`.

Events (listen): `onDynamicConversationFrame`, `onDynamicSessionsIndexFrame`,
`onDynamicLocalTtftFacts`, `onAgentRuntimeRestarted`, `onAgentRuntimeLifecycle`.

Push-notification method (host→client frames): `v4/conversation/frame`.

> Note: these are the *method names the UI passes to the RPC proxy*. The phone flow reuses the
> exact same `agentConversationTransport` / `agentSessionsIndexTransport` code (confirmed: the
> bundle contains the same call shapes, e.g. `e.subscribeConversationV4({...n,sessionId:i,...})`
> at index offset ~503312 and `e.subscribeSessionsIndexV4({...n,runtimePolicy:`existing-only`,...})`
> at ~1035404), so the same names are what travels inside the `rpc-frame` payloads.

---

## 5. Delivery-profile / clientMode values and where each is used

Two connection-scoped, host-trusted enums. They are **connection facts**, not client choices:
`zcodeAgentConnectionScope.ts:91-109` (`withTrustedConnection`) deletes any client-supplied
`clientMode`/`deliveryProfile`/`workflowRunDeltas` and re-writes the host's truth.

| `clientMode` | `deliveryProfile` | `role` | Who |
|---|---|---|---|
| `desktop-continuous` | `continuous` | `trusted-host-relay` | Desktop renderer / trusted host relay (`http.ts:336`) |
| `web-remote-replayable` | `replayable` | `terminal-client` | Browser/phone/remote (`http.ts:329`, `:443`) |

- Enum: `transport.ts:46` and `:424` (`clientMode`), `:47` (`deliveryProfile`).
- Coupling enforced by `helloMessageSchema.superRefine` (`transport.ts:55-63`):
  `deliveryProfile === (clientMode==="desktop-continuous" ? "continuous" : "replayable")`,
  error message `"trusted clientMode and deliveryProfile must match"`.
- Server mapping: `deliveryProfileFor()` in `zcodeAgentConnectionScope.ts:201-203`.
- The phone connection is created with `web-remote-replayable` (the OSS analogue is
  `http.ts:329` for `/ws` and `:443` for `/ws/remote/:id`).
- Capability differences by mode: `binaryFrames:false`, `compression:"none"` for the replayable
  path; `nativeDialogs`/`localTerminal` are `continuous`-only
  (`zcodeAgentConnectionScope.ts:214-223`). Several privileged emitters
  (`onDynamicLocalTtftFacts`, `onDynamicConversationTelemetryFact`, `onDynamicCuaPermissionObservation`,
  `onDynamicProcessResourceSample`, `onDynamicToolExecResource`, `onDynamicMcpResourceSamples`,
  `onDynamicMcpTelemetry`) return `RpcEvent.None` for `web-remote-replayable`/`terminal-client`
  (`zcodeAgentConnectionScope.ts:857-924`).
- Hard-coded strings that identify the remote-web profile: `"web-remote-replayable"`,
  `"replayable"`, `"terminal-client"`, and in the bundle `meta.name:"mobile-browser"`,
  `meta.platform:"web"`, `role:"terminal"` (relay-level), `deviceInfo.name:"mobile-browser"`.

---

## 6. Single-client enforcement and teardown

### 6.1 "One phone at a time" — enforced by the relay (closed), surfaced by the client
The client does **not** enforce it. When a second device pairs, the relay closes the first with
an `error` frame code `KICKED`:
```js
handleRelayError(e,t){
  if(e===`KICKED`){ this.setState(`kicked`); this.enterTerminalFailure(L9(`session-conflict`,t||e)); return }
  if(e===`DEVICE_OFFLINE`){ this.recoverFromDeviceOffline(t||e); return }
  if(e===`AUTH_FAILED`||e===`WRONG_PARAM`){ this.enterTerminalFailure(L9(`invalid-mobile-connection`,t||e)); return }
  if(e===`INTERNAL`){ ... } 
  this.enterTerminalFailure(L9(`relay-unavailable`,t||e))
}
```
(index offset ~4744400). `enterTerminalFailure` sets `intentionallyClosed=true`, stops
heartbeat/timers, `setState("kicked")`, rejects pending waiters, calls `onFailure`, and
`socket.close()` (offset ~4748200). The UI then renders the `session-conflict` copy:
> "已被其他设备接管 … 另一台远程控制设备已经接入，同一时间只能保留一个手机控制端" with action
> "重新连接" (index offset ~4754600, table `i4t`). So: **the previous client is kicked
> (terminal state, no auto-reconnect) and shown a "taken over" screen; the newest device wins.**

The relay also returns `session-conflict` as an HTTP/bootstrap reason code. Reason-code table
from the chunk (offset ~275810):
```js
var mO={SessionNotFound:4004,SessionConflict:4009,DesktopDisconnected:4010,SessionExpired:4011,
        WorkspaceClosed:4012,InvalidMobileConnection:4013};
```
mapped to UI states `session-not-found | session-conflict | desktop-disconnected |
session-expired | workspace-closed | invalid-mobile-connection`.

### 6.2 Other teardown paths the client handles
- `DEVICE_OFFLINE`: retries immediately, but a 15 s grace timer (`desktopOfflineGraceMs`)
  then fails with `desktop-disconnected`.
- `INTERNAL` while paired/waiting: falls back to `waiting` and keeps heart-beating.
- Tab hidden/frozen/pagehide: `e4t()` wires `visibilitychange`/`freeze`/`pagehide` →
  `transport.suspend()` (clears timers, state `suspended`); `visible/pageshow/online/resume`
  → `recoverConnection()` then re-bootstrap with `preferredWorkspaceKey`/`preferredTaskId`.
- Bridge-level: `workspace-bridge-error` / `bridge-degraded` with a matching `bridgeSessionId`
  marks the RPC bridge degraded and triggers recovery (`W4t`'s `onPayload`).
- Client-side app errors: `app-error {reason,error,requestId?}` → `onFailure`.

### 6.3 Desktop-side 停止/stop
**Not determinable from this OSS tree.** The OSS `WebRemoteControlDialog`
(`packages/ui/src/WebRemoteControlDialog.tsx`) is only the *Bot Channel* chooser
(i18n `webRemoteControl.*` at `packages/ui/src/i18n/locales/zh-CN.ts:1615-1629`); it has no
QR/phone-relay UI and no stop button. `packages/desktop/src/main/desktopRemoteSessions.ts` and
`desktopMainIpcRemote.ts` manage SSH/Docker/WSL *remote dev workspaces* (`RemoteTarget`,
`connectRemote`), not the relay phone session — grep for `auth_challenge`, `passHash`,
`deviceSid`, `pair_status`, `KICKED`, `remote-control` finds none of the relay vocabulary in
the desktop source. The QR/stop dialog and the host-side relay client that would send the
"stop" (invalidate session) are closed-source. The observable effect of a desktop stop is a
relay reason code (`session-expired`/`session-not-found`/`workspace-closed`) delivered either
as a bootstrap error or an `error` frame, per §6.1/§6.2.

### 6.4 OSS-side per-connection lifecycle (for reference)
The OSS `createZCodeAgentConnectionScope` disposes cleanly on socket close:
`http.ts:121-124` (`socket.onClose → connectionScope.dispose()`), and `dispose()`
(`zcodeAgentConnectionScope.ts:1035-1067`) closes flow state, drops all owned subscriptions,
detaches emitters, and unsubscribes upstream. There is **no** "only one client" rule at the OSS
server level — every `/ws` gets its own scope; the one-at-a-time rule is purely a relay policy.

---

## 7. How to impersonate this client

### 7.1 What a third-party client must send
1. **Open the WS** at `wss://zcode.z.ai/ws` (or `wss://zcode.chatglm.site/ws` if
   `endpointOrigin` is that origin; `overrideUrl` wins if present). Append `?mid=<deviceMid>`
   **only if** you have a `mid`; the client omits the param when `mid` is absent.
   No subprotocol, no auth header — authentication is entirely in-band.
2. **On open, send `auth_init`** (raw JSON text frame):
   ```json
   {"type":"auth_init","role":"terminal","device_sid":"<sid>","meta":{"platform":"web","version":"<app_version or 'web'>","name":"mobile-browser"},"client_ts":<epoch_ms>}
   ```
   `device_sid` must be the `sid` from the URL; `role` must be exactly `"terminal"`.
3. **On `auth_challenge`**, compute and send `auth_response`:
   ```json
   {"type":"auth_response","device_sid":"<sid>","proof":"<base64url_nopad(HMAC_SHA256(key=<hash>, msg='<nonce>|terminal|<sid>'))>","client_ts":<epoch_ms>}
   ```
   where `<nonce>` is `auth_challenge.nonce` verbatim. Do **not** URL-encode the `|`.
4. **Wait for `auth_ack`/`pair_status_ack`.** `pair_status:"waiting"` means the desktop has not
   matched yet (client starts a waiting timer, default 30 s). `pair_status:"matched"` ⇒ paired;
   only then may you send `data` payloads.
5. **Send control payloads wrapped as `data`**:
   ```json
   {"type":"data","payload":{"zcode_type":"bootstrap-request","requestId":"bootstrap-<uuid>"},"client_ts":<epoch_ms>}
   ```
   then `workspace-list-request`, then `workspace-bridge-open`, then run the v4 handshake.
6. **Keep heartbeating**: send `{"type":"pair_status_query","device_sid":"<sid>","client_ts":<ms>}`
   roughly every 10 s; the relay expects an ACK within 30 s or the client side will reconnect.
7. **Run the v4 handshake over the bridge** (inside `rpc-frame` payloads) exactly as §2.1:
   `helloConversationV4()` → parse `hello` → `initializeConversationV4(clientHello)` with
   `protocolVersion:3`, `clientKind:"web"`, `appVersion:"unknown"`,
   `capabilities:{workspaceHookReviewUi:true}`.

### 7.2 What it must NOT get wrong
- **`sid`/`hash`/`t` are mandatory**; missing `t` or a non-numeric `t` ⇒ hard fail before any
  socket is opened (`pO` returns null → `invalid-mobile-connection`).
- **`role` is fixed `"terminal"`** in both `auth_init` and the proof message. Any other value
  yields a different HMAC and `AUTH_FAILED`/`WRONG_PARAM`.
- **Proof = HMAC over `nonce|terminal|sid`**, key = `hash`, output base64url **without padding**.
  Common mistakes: padding left on, standard base64 `+`/`/`, wrong key/msg order, using `mid`
  or `t` instead of `sid`.
- **`clientHello.capabilities` is `.strict()`** (`transport.ts:76-87`). Sending any key the host
  does not know (e.g. `workflowRunDeltas` to a host that did not advertise it, or any typo)
  makes the *entire* `clientHello` fail to parse and the handshake never completes. Only send
  `workspaceHookReviewUi`, and only add `workflowRunDeltas` if the received `hello.capabilities.workflowRunDeltas === true`.
- **Do not try to set `clientMode`/`deliveryProfile`/`connectionId`** in any v4 call — the host
  facade deletes them and re-injects trusted values (`zcodeAgentConnectionScope.ts:91-109`).
  Trying to force `desktop-continuous` gets you nothing; the connection stays `replayable`.
- **`clientId` must stay constant per connection.** A second `clientHello` with a different
  `clientId` ⇒ `fault.connection.clientChanged` (`zcodeAgentConnectionScope.ts:677-679`).
- **Register listeners before subscribing.** Frames can arrive before the subscribe ACK; if you
  subscribe first and attach `onDynamicConversationFrame` after, early frames are lost. The
  reference order is: attach `onDynamic*Frame` → `subscribe...` → on ACK `activate(subscriptionId)`.
- **Subscribe base/watermark invariant**: only pass `base:{logEpoch,seq}` if you truly hold
  consistent state at that point; otherwise omit it (`transport.ts:109-118`).
- **`sessions-index` subscriptions must use `runtimePolicy:"existing-only"`** (the phone UI
  always does — `agentSessionsIndexTransport.ts:123`). Omitting it can start a runtime the
  replayable client is not allowed to own.
- **Respect frame-size limits**: `Wo.maxPhysicalFrameBytes`; oversize payloads are rejected
  client-side (`sendPayloadResult` → `{kind:"oversize"}`) and `rpc-frame` oversize throws
  `remote.rpcFrame.envelopeTooLarge`. Keep `data` envelopes under the physical frame cap and
  split attachment uploads into `attachmentChunkV4` chunks (`PROTOCOL_V4_LIMITS.attachmentChunkMaxBytes`).
- **One phone at a time**: a second paired device triggers `KICKED` on the first. Do not assume
  your session survives another pairing, and handle `error.code==="KICKED"` as terminal
  (no reconnect loop).
- **Do not rely on `name`/`app_version` for auth** — they are informational only
  (`auth_init.meta.version`, unused `deviceName`); the v4 `clientHello.appVersion` is hard-coded
  `"unknown"` by the real client.

---

## Appendix — files referenced
| Path | Role |
|---|---|
| `packages/shared/src/zcode-protocol-v4/transport.ts:41-90` | `helloMessageSchema`, `clientHelloSchema`, capability helpers |
| `packages/shared/src/zcode-protocol-v4/core.ts:7` | `V4_WIRE_PROTOCOL_VERSION = 3` |
| `packages/ui/src/v4/agentV4ConnectionHandshake.ts:18-51` | per-service hello→clientHello handshake |
| `packages/ui/src/v4/agentConversationTransport.ts:187-272,527-575` | conversation subscribe/activate/onFrame |
| `packages/ui/src/v4/agentSessionsIndexTransport.ts:116-151` | sessions-index subscribe (`runtimePolicy:"existing-only"`) |
| `packages/ui/src/v4/sessionsIndexRegistry.ts:143-157` | sessions-index store registry/refCount |
| `packages/ui/src/v4/workspaceConnectionRegistry.ts:159-227` | conversation connection registry + 30 s keep-warm |
| `packages/ui/src/v4/sessionsIndexStore.ts:216,240,262` | listen→subscribe→activate ordering |
| `packages/ui/src/v4/sessionDataLayer.ts:62` | onFrame attach on connect |
| `packages/services/src/zcode-agent/zcodeAgentConnectionScope.ts:205-227,668-683,91-109,1035-1067` | host `hello`, handshake enforcement, trusted-field scrubbing, dispose |
| `packages/server/src/http.ts:86-125,325-346,418-447` | `setupChannelServer`, `/ws`, `/ws/host`, `/ws/remote/:id` |
| `packages/web/src/main.tsx:359-389,446-472` | OSS web SPA bootstrap (base of the served SPA) |
| `packages/web/src/env.d.ts:6-17` | `/remote` vs `/remote/v3` build-base + remote-control env vars (declared, unused in OSS) |
| `_relay_probe/index-B-ilXaCQ.js` | served SPA entry (bundle) |
| `_relay_probe/src-wmk2orCZ.js` | served SPA app chunk (relay param parser, endpoint/relay URL config) |
