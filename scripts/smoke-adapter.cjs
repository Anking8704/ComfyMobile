// Checks the 0.4 adapter features on the installed frontend through a read-only proxy (non-GET requests are dropped).
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),{spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.join(root,'build','adapter-smoke');fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let edge,ws,send,evaluate;
const server=http.createServer(async(req,res)=>{
 try{
  const route=req.url.replace(/^\/api\//,'/');
  if(req.method!=='GET') {res.writeHead(200,{'content-type':'application/json'});res.end('{}');return;}
  if(route.startsWith('/userdata')&&!route.includes('workflows')){res.writeHead(200,{'content-type':'application/json'});res.end(route.includes('?')?'[]':'{}');return;}
  if(route==='/extensions'){res.writeHead(200,{'content-type':'application/json'});res.end('[]');return;}
  const response=await fetch('http://127.0.0.1:8188'+req.url);
  let data;
  if(route==='/object_info') {const all=await response.json();data=Buffer.from(JSON.stringify(Object.fromEntries(Object.entries(all).filter(([name])=>['CLIPTextEncode','KSampler','EmptyLatentImage','SaveImage','CheckpointLoaderSimple'].includes(name)))));}
  else data=Buffer.from(await response.arrayBuffer());
  res.writeHead(response.status,{'content-type':response.headers.get('content-type')||'application/octet-stream','cache-control':'no-store'});res.end(data);
 }catch(error){res.writeHead(502);res.end(String(error));}
});
function stop(child){if(child)spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});}
(async()=>{
 try{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
  edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=9247','--user-data-dir='+path.join(out,'profile'),'http://127.0.0.1:'+port],{windowsHide:true,stdio:'ignore'});
  let target;
  for(let i=0;i<100;i++){try{const all=await(await fetch('http://127.0.0.1:9247/json')).json();target=all.find(t=>t.type==='page'&&t.url.includes(':'+port));if(target)break;}catch{}await sleep(300);}
  if(!target)throw Error('No browser target');ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  let id=0;const pending=new Map();ws.onmessage=e=>{const r=JSON.parse(e.data);if(pending.has(r.id)){pending.get(r.id)(r);pending.delete(r.id);}};
  send=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,r=>r.error?reject(Error(JSON.stringify(r.error))):resolve(r.result));ws.send(JSON.stringify({id:key,method,params}));setTimeout(()=>{if(pending.delete(key))reject(Error('CDP timeout'));},30000).unref();});
  evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result?.value;};
  await send('Emulation.setDeviceMetricsOverride',{width:915,height:412,deviceScaleFactor:1,mobile:true});await send('Page.reload');await sleep(1200);
  let ready;for(let i=0;i<100;i++){ready=await evaluate('!!window.comfyAPI?.app?.app?.graph && !!window.LiteGraph?.registered_node_types?.CLIPTextEncode');if(ready)break;await sleep(400);}if(!ready)throw Error('Frontend not ready');
  await evaluate(fs.readFileSync(path.join(root,'app/src/main/assets/editor-adapter.js'),'utf8'));
  await send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
  const touch=async(x,y)=>{await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await sleep(40);await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});};
  const doubleTap=async([x,y])=>{await touch(x,y);await sleep(120);await touch(x+2,y+1);await sleep(450);};
  const edits=async()=>(await evaluate('window.ComfyMobileEditor.drainEvents()')).events.filter(e=>e.type==='edit_field'||e.type==='pick_field');
  const checks=[],passed=name=>{checks.push(name);console.error('ok:',name);};
  const fresh=`const app=window.comfyAPI.app.app,b=window.ComfyMobileEditor,L=window.LiteGraph;
    await app.loadGraphData({last_node_id:0,last_link_id:0,nodes:[],links:[],groups:[],config:{},extra:{},version:.4},true,true,null,{skipAssetScans:true,deferWarnings:true});
    await new Promise(r=>setTimeout(r,300));app.canvas.ds.scale=1;app.canvas.ds.offset=[0,0];`;
  const textareaPoint=`(()=>{const t=[...document.querySelectorAll('textarea')].find(t=>{const r=t.getBoundingClientRect();return r.width>40&&r.height>20&&r.top>0&&r.left>=0;});
    if(!t)return null;const r=t.getBoundingClientRect();return [r.left+r.width/2,r.top+Math.min(30,r.height/2)];})()`;

  // 1. Undo restores exactly what the node panel removed.
  const undo=await evaluate(`(async()=>{${fresh}
    const tracker=app.extensionManager.workflow.activeWorkflow?.changeTracker;
    const a=L.createNode('EmptyLatentImage');a.pos=[40,60];app.graph.add(a);tracker?.checkState();
    const k=L.createNode('KSampler');k.pos=[420,40];app.graph.add(k);tracker?.checkState();
    await b.command('remove',{nodeId:a.id});await b.command('undo',{});await new Promise(r=>setTimeout(r,800));
    return app.graph._nodes.map(n=>n.type).sort().join(',');})()`);
  if(undo!=='EmptyLatentImage,KSampler')throw Error('undo after panel delete restored: '+undo);passed('undo after a node-panel delete restores exactly that node');

  // 2. Toolbars and the debug overlay stay hidden on touch screens.
  const chrome=await evaluate(`(async()=>{const app=window.comfyAPI.app.app,b=window.ComfyMobileEditor;
    await b.command('configure',{hideInfo:true,selectionToolbox:false,toolbar:true});await b.command('chrome',{focus:true});
    app.canvas.show_info=true;
    return {info:app.canvas.show_info,css:document.getElementById('comfymobile-style')?.textContent||''};})()`);
  if(chrome.info||!chrome.css.includes('.selection-toolbox'))throw Error('configure: '+JSON.stringify(chrome));passed('FPS overlay pinned off and selection toolbox hidden');

  // 3. Double tap inside an open subgraph edits the inner field.
  const sub=await evaluate(`(async()=>{${fresh}
    const c=L.createNode('CLIPTextEncode');c.pos=[80,60];app.graph.add(c);
    const res=app.graph.convertToSubgraph(new Set([c]));const sg=res.subgraph;
    // The frontend promotes the text box to the subgraph node; that promoted field is what the outside edits.
    const promoted=(await b.fields()).fields.find(f=>f.target.graphId==='root');
    const promotedOk=promoted?(await b.writeField(promoted.target,'promoted text')).ok:false;
    const inner=sg.nodes[0];const ti=inner.inputs.findIndex(i=>i.widget?.name==='text');if(ti>=0&&inner.inputs[ti].link!=null)inner.disconnectInput(ti);
    app.canvas.setGraph(sg);inner.pos=[80,60];inner.size=[420,240];app.canvas.ds.scale=1;app.canvas.ds.offset=[0,0];
    for(let i=0;i<10;i++){app.canvas.setDirty(true,true);app.canvas.draw(true,true);await new Promise(r=>setTimeout(r,200));}
    await b.drainEvents();
    return {graphId:String(sg.id),promotedOk,fields:(await b.fields()).fields.map(f=>f.target.graphId+':'+f.nodeTitle)};})()`);
  const subPoint=await evaluate(textareaPoint);
  if(!sub.promotedOk)throw Error('promoted subgraph field not writable: '+JSON.stringify(sub));passed('a text box promoted to a subgraph node is edited on the outside');
  if(!sub.fields.some(f=>f.startsWith(sub.graphId)))throw Error('subgraph fields not listed: '+JSON.stringify(sub));
  passed('fields inside subgraphs are listed with the subgraph name');
  if(subPoint){await doubleTap(subPoint);const e=await edits();
    if(!e.length||e[0].data.target.graphId!==sub.graphId)throw Error('subgraph double tap: '+JSON.stringify(e));passed('double tap inside an open subgraph edits the inner field');}
  else console.error('note: inner subgraph text box not rendered in this headless frontend; listing checked only');

  // 4. Nodes 2.0 (Vue) text boxes.
  const vue=await evaluate(`(async()=>{${fresh}
    const c=L.createNode('CLIPTextEncode');c.pos=[80,60];app.graph.add(c);
    await app.extensionManager.setting.set('Comfy.VueNodes.Enabled',true);await new Promise(r=>setTimeout(r,1500));
    await b.drainEvents();return c.id;})()`);
  const vuePoint=await evaluate(textareaPoint);
  if(!vuePoint)throw Error('no Vue text box found');
  await touch(...vuePoint);await sleep(700);if((await edits()).length)throw Error('Vue single tap navigated');
  await doubleTap(vuePoint);const ve=await edits();
  if(ve.length!==1||String(ve[0].data.target.nodeId)!==String(vue)||ve[0].data.name!=='text')throw Error('Vue double tap: '+JSON.stringify(ve));
  await evaluate(`window.comfyAPI.app.app.extensionManager.setting.set('Comfy.VueNodes.Enabled',false)`);await sleep(800);
  passed('Nodes 2.0: single tap stays, double tap edits the exact field');

  // 5. Escape closes the frontend's own menus (Android back key).
  const esc=await evaluate(`(async()=>{const L=window.LiteGraph;new L.ContextMenu(['一','二'],{event:new MouseEvent('click',{clientX:200,clientY:200})});
    const before=!!document.querySelector('.litecontextmenu');const r=await window.ComfyMobileEditor.command('escape',{});
    return {before,closed:r.closed,after:!!document.querySelector('.litecontextmenu')};})()`);
  if(!esc.before||!esc.closed||esc.after)throw Error('escape: '+JSON.stringify(esc));passed('back key closes an open context menu first');

  // 6. Downloads started by the page reach the phone.
  const dl=await evaluate(`(async()=>{const b=window.ComfyMobileEditor;await b.command('configure',{nativeDownloads:true});await b.drainEvents();
    const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(['{"x":1}'],{type:'application/json'}));a.download='wf.json';a.click();
    await new Promise(r=>setTimeout(r,500));const e=(await b.drainEvents()).events.find(x=>x.type==='download');
    await b.command('configure',{nativeDownloads:false});return e&&{name:e.data.name,data:e.data.dataUrl.slice(0,40)};})()`);
  if(!dl||dl.name!=='wf.json'||!dl.data.startsWith('data:application/json'))throw Error('download: '+JSON.stringify(dl));passed('blob downloads are handed to the app');

  // 7. Workflows saved on the computer can be listed and opened (read-only here).
  const wf=await evaluate(`(async()=>{const b=window.ComfyMobileEditor;const list=await b.command('workflows',{});if(!list.ok)return list;
    const first=list.workflows.find(w=>!/\\.bak$/.test(w.path));if(!first)return {ok:true,count:0};
    const opened=await b.command('openWorkflow',{path:first.path});const info=await b.command('workflowInfo',{});
    return {ok:opened.ok,count:list.workflows.length,path:first.path,active:info.path,error:opened.error};})()`);
  if(!wf.ok||(wf.count&&wf.active!==wf.path))throw Error('workflows: '+JSON.stringify(wf));passed(`${wf.count} workflows listed; opening one makes it the active tab`);

  // 8. Simple mode selection is saved as the workflow's App-mode data.
  const simple=await evaluate(`(async()=>{${fresh}
    const c=L.createNode('CLIPTextEncode'),k=L.createNode('KSampler'),e=L.createNode('EmptyLatentImage');app.graph.add(c);app.graph.add(k);app.graph.add(e);
    c.connect(0,k,k.inputs.findIndex(i=>i.name==='positive'));e.connect(0,k,k.inputs.findIndex(i=>i.name==='latent_image'));
    const auto=await b.command('simple',{});
    const saved=await b.command('setSimple',{entries:[{nodeId:k.id,name:'steps'}]});
    return {first:auto.fields[0]?.role,names:auto.fields.map(f=>f.name).join(','),source:saved.source,linear:JSON.stringify(app.rootGraph.extra.linearData)};})()`);
  if(simple.first!=='positive'||!/seed/.test(simple.names)||!/width/.test(simple.names)||simple.source!=='workflow'||!simple.linear.includes('"steps"'))throw Error('simple: '+JSON.stringify(simple));
  passed('simple mode detects positive prompt, seed and size, and stores choices as App-mode linearData');

  // 9. Add-node list uses display names and categories.
  const types=await evaluate(`(async()=>{const r=await window.ComfyMobileEditor.command('types',{});const k=r.types.find(t=>t.type==='KSampler');return {count:r.types.length,k};})()`);
  if(!types.k||!types.k.category||!types.k.title)throw Error('types: '+JSON.stringify(types));passed('add-node list shows display names and categories');

  const result={ok:true,checks};fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }catch(error){
  if(evaluate){try{const r=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(out,'failure.png'),Buffer.from(r.data,'base64'));}catch{}}
  throw error;
 }finally{if(ws)ws.close();stop(edge);server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
