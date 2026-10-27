// Integration test against the installed ComfyUI frontend; never queues generation.
const fs=require('node:fs'),path=require('node:path'),{spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),bundle=path.dirname(root),logs=path.join(root,'build','smoke');
fs.mkdirSync(logs,{recursive:true});
const managerConfig=path.join(logs,'__manager','config.ini');
fs.mkdirSync(path.dirname(managerConfig),{recursive:true});
fs.writeFileSync(managerConfig,'[default]\nnetwork_mode = offline\n');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const timeout=Date.now()+300000;
const python=spawn(path.join(bundle,'python','python.exe'),['-s','main.py','--cpu','--offline','--disable-all-custom-nodes','--disable-auto-launch','--listen','127.0.0.1','--port','8199','--user-directory',logs,'--whitelist-custom-nodes','comfyui-anima-enhancer'],{
 cwd:path.join(bundle,'ComfyUI'),windowsHide:true,env:{...process.env,HF_HUB_OFFLINE:'1',HF_HUB_DISABLE_TELEMETRY:'1'}
});
const log=fs.createWriteStream(path.join(logs,'server.log'));python.stdout.pipe(log);python.stderr.pipe(log);
let edge,ws,send,evaluate;
function stop(child){if(!child)return;if(process.platform==='win32')spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});else child.kill();}
async function reachable(url){try{const r=await fetch(url);return r.ok;}catch{return false;}}
(async()=>{
 try{
  while(!await reachable('http://127.0.0.1:8199/system_stats')){if(python.exitCode!==null)throw Error('ComfyUI exited; inspect build/smoke/server.log');if(Date.now()>timeout)throw Error('ComfyUI startup timeout');await sleep(1000);}
  edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=9229','--user-data-dir='+path.join(logs,'edge-profile'),'http://127.0.0.1:8199'],{windowsHide:true,stdio:'ignore'});
  while(!await reachable('http://127.0.0.1:9229/json')){if(Date.now()>timeout)throw Error('Edge startup timeout');await sleep(500);}
  let target;
  for(let i=0;i<50;i++){const targets=await(await fetch('http://127.0.0.1:9229/json')).json();target=targets.find(t=>t.type==='page'&&t.url.includes(':8199'));if(target)break;await sleep(300);}
  if(!target)throw Error('No frontend browser target');
  ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
  let id=0;const pending=new Map();ws.onmessage=event=>{const data=JSON.parse(event.data);if(pending.has(data.id)){pending.get(data.id)(data);pending.delete(data.id);}};
  send=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,r=>r.error?reject(Error(JSON.stringify(r.error))):resolve(r.result));ws.send(JSON.stringify({id:n,method,params}));setTimeout(()=>{if(pending.delete(n))reject(Error('CDP timeout '+method));},45000).unref();});
  evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result?.value;};
  await send('Emulation.setDeviceMetricsOverride',{width:412,height:915,deviceScaleFactor:1,mobile:true});
  await send('Page.reload');await sleep(1500);
  let ready=false;
  for(let i=0;i<100;i++){ready=await evaluate('!!window.comfyAPI?.app?.app?.graph && !!window.LiteGraph?.registered_node_types?.CLIPTextEncode');if(ready)break;await sleep(500);}
  if(!ready)throw Error('Frontend app/graph not ready');
  await evaluate(fs.readFileSync(path.join(root,'app/src/main/assets/editor-adapter.js'),'utf8'));
  const result=await evaluate(`(async()=>{
   const b=window.ComfyMobileEditor, app=window.comfyAPI.app.app;
   const types=await b.command('types',{});if(!types.ok||!types.types.includes('CLIPTextEncode'))throw Error('types: '+JSON.stringify(types));
   await app.loadGraphData({last_node_id:0,last_link_id:0,nodes:[],links:[],groups:[],config:{},extra:{},version:.4});
   await new Promise(r=>setTimeout(r,500));
   for(const type of ['CLIPTextEncode','EmptyLatentImage','KSampler','AnimaLayerReplayPatcher']){const r=await b.command('add',{type});if(!r.ok)throw Error(r.error);}
   const fields=await b.fields();const field=fields.fields.find(f=>f.nodeType==='CLIPTextEncode'&&f.name==='text');if(!field)throw Error('no CLIP field');
   const written=await b.writeField(field.target,'银白长发，月光花园\\nsoft lighting');if(!written.ok)throw Error(written.error);
   const custom=fields.fields.find(f=>f.nodeType==='AnimaLayerReplayPatcher'&&f.name==='block_indices');if(!custom)throw Error('custom node field missing');
   if(!(await b.writeField(custom.target,'2,3,4')).ok)throw Error('custom node write failed');
   const nodes=await b.command('nodes',{}),from=nodes.nodes.find(n=>n.type==='EmptyLatentImage'),to=nodes.nodes.find(n=>n.type==='KSampler');
   const input=to.inputs.findIndex(s=>s.name==='latent_image');const connected=await b.command('connect',{from:from.id,output:0,to:to.id,input});if(!connected.ok)throw Error(connected.error);
   const out=await b.command('export',{}),data=JSON.parse(out.json);if(data.nodes.length!==4)throw Error('export count');
   await new Promise(r=>setTimeout(r,500));
   const imported=await b.command('import',{json:out.json});if(!imported.ok)throw Error(imported.error);
   const fresh=await b.fields();if(!fresh.fields.some(f=>f.value.includes('月光花园')))throw Error('round trip text');
   if(!fresh.fields.some(f=>f.nodeType==='AnimaLayerReplayPatcher'&&f.value==='2,3,4'))throw Error('custom node round trip');
   const stale=await b.writeField(field.target,'wrong');if(stale.ok)throw Error('stale accepted');
   await b.command('fit',{});
   return {ok:true,registeredTypes:types.types.length,fieldCount:fresh.fields.length,nodes:data.nodes.length,links:Array.isArray(data.links)?data.links.length:'object',checks:['real CLIP field write','node creation','latent connection','workflow export/import','AnimaLayerReplayPatcher custom widget round trip','stale field rejection'],userAgent:navigator.userAgent};
  })()`);
  if(!result?.ok)throw Error(JSON.stringify(result));
  await sleep(1000);const shot=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(logs,'frontend-phone-viewport.png'),Buffer.from(shot.data,'base64'));
  fs.writeFileSync(path.join(logs,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }catch(error){
  if(send){try{const shot=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(logs,'failure.png'),Buffer.from(shot.data,'base64'));}catch{}}
  if(evaluate){try{const state=await evaluate('({canvas:!!window.comfyAPI?.app?.app?.canvas,graph:!!window.comfyAPI?.app?.app?.graph,body:document.body.innerText.slice(-5000)})');fs.writeFileSync(path.join(logs,'failure-state.json'),JSON.stringify(state,null,2));}catch{}}
  throw error;
 }finally{
  if(ws)ws.close();stop(edge);stop(python);log.end();
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
