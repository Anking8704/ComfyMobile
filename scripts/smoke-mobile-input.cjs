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
  await send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
  const touch=async(x,y)=>{await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await sleep(40);await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});};
  const doubleTap=async(x,y)=>{await touch(x,y);await sleep(120);await touch(x+2,y+1);await sleep(400);};
  const edits=async()=>(await evaluate('window.ComfyMobileEditor.drainEvents()')).events.filter(e=>e.type==='edit_field'||e.type==='pick_field');
  const setup=await evaluate(`(async()=>{
    const app=window.comfyAPI.app.app,b=window.ComfyMobileEditor;
    await app.loadGraphData({last_node_id:0,last_link_id:0,nodes:[],links:[],groups:[],config:{},extra:{},version:.4},true,true,null,{skipAssetScans:true,deferWarnings:true});
    await new Promise(r=>setTimeout(r,500));
    const focus=await b.command('chrome',{focus:true});if(!focus.ok||!focus.focus)throw Error('Focus mode failed: '+JSON.stringify(focus));
    await new Promise(r=>setTimeout(r,500));
    const c=document.querySelector('#graph-canvas').getBoundingClientRect();
    if(Math.round(c.width)!==innerWidth||Math.round(c.height)!==innerHeight)throw Error('Canvas is not full screen in focus mode: '+JSON.stringify(c));
    const added=await b.command('add',{type:'CLIPTextEncode'});if(!added.ok)throw Error(added.error);
    const node=app.graph.getNodeById(added.nodeId);node.pos=[80,60];node.size=[420,260];app.canvas.ds.scale=1;app.canvas.ds.offset=[0,0];app.canvas.setDirty(true,true);app.canvas.draw(true,true);
    await new Promise(r=>setTimeout(r,700));
    const widget=node.widgets.find(w=>w.name==='text'),element=widget.element;
    const input=element?.matches?.('textarea,input')?element:element?.querySelector?.('textarea,input');
    if(!input)throw Error('Real CLIP DOM text field missing: '+String(element));
    const r=input.getBoundingClientRect();
    let body=null;
    for(let y=Math.round(node.pos[1]+4);y<node.pos[1]+node.size[1]&&!body;y+=6)for(let x=Math.round(node.pos[0]+8);x<node.pos[0]+node.size[0]-8;x+=12){
      const scr=app.canvas.ds.convertCanvasToOffset?app.canvas.ds.convertCanvasToOffset([x,y]):[x,y];
      if(document.elementFromPoint(scr[0],scr[1])===app.canvas.canvas && !node.getWidgetOnPos?.(x,y,true)){body=scr;break;}
    }
    await b.drainEvents();window.__cmNode=node.id;window.__cmInput=input;
    return {input:[r.left+r.width/2,r.top+Math.min(r.height/2,40)],body,widgetType:widget.type};
  })()`);
  await touch(...setup.input);await sleep(900);
  const single=await edits();const keptFocus=await evaluate('document.activeElement===window.__cmInput');
  if(single.length||keptFocus)throw Error('Single tap opened editor or web keyboard: '+JSON.stringify({single,keptFocus}));
  await doubleTap(...setup.input);
  const dbl=await edits();const edit=dbl.find(e=>e.type==='edit_field');
  if(dbl.length!==1||!edit||edit.data.name!=='text')throw Error('Double tap handoff failed: '+JSON.stringify(dbl));
  let bodyResult='no free node body point in this frontend layout';
  if(setup.body){await sleep(600);await doubleTap(...setup.body);const viaBody=await edits();
    if(viaBody.length!==1||viaBody[0].type!=='edit_field'||viaBody[0].data.name!=='text')throw Error('Node body double tap failed: '+JSON.stringify(viaBody));bodyResult='node body double tap opens the only prompt field';}
  const result=await evaluate(`(async()=>{
    const app=window.comfyAPI.app.app,b=window.ComfyMobileEditor,edit=${JSON.stringify(edit)};
    const node=app.graph.getNodeById(window.__cmNode),widget=node.widgets.find(w=>w.name==='text');
    const saved=await b.writeField(edit.data.target,'silver hair, soft lighting');if(!saved.ok||widget.value!=='silver hair, soft lighting')throw Error('Writeback failed');
    const search=document.createElement('input');document.body.appendChild(search);search.focus();
    const unrelated=(await b.drainEvents()).events;if(unrelated.some(e=>e.type==='edit_field'))throw Error('Unrelated search captured');search.remove();
    const restore=await b.command('chrome',{focus:false});if(!restore.ok||restore.focus)throw Error('Could not restore menus');
    return {ok:true,viewport:[innerWidth,innerHeight],checks:['focus mode makes the canvas full screen','real touch single tap keeps web keyboard closed and does not navigate','real touch double tap opens exact node/widget','English field writeback','search inputs untouched','original menus restorable'],widgetType:widget.type};
  })()`);
  result.checks.splice(3,0,bodyResult);
  if(!result?.ok)throw Error(JSON.stringify(result));
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }catch(error){
  if(evaluate){try{console.error(await evaluate('document.body.innerText.slice(-2000)'));}catch{}}
  throw error;
 }finally{if(ws)ws.close();stop(edge);server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
