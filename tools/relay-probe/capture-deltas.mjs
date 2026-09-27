/** Capture real delta ops from the live relay so the client reducer is built
 *  against observed shapes rather than inferred ones. */
import { readFileSync, writeFileSync } from 'node:fs';
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';
const AGENT = 'zcode-agent';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const transport = new RelayTransport(parseRemoteLink(readFileSync('link.txt','utf8').trim()), {});
const client = new ChannelClient(transport, {});
await transport.connect();
const boot = await transport.bootstrap();
const ws = boot.result.initialViewState.activeWorkspaceKey;
const sid = boot.result.initialViewState.activeTaskId;
await transport.openBridge(ws, sid);
for (let i=0;i<50 && !client.initialized;i++) await sleep(100);
const hello = await client.call(AGENT,'helloConversationV4');
await client.call(AGENT,'initializeConversationV4',{kind:'clientHello',protocolVersion:3,
  clientId:'delta-probe',clientKind:'mobileApp',appVersion:'0',capabilities:{workspaceHookReviewUi:true}});

const ops = [];
client.listen(AGENT,'onDynamicConversationFrame',{workspacePath:ws},(c)=>{
  const f=c?.frame; if(!f) return;
  if (f.payload?.kind==='deltas') for (const op of f.payload.deltas) ops.push(op);
});
await client.call(AGENT,'subscribeConversationV4',{workspacePath:ws,sessionId:sid});
console.log('collecting deltas for 25s...');
await sleep(25000);
writeFileSync('deltas.json', JSON.stringify(ops,null,2));
console.log('ops captured:', ops.length);
const shapes={};
for(const o of ops){ const k=JSON.stringify(Object.keys(o).sort()); shapes[k]=(shapes[k]??0)+1; }
for(const [k,v] of Object.entries(shapes)) console.log(v+'x  '+k);
const byOp={};
for(const o of ops) byOp[o.op]=(byOp[o.op]??0)+1;
console.log('op names:', JSON.stringify(byOp));
const seen=new Set();
for(const o of ops){ if(seen.has(o.op))continue; seen.add(o.op); console.log('\n--- sample '+o.op+' ---'); console.log(JSON.stringify(o).slice(0,700)); }
transport.close(); process.exit(0);
