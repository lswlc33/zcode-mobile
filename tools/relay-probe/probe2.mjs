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
const hello=await c.call(AGENT,'helloConversationV4');
await c.call(AGENT,'initializeConversationV4',{kind:'clientHello',protocolVersion:3,clientId:'probe2',clientKind:'mobileApp',appVersion:'0',capabilities:{workspaceHookReviewUi:true}});

let snap=null;
c.listen(AGENT,'onDynamicConversationFrame',{workspacePath:ws},(x)=>{ if(x?.frame?.payload?.kind==='snapshot') snap=x.frame.payload.snapshot; });
await c.call(AGENT,'subscribeConversationV4',{workspacePath:ws,sessionId:sid});
for(let i=0;i<60&&!snap;i++)await sleep(100);
console.log('snapshot:', !!snap, 'rev=', snap?.revision);

// 1. model-selection.getView  -- is the channel exposed over the relay?
for (const ch of ['model-selection','provider-settings','setting','client-config']) {
  try {
    const v = await c.call(ch,'getView');
    console.log(`OK  ${ch}.getView ->`, JSON.stringify(v).slice(0,600));
  } catch(e){ console.log(`ERR ${ch}.getView -> ${e.message.slice(0,120)}`); }
}

// 2. fileChanges for a tool-call row
const rows = snap.rows.window.filter(r=>r.kind==="toolCall" && ["Edit","Write","MultiEdit","NotebookEdit"].includes(r.toolName));
console.log('\ntool row:', toolRow ? `${toolRow.rowId} ${toolRow.toolName} entity=${toolRow.entityId}` : 'none');
if (toolRow) {
  try {
    const fc = await c.call(AGENT,'conversationFileChangesV4',{workspacePath:ws,sessionId:sid,
      target:{rowId:toolRow.rowId,entityId:toolRow.entityId},
      baseRevision:snap.revision, baseLogEpoch:snap.logEpoch});
    console.log('fileChanges:', JSON.stringify(fc).slice(0,900));
    writeFileSync('filechanges.json',JSON.stringify(fc,null,2));
  } catch(e){ console.log('fileChanges ERR:', e.message.slice(0,200)); }
}
t.close(); process.exit(0);
