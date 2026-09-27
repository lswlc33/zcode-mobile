import { readFileSync, writeFileSync } from 'node:fs';
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';
const AGENT='zcode-agent';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const t=new RelayTransport(parseRemoteLink(readFileSync('link.txt','utf8').trim()),{});
const c=new ChannelClient(t,{});
await t.connect();
const boot=await t.bootstrap();
const ws=boot.result.initialViewState.activeWorkspaceKey, sid=boot.result.initialViewState.activeTaskId;
await t.openBridge(ws,sid);
for(let i=0;i<50&&!c.initialized;i++)await sleep(100);
await c.call(AGENT,'helloConversationV4');
await c.call(AGENT,'initializeConversationV4',{kind:'clientHello',protocolVersion:3,clientId:'probe3',clientKind:'mobileApp',appVersion:'0',capabilities:{workspaceHookReviewUi:true}});
let snap=null;
c.listen(AGENT,'onDynamicConversationFrame',{workspacePath:ws},(x)=>{ if(x?.frame?.payload?.kind==='snapshot') snap=x.frame.payload.snapshot; });
await c.call(AGENT,'subscribeConversationV4',{workspacePath:ws,sessionId:sid});
for(let i=0;i<60&&!snap;i++)await sleep(100);
console.log('rev=',snap.revision,'window=',snap.rows.window.length);

const rows=snap.rows.window.filter(r=>r.kind==='toolCall'&&['Edit','Write','MultiEdit'].includes(r.toolName));
console.log('edit-ish rows in window:', rows.length, rows.map(r=>r.rowId).join(','));

// try newest first, with several CAS bases
outer:
for (const r of rows.slice().reverse()) {
  for (const [label, base] of [['snapRev', snap.revision], ['createdSeq', r.createdAtSeq]]) {
    try {
      const fc = await c.call(AGENT,'conversationFileChangesV4',{workspacePath:ws,sessionId:sid,
        target:{rowId:r.rowId,entityId:r.entityId},
        baseRevision:base, baseLogEpoch:snap.logEpoch});
      console.log(`OK   row=${r.rowId} ${r.toolName} base=${label} ->`, JSON.stringify(fc).slice(0,500));
      writeFileSync('filechanges.json',JSON.stringify(fc,null,2));
      break outer;
    } catch(e){ console.log(`ERR  row=${r.rowId} ${r.toolName} base=${label} -> ${e.message.slice(0,80)}`); }
  }
}
t.close(); process.exit(0);
