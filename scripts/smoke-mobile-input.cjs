// Uses the installed frontend through a read-only proxy. No server mutations or generation requests are forwarded.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),{spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.join(root,'build','mobile-input');fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let edge,ws,send,evaluate;
const server=http.createServer(async(req,res)=>{
 try{
  const route=req.url.replace(/^\/api\//,'/');
  if(req.method!=='GET') {res.writeHead(200,{'content-type':'application/json'});res.end('{}');return;}
  if(route.startsWith('/userdata')){res.writeHead(200,{'content-type':'application/json'});res.end(route.includes('?')?'[]':'{}');return;}
  if(route==='/extensions'){res.writeHead(200,{'content-type':'application/json'});res.end('[]');return;}
  const response=await fetch('http://127.0.0.1:8188'+req.url);
  let data;
  if(route==='/object_info') {const all=await response.json();data=Buffer.from(JSON.stringify(Object.fromEntries(Object.entries(all).filter(([name])=>['CLIPTextEncode','KSampler','EmptyLatentImage','SaveImage'].includes(name)))));}
  else data=Buffer.from(await response.arrayBuffer());
  res.writeHead(response.status,{'content-type':response.headers.get('content-type')||'application/octet-stream','cache-control':'no-store'});res.end(data);
 }catch(error){res.writeHead(502);res.end(String(error));}
});
function stop(child){if(child)spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});}
(async()=>{
 try{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
  edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=9239','--user-data-dir='+path.join(out,'profile'),'http://127.0.0.1:'+port],{windowsHide:true,stdio:'ignore'});
  let target;
  for(let i=0;i<100;i++){try{const all=await(await fetch('http://127.0.0.1:9239/json')).json();target=all.find(t=>t.type==='page'&&t.url.includes(':'+port));if(target)break;}catch{}await sleep(300);}
  if(!target)throw Error('No browser target');ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  let id=0;const pending=new Map();ws.onmessage=e=>{const r=JSON.parse(e.data);if(pending.has(r.id)){pending.get(r.id)(r);pending.delete(r.id);}};
  send=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,r=>r.error?reject(Error(JSON.stringify(r.error))):resolve(r.result));ws.send(JSON.stringify({id:key,method,params}));setTimeout(()=>{if(pending.delete(key))reject(Error('CDP timeout'));},30000).unref();});
  evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result?.value;};
  await send('Emulation.setDeviceMetricsOverride',{width:915,height:412,deviceScaleFactor:1,mobile:true});await send('Page.reload');await sleep(1200);
  let ready;for(let i=0;i<100;i++){ready=await evaluate('!!window.comfyAPI?.app?.app?.graph && !!window.LiteGraph?.registered_node_types?.CLIPTextEncode');if(ready)break;await sleep(400);}if(!ready)throw Error('Frontend not ready');
  await evaluate(fs.readFileSync(path.join(root,'app/src/main/assets/editor-adapter.js'),'utf8'));
  const result=await evaluate(`(async()=>{
    const app=window.comfyAPI.app.app,b=window.ComfyMobileEditor;
    await app.loadGraphData({last_node_id:0,last_link_id:0,nodes:[],links:[],groups:[],config:{},extra:{},version:.4},true,true,null,{skipAssetScans:true,deferWarnings:true});
    await new Promise(r=>setTimeout(r,500));
    const added=await b.command('add',{type:'CLIPTextEncode'});if(!added.ok)throw Error(added.error);
    const node=app.graph.getNodeById(added.nodeId);node.pos=[80,50];app.canvas.centerOnNode(node);app.canvas.draw(true,true);
    await new Promise(r=>setTimeout(r,600));
    const widget=node.widgets.find(w=>w.name==='text');const element=widget.element;
    const input=element?.matches?.('textarea,input')?element:element?.querySelector?.('textarea,input');
    if(!input)throw Error('Real CLIP DOM text field missing: '+String(element));
    await b.drainEvents();
    const allowed=input.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch'}));
    const events=(await b.drainEvents()).events,edit=events.find(e=>e.type==='edit_field');
    if(allowed||!edit||edit.data.target.nodeId!==node.id||edit.data.name!=='text')throw Error('Native handoff failed: '+JSON.stringify({allowed,events}));
    const saved=await b.writeField(edit.data.target,'silver hair, soft lighting');if(!saved.ok||widget.value!=='silver hair, soft lighting')throw Error('Writeback failed');
    const search=document.createElement('input');document.body.appendChild(search);search.focus();
    const unrelated=(await b.drainEvents()).events;if(unrelated.some(e=>e.type==='edit_field'))throw Error('Unrelated search captured');search.remove();
    await new Promise(r=>setTimeout(r,800));input.focus();
    const focused=(await b.drainEvents()).events;if(!focused.some(e=>e.type==='edit_field')||document.activeElement===input)throw Error('Focus fallback failed');
    return {ok:true,viewport:[innerWidth,innerHeight],checks:['real CLIP pointer intercepted before web keyboard','exact node/widget handoff','English field writeback','search inputs untouched','focus fallback releases web input'],widgetType:widget.type};
  })()`);
  if(!result?.ok)throw Error(JSON.stringify(result));
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }catch(error){
  if(evaluate){try{console.error(await evaluate('document.body.innerText.slice(-2000)'));}catch{}}
  throw error;
 }finally{if(ws)ws.close();stop(edge);server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
