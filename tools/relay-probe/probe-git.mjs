import { readFileSync } from 'node:fs';
import { RelayTransport, ChannelClient, parseRemoteLink } from './zcode-client.mjs';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const t=new RelayTransport(parseRemoteLink(readFileSync('link.txt','utf8').trim()),{});
const c=new ChannelClient(t,{});
await t.connect();
const boot=await t.bootstrap();
const ws=process.argv[2] || boot.result.initialViewState.activeWorkspaceKey, sid=boot.result.initialViewState.activeTaskId;
await t.openBridge(ws,sid);
for(let i=0;i<50&&!c.initialized;i++)await sleep(100);
for (const m of ['getRepositorySummary','getWorkspaceRepositoryInfo','getLocalBranches']) {
  try { const v = await c.call('git', m, {workspacePath: ws});
        console.log(`OK  git.${m} ->`, JSON.stringify(v).slice(0,420)); }
  catch(e){ console.log(`ERR git.${m} -> ${e.message.slice(0,110)}`); }
}
t.close(); process.exit(0);
