// Checks the iPad web shell against the installed frontend through a read-only proxy.
// /comfymobile/* is served from this repository; everything else is proxied to the local ComfyUI with non-GET requests dropped.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),{spawn,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),out=path.join(root,'build','web-smoke');fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let edge,ws,send,evaluate;
const local={'editor-adapter.js':'app/src/main/assets/editor-adapter.js','prompt-library.json':'app/src/main/assets/prompt-library.json'};
const types={'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.json':'application/json','.png':'image/png','.webmanifest':'application/manifest+json'};
const server=http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://x');
  if(url.pathname.startsWith('/comfymobile/')){
   const name=url.pathname.slice('/comfymobile/'.length)||'index.html';
   const file=local[name]?path.join(root,local[name]):path.join(root,'web/comfymobile_web/static',path.basename(name));
   res.writeHead(200,{'content-type':types[path.extname(file)]||'application/octet-stream'});res.end(fs.readFileSync(file));return;
  }
  const route=req.url.replace(/^\/api\//,'/');
  if(req.method!=='GET') {res.writeHead(200,{'content-type':'application/json'});res.end('{}');return;}
  if(route.startsWith('/userdata')){res.writeHead(200,{'content-type':'application/json'});res.end(route.includes('?')?'[]':'{}');return;}
  // Only the AI assistant plugin's scripts are loaded, so its integration can be checked without every other extension.
  if(route==='/extensions'){let list=[];try{list=(await (await fetch('http://127.0.0.1:8188/extensions')).json()).filter(p=>/AgentLink/i.test(p));}catch{}res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(list));return;}
  const response=await fetch('http://127.0.0.1:8188'+req.url);
  let data;
  if(route==='/object_info') {const all=await response.json();data=Buffer.from(JSON.stringify(Object.fromEntries(Object.entries(all).filter(([name])=>['CLIPTextEncode','KSampler','EmptyLatentImage','SaveImage','LoadImage','CheckpointLoaderSimple'].includes(name)))));}
  else data=Buffer.from(await response.arrayBuffer());
  res.writeHead(response.status,{'content-type':response.headers.get('content-type')||'application/octet-stream','cache-control':'no-store'});res.end(data);
 }catch(error){res.writeHead(502);res.end(String(error));}
});
function stop(child){if(child)spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});}
(async()=>{
 const report={ok:false,sizes:[]};
 try{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
  edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=9241','--user-data-dir='+path.join(out,'profile'),'about:blank'],{windowsHide:true,stdio:'ignore'});
  let target;
  for(let i=0;i<100;i++){try{const all=await(await fetch('http://127.0.0.1:9241/json')).json();target=all.find(t=>t.type==='page');if(target)break;}catch{}await sleep(300);}
  if(!target)throw Error('No browser target');ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  let id=0;const pending=new Map();ws.onmessage=e=>{const r=JSON.parse(e.data);if(r.method==='Page.javascriptDialogOpening'){send('Page.handleJavaScriptDialog',{accept:true}).catch(()=>{});return;}if(pending.has(r.id)){pending.get(r.id)(r);pending.delete(r.id);}};
  send=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,r=>r.error?reject(Error(JSON.stringify(r.error))):resolve(r.result));ws.send(JSON.stringify({id:key,method,params}));setTimeout(()=>{if(pending.delete(key))reject(Error('CDP timeout'));},30000).unref();});
  evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result?.value;};
  const shot=async name=>{const r=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(out,name),Buffer.from(r.data,'base64'));};
  const touch=async(x,y)=>{await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await sleep(40);await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});};
  await send('Page.enable');await send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
  // iPad Air landscape first (full checks), then iPad portrait and a small phone for layout.
  const sizes=[['ipad-landscape',1180,820],['ipad-portrait',820,1180],['phone-portrait',375,667]];
  for(const [name,width,height] of sizes){
   await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:true});
   await send('Page.navigate',{url:`http://127.0.0.1:${port}/comfymobile/`});await sleep(1500);
   let ready=false;for(let i=0;i<120;i++){ready=await evaluate(`(()=>{try{const w=document.querySelector('#editor').contentWindow;return !!(w.ComfyMobileEditor&&w.comfyAPI.app.app.extensionManager.focusMode&&w.LiteGraph?.registered_node_types?.CLIPTextEncode);}catch{return false;}})()`);if(ready)break;await sleep(400);}
   if(!ready)throw Error(name+': adapter not injected or focus mode not applied');
   const setup=await evaluate(`(async()=>{
     const w=document.querySelector('#editor').contentWindow,app=w.comfyAPI.app.app,b=w.ComfyMobileEditor;
     await app.loadGraphData({last_node_id:0,last_link_id:0,nodes:[],links:[],groups:[],config:{},extra:{},version:.4},true,true,null,{skipAssetScans:true,deferWarnings:true});
     await new Promise(r=>setTimeout(r,400));
     const added=await b.command('add',{type:'CLIPTextEncode'});if(!added.ok)throw Error(added.error);
     const node=app.graph.getNodeById(added.nodeId);node.pos=[60,80];node.size=[Math.min(420,innerWidth-140),240];app.canvas.ds.scale=1;app.canvas.ds.offset=[0,0];
     // A real positive-prompt chain, so simple mode has something to detect.
     const L=w.LiteGraph,k=L.createNode('KSampler'),latent=L.createNode('EmptyLatentImage');k.pos=[900,80];latent.pos=[900,500];app.graph.add(k);app.graph.add(latent);
     node.connect(0,k,k.inputs.findIndex(i=>i.name==='positive'));latent.connect(0,k,k.inputs.findIndex(i=>i.name==='latent_image'));
     app.canvas.setDirty(true,true);app.canvas.draw(true,true);
     await new Promise(r=>setTimeout(r,700));
     const widget=node.widgets.find(x=>x.name==='text');widget.value='original prompt';const el=widget.element;
     const input=el?.matches?.('textarea,input')?el:el?.querySelector?.('textarea,input');input.value='original prompt';
     const r=input.getBoundingClientRect(),h=document.querySelector('#handle').getBoundingClientRect(),c=w.document.querySelector('#graph-canvas').getBoundingClientRect();
     return {input:[r.left+r.width/2,r.top+Math.min(30,r.height/2)],handle:[h.left+h.width/2,h.top+h.height/2],canvas:[c.width,c.height],viewport:[innerWidth,innerHeight],node:added.nodeId};
   })()`);
   if(Math.round(setup.canvas[0])!==width||Math.round(setup.canvas[1])!==height)throw Error(name+': canvas is not full screen '+JSON.stringify(setup));
   await shot(name+"-canvas.png");
   await touch(...setup.input);await sleep(900);
   if(!(await evaluate(`document.querySelector('#prompt').hidden`)))throw Error(name+': single tap opened the prompt page');
   await touch(...setup.input);await sleep(120);await touch(setup.input[0]+2,setup.input[1]+1);
   let opened=false;for(let i=0;i<20&&!opened;i++){await sleep(150);opened=await evaluate(`!document.querySelector('#prompt').hidden && document.querySelector('#text').value==='original prompt'`);}
   if(!opened)throw Error(name+': double tap did not open the bound prompt page');
   await sleep(300);await shot(name+'-prompt.png');
   const editing=await evaluate(`(async()=>{
     const label=document.querySelector('#field-label').textContent;
     const items=document.querySelectorAll('#library-list .entry').length;
     const t=document.querySelector('#text');t.value='silver hair';t.setSelectionRange(t.value.length,t.value.length);t.dispatchEvent(new Event('input'));
     document.querySelector('#library-search').value='lighting';document.querySelector('#library-search').dispatchEvent(new Event('input'));
     const first=document.querySelector('#library-list .entry');const english=first.querySelector('.en').textContent;first.click();
     await new Promise(r=>setTimeout(r,100));
     if(!t.value.startsWith('silver hair, '+english))throw Error('library insert wrong: '+t.value);
     document.querySelector('[data-act="save"]').click();await new Promise(r=>setTimeout(r,300));
     const w=document.querySelector('#editor').contentWindow,node=w.comfyAPI.app.app.graph.getNodeById(${setup.node});
     const value=node.widgets.find(x=>x.name==='text').value;if(value!==t.value)throw Error('writeback failed: '+value);
     const sidebar=getComputedStyle(document.querySelector('#library')).display!=='none';
     return {label,items,english,sidebar};
   })()`);
   if(!/正向/.test(editing.label))throw Error(name+': prompt label should name the positive role: '+editing.label);
   if(name==='ipad-landscape'&&!editing.sidebar)throw Error('iPad landscape should keep the library beside the editor');
   await evaluate(`document.querySelector('[data-act="back"]').click()`);await sleep(300);
   await touch(...setup.handle);await sleep(300);
   const sheet=await evaluate(`(()=>{const s=document.querySelector('#sheet');const r=s.getBoundingClientRect();return {open:!s.hidden,inside:r.left>=0&&r.right<=innerWidth&&r.bottom<=innerHeight,buttons:document.querySelectorAll('#actions button').length};})()`);
   if(!sheet.open||!sheet.inside||sheet.buttons<12)throw Error(name+': menu sheet '+JSON.stringify(sheet));
   await shot(name+'-menu.png');
   // AI assistant from the round menu: the shell's handle steps aside while the chat is open and returns afterwards.
   const hasAgent=await evaluate(`!!document.querySelector('#editor').contentWindow.document.querySelector('.al-launch')`);
   let agentCheck='not installed';
   if(hasAgent){
     await evaluate(`[...document.querySelectorAll('#actions button')].find(b=>b.textContent==='AI 助手').click()`);
     let open;for(let i=0;i<20;i++){await sleep(250);open=await evaluate(`(()=>{const d=document.querySelector('#editor').contentWindow.document,w=d.querySelector('.al-float');
       return {win:!!w&&!w.hidden,body:document.body.classList.contains('agent-open'),handle:getComputedStyle(document.querySelector('#handle')).display,button:getComputedStyle(d.querySelector('.al-launch')).display};})()`);if(open.win&&open.body)break;}
     if(!open.win||!open.body||open.handle!=='none'||open.button!=='none')throw Error(name+': AI assistant open '+JSON.stringify(open));
     await shot(name+'-agent.png');
     await evaluate(`document.querySelector('#editor').contentWindow.document.querySelector('.al-launch').click()`);
     let back=false;for(let i=0;i<20&&!back;i++){await sleep(250);back=await evaluate(`!document.body.classList.contains('agent-open')&&getComputedStyle(document.querySelector('#handle')).display!=='none'`);}
     if(!back)throw Error(name+': handle did not return after the assistant closed');
     agentCheck='ok';
     await touch(...setup.handle);await sleep(300);
   }
   await evaluate(`[...document.querySelectorAll('#actions button')].find(b=>b.textContent==='图库').click()`);await sleep(1500);
   const results=await evaluate(`(()=>{const p=document.querySelector('#results');return {open:!p.hidden,summary:document.querySelector('#results-summary').textContent,overflow:document.documentElement.scrollWidth>innerWidth}})()`);
   if(!results.open||results.overflow)throw Error(name+': results page '+JSON.stringify(results));
   await shot(name+'-gallery.png');
   await evaluate('CM.showSimple()');let simple;
   for(let i=0;i<20;i++){await sleep(300);simple=await evaluate(`(()=>{const p=document.querySelector('#simple');const cards=[...document.querySelectorAll('#simple-fields .control')];
     return {open:!p.hidden,cards:cards.length,text:cards.map(c=>c.querySelector('strong').textContent).join(','),overflow:document.documentElement.scrollWidth>innerWidth,
       footer:document.querySelector('.simple-footer').getBoundingClientRect().bottom<=innerHeight+1,image:!document.querySelector('#simple-image').hidden,label:document.querySelector('#simple-result-label').textContent,src:document.querySelector('#simple-image').src.slice(0,80)};})()`);if(simple.cards>0)break;}
   if(!simple.open||!/正向/.test(simple.text)||!/种子/.test(simple.text)||!/宽度/.test(simple.text)||simple.overflow||!simple.footer)throw Error(name+': simple mode '+JSON.stringify(simple));
   await shot(name+'-simple.png');
   // Progress panel: simulated server events go through the real frontend's event bus to the adapter.
   await evaluate(`(()=>{const w=document.querySelector('#editor').contentWindow,api=w.comfyAPI.api.api,app=w.comfyAPI.app.app;
     const k=app.graph._nodes.find(n=>n.type==='KSampler');const fire=(t,d)=>api.dispatchEvent(new CustomEvent(t,{detail:d}));
     window.__cmFire=()=>{fire('execution_start',{prompt_id:'smoke'});fire('status',{exec_info:{queue_remaining:2}});fire('executing',{node:String(k.id),prompt_id:'smoke'});fire('progress',{value:5,max:20,prompt_id:'smoke',node:String(k.id)});};window.__cmFire();})()`);
   let progress;for(let i=0;i<20;i++){await evaluate('window.__cmFire()');await sleep(250);progress=await evaluate(`({shown:!document.querySelector('#simple-progress').hidden,stage:document.querySelector('#simple-stage').textContent,steps:document.querySelector('#simple-steps').textContent,bar:document.querySelector('#simple-bar').style.width})`);if(progress.shown&&progress.steps.includes('5 / 20'))break;}
   if(!progress.shown||!/K采样器|KSampler/.test(progress.stage)||!progress.steps.includes('第 5 / 20 步 · 25%')||!/后面还有 1 个任务/.test(progress.steps)||progress.bar!=='25%')throw Error(name+': progress panel '+JSON.stringify(progress));
   await shot(name+'-progress.png');
   await evaluate(`(()=>{const api=document.querySelector('#editor').contentWindow.comfyAPI.api.api;api.dispatchEvent(new CustomEvent('execution_success',{detail:{prompt_id:'smoke'}}));api.dispatchEvent(new CustomEvent('status',{detail:{exec_info:{queue_remaining:0}}}));})()`);
   let hidden=false;for(let i=0;i<20&&!hidden;i++){await sleep(250);hidden=await evaluate(`document.querySelector('#simple-progress').hidden`);}
   if(!hidden)throw Error(name+': progress panel should hide when the queue is empty');
   // Presets: save the current parameters, change one, apply the preset, and the value comes back.
   await evaluate(`[...document.querySelectorAll('#simple-fields .presets button')].find(b=>b.textContent.includes('存为预设')).click()`);await sleep(300);
   await evaluate(`(()=>{const i=document.querySelector('#modal input');i.value='测试预设';document.querySelector('#modal .buttons .primary').click();})()`);await sleep(800);
   const preset=await evaluate(`(async()=>{const w=document.querySelector('#editor').contentWindow,app=w.comfyAPI.app.app,k=app.graph._nodes.find(n=>n.type==='KSampler'),steps=k.widgets.find(x=>x.name==='steps');
     const before=steps.value;steps.value=before+7;
     const b=[...document.querySelectorAll('#simple-fields .presets button')].find(x=>x.textContent==='测试预设');if(!b)return {missing:true};b.click();
     await new Promise(r=>setTimeout(r,800));return {before,after:steps.value,stored:(app.rootGraph.extra.comfymobile?.presets||[]).map(p=>p.name)};})()`);
   if(preset.missing||preset.after!==preset.before||!preset.stored.includes('测试预设'))throw Error(name+': presets '+JSON.stringify(preset));
   // A result becomes the input image of the LoadImage node.
   const input=await evaluate(`(async()=>{const w=document.querySelector('#editor').contentWindow,app=w.comfyAPI.app.app,L=w.LiteGraph;
     let n=app.graph._nodes.find(x=>x.type==='LoadImage');if(!n){n=L.createNode('LoadImage');n.pos=[900,800];app.graph.add(n);}
     await CM.useAsInput({filename:'ComfyUI_00123_.png',subfolder:'',type:'output'});await new Promise(r=>setTimeout(r,500));
     return n.widgets.find(x=>x.name==='image').value;})()`);
   if(input!=='ComfyUI_00123_.png [output]')throw Error(name+': use as input '+JSON.stringify(input));
   // The viewer swipes through the list it was opened from.
   await evaluate(`CM.openOutput({filename:'ComfyUI_00123_.png',type:'output'},[{filename:'ComfyUI_00123_.png',type:'output'},{filename:'ComfyUI_00122_.png',type:'output'}])`);await sleep(400);
   const sr=await evaluate(`(()=>{const r=document.querySelector('#viewer-stage').getBoundingClientRect();return [r.left+r.width*0.75,r.left+r.width*0.25,r.top+r.height/2];})()`);
   await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:sr[0],y:sr[2]}]});
   for(let i=1;i<=6;i++){await sleep(20);await send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:sr[0]+(sr[1]-sr[0])*i/6,y:sr[2]}]});}
   await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await sleep(400);
   const swiped=await evaluate(`document.querySelector('#viewer-hint').textContent`);
   if(!swiped.startsWith('2 / 2'))throw Error(name+': viewer swipe '+swiped);
   await evaluate(`document.querySelector('[data-act="close-viewer"]').click()`);
   await evaluate(`[...document.querySelectorAll('#simple-fields .control')].find(c=>/正向/.test(c.textContent)).click()`);
   let promptOpen=false;for(let i=0;i<15&&!promptOpen;i++){await sleep(200);promptOpen=await evaluate(`!document.querySelector('#prompt').hidden`);}
   if(!promptOpen)throw Error(name+': simple mode prompt card did not open the editor');
   await evaluate(`document.querySelector('#prompt [data-act="back"]').click()`);await sleep(300);
   if(await evaluate(`document.querySelector('#simple').hidden`))throw Error(name+': back from the prompt editor should return to simple mode');
   await evaluate('CM.showCanvas()');
   report.sizes.push({name,viewport:setup.viewport,label:editing.label,libraryItems:editing.items,inserted:editing.english,sidebar:editing.sidebar,menuButtons:sheet.buttons,results:results.summary,simple:simple.text,agent:agentCheck});
  }
  report.ok=true;report.checks=['adapter injected into the embedded editor','original menus hidden (full-screen canvas)','single tap does not navigate','real touch double tap opens the exact field','bilingual library inserts English only','save writes back to the node','floating menu fits the screen','gallery page has no horizontal overflow','simple mode detects positive prompt, seed and size','simple mode prompt card opens the editor and back returns to simple mode','simple mode shows the running node, step bar, percentage and queue, and hides it when done','parameter presets save and reapply values','a result can become the LoadImage input (name [output])','the viewer swipes to the next result'];
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
 }catch(error){
  if(evaluate){try{await send('Page.captureScreenshot',{format:'png'}).then(r=>fs.writeFileSync(path.join(out,'failure.png'),Buffer.from(r.data,'base64')));}catch{}}
  throw error;
 }finally{if(ws)ws.close();stop(edge);server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
