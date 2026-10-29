const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/editor-adapter.js', 'utf8');

function fixture() {
  const widget = { name: 'text', type: 'customtext', value: '原始提示词', callback(v) { this.last = v; } };
  const node = { id: 6, type: 'ThirdPartyText', title: '自定义文本', widgets: [widget, {name:'seed',value:42}], pos: [0,0], connect() {}, inputs: [], outputs: [], setDirtyCanvas() {} };
  const graph = { _nodes: [node], getNodeById(id) { return this._nodes.find(n => String(n.id) === String(id)); }, serialize() { return { nodes: this._nodes.map(n=>({id:n.id,type:n.type,widgets_values:n.widgets.map(w=>w.value)})) }; }, setDirtyCanvas() {}, configure(data) { this._nodes = data.nodes.map(n=>({...node,id:n.id,widgets:[{...widget,value:n.widgets_values[0]}]})); } };
  const listeners = new Map();
  const app = { graph, canvas: { selected_nodes: {}, setDirty() {}, selectNode() {}, ds:{scale:1,offset:[0,0]} }, queuePrompt: async()=>{listeners.get('promptQueueing')?.({detail:{requestId:1}});listeners.get('promptQueued')?.({detail:{requestId:1,batchCount:1}});return true;}, loadGraphData: async(data)=>{graph.configure(data);return true;} };
  const tracker = { checks: 0, checkState() { this.checks++; } };
  app.extensionManager = { workflow: { activeWorkflow: { changeTracker: tracker } } };
  const domListeners=new Map();
  const document = { addEventListener(name,cb){domListeners.set(name,cb);}, createElement() { return {}; }, head:{appendChild(){}}, querySelector(){return null;}, getElementById(){return null;} };
  class FileReader { readAsDataURL(blob){ blob.arrayBuffer().then(b=>{this.result='data:'+(blob.type||'application/octet-stream')+';base64,'+Buffer.from(b).toString('base64');this.onload?.();}); } }
  const ctx = vm.createContext({ console, setTimeout, clearTimeout, document, Event, URL, Blob, TextDecoder, FileReader });
  ctx.window = ctx;
  const posted = [];
  const apiObject = { addEventListener(name,cb){listeners.set(name,cb);}, clientId:'phone', posted,
    responses: {}, async fetchApi(path, init) { if(init?.method==='POST'){posted.push({path,body:JSON.parse(init.body)});return {ok:true,json:async()=>({prompt_id:'new-id'})};} const r=this.responses[path]; return {ok:!!r,status:r?200:404,json:async()=>r}; } };
  ctx.comfyAPI = { app:{app}, api:{api:apiObject} };
  ctx.LiteGraph = {registered_node_types:{ThirdPartyText:{}},createNode(){return {...node};}};
  vm.runInContext(source, ctx);
  return { bridge:ctx.ComfyMobileEditor, app, graph, widget, node, listeners, domListeners, tracker, api:apiObject, ctx };
}

test('custom string widgets are editable and numeric widgets are not prompt fields', async()=>{
  const f=fixture(), result=await f.bridge.fields();
  assert.equal(result.ok,true); assert.equal(result.fields.length,1);
  assert.equal(result.fields[0].value,'原始提示词');
  const target=result.fields[0].target;
  assert.equal((await f.bridge.writeField(target,'新词\nmoonlight')).ok,true);
  assert.equal(f.widget.value,'新词\nmoonlight'); assert.equal(f.widget.last,f.widget.value);
});
test('string-valued dropdowns stay in node parameters and are excluded from prompt fields',async()=>{
  const f=fixture();f.node.widgets.push({name:'sampler_name',type:'combo',value:'euler',options:{values:['euler','dpmpp_2m']}});
  assert.equal((await f.bridge.fields()).fields.length,1);
  assert.equal((await f.bridge.command('nodes',{})).fields.some(field=>field.name==='sampler_name'),true);
});
test('stale field is rejected when a different graph has the same ids', async()=>{
  const f=fixture(), target=(await f.bridge.fields()).fields[0].target;
  f.app.graph={...f.graph,_nodes:[{...f.node,widgets:[{...f.widget}]}]};
  assert.equal((await f.bridge.writeField(target,'wrong')).ok,false);
  assert.equal(f.widget.value,'原始提示词');
});
test('configure with reused node ids invalidates an old field', async()=>{
  const f=fixture(), target=(await f.bridge.fields()).fields[0].target;
  f.graph.configure({nodes:[{id:6,widgets_values:['另一个工作流']}]});
  assert.equal((await f.bridge.writeField(target,'wrong')).ok,false);
  assert.equal(f.graph._nodes[0].widgets[0].value,'另一个工作流');
});
test('replacement and deletion of widget never writes to a different field',async()=>{
  const f=fixture(), target=(await f.bridge.fields()).fields[0].target;
  f.node.widgets[0]={name:'text',value:'replacement'};
  assert.equal((await f.bridge.writeField(target,'wrong')).ok,false);
  assert.equal(f.node.widgets[0].value,'replacement');
});
test('malformed workflow import leaves current graph intact',async()=>{
  const f=fixture();
  assert.equal((await f.bridge.command('import',{json:'{broken'})).ok,false);
  assert.equal(f.widget.value,'原始提示词');
  assert.equal((await f.bridge.command('import',{json:'{"prompt":{}}'})).ok,false);
});
test('export and import preserve workflow values',async()=>{
  const f=fixture(); const out=await f.bridge.command('export',{});
  assert.equal(JSON.parse(out.json).nodes[0].widgets_values[0],'原始提示词');
  assert.equal((await f.bridge.command('import',{json:out.json})).ok,true);
});
test('simultaneous generate commands do not duplicate submission',async()=>{
  const f=fixture(); let complete,calls=0;
  f.app.queuePrompt=()=>{calls++;return new Promise(r=>{complete=r;});};
  const first=f.bridge.command('generate',{});
  assert.equal((await f.bridge.command('generate',{})).ok,false);
  f.listeners.get('promptQueueing')?.({detail:{requestId:1}});f.listeners.get('promptQueued')?.({detail:{requestId:1,batchCount:1}});
  complete(); assert.equal((await first).ok,true); assert.equal(calls,1);
});
test('failed generation is reported without retry',async()=>{
  const f=fixture(); let calls=0;
  f.app.queuePrompt=async()=>{calls++;throw new Error('offline');};
  assert.equal((await f.bridge.command('generate',{})).ok,false); assert.equal(calls,1);
});
test('frontend validation rejection is not reported as submitted',async()=>{
  const f=fixture();f.app.queuePrompt=async()=>false;
  assert.equal((await f.bridge.command('generate',{})).ok,false);
});
test('swallowed frontend network error without promptQueued is not reported as accepted',async()=>{
  const f=fixture();f.app.queuePrompt=async()=>{f.listeners.get('promptQueueing')?.({detail:{requestId:2}});return true;};
  assert.equal((await f.bridge.command('generate',{})).ok,false);
});
test('a false loadGraphData result is reported as a failed import',async()=>{
  const f=fixture();f.app.loadGraphData=async()=>false;
  assert.equal((await f.bridge.command('import',{json:'{"nodes":[]}'})).ok,false);
});
test('a destructive failed import restores the previous graph',async()=>{
  const f=fixture();let calls=0;
  f.app.loadGraphData=async(data)=>{calls++;if(calls===1){f.graph._nodes=[];return false;}f.graph.configure(data);return true;};
  const result=await f.bridge.command('import',{json:'{"nodes":[]}'});
  assert.equal(result.ok,false);assert.equal(f.graph._nodes.length,1);assert.equal(f.graph._nodes[0].widgets[0].value,'原始提示词');
});
test('a target from another page load cannot collide with fresh field tokens',async()=>{
  const first=fixture(),second=fixture();const old=(await first.bridge.fields()).fields[0].target;
  await second.bridge.fields();assert.equal((await second.bridge.writeField(old,'wrong')).ok,false);
});
test('workflow load sequence invalidates targets even when node objects are retained',async()=>{
  const f=fixture();f.app.graphLoadSequence=1;const target=(await f.bridge.fields()).fields[0].target;
  f.app.graphLoadSequence=2;assert.equal((await f.bridge.writeField(target,'wrong')).ok,false);
});
test('progress and queue status are kept as state instead of flooding the event queue',async()=>{
  const f=fixture();
  for(let i=0;i<200;i++)f.listeners.get('status')({detail:{exec_info:{queue_remaining:2}}});
  f.listeners.get('progress')({detail:{value:7,max:28,prompt_id:'p'}});
  f.listeners.get('execution_success')({detail:{prompt_id:'p'}});
  f.listeners.get('progress')({detail:{value:1,max:28,prompt_id:'q'}});
  const result=await f.bridge.drainEvents();
  assert.deepEqual([...result.events.map(e=>e.type)],['execution_success']);
  assert.equal(result.state.queueRemaining,2);assert.equal(result.state.progress.value,1);
  assert.equal((await f.bridge.drainEvents()).events.length,0);
});
test('sampler previews are fetched on demand and only when a newer one exists',async()=>{
  const f=fixture();assert.equal((await f.bridge.preview(0)).seq,0);
  f.listeners.get('b_preview')({detail:new Blob([new Uint8Array([1,2,3])],{type:'image/jpeg'})});
  const state=(await f.bridge.drainEvents()).state;assert.equal(state.previewSeq,1);
  const first=await f.bridge.preview(0);assert.equal(first.seq,1);assert.match(first.dataUrl,/^data:image\/jpeg;base64,/);
  assert.equal((await f.bridge.preview(1)).same,true);
});
function field(f){const input={closest(){return this;},setAttribute(k,v){this[k]=v;},blur(){this.blurred=true;}};f.widget.element=input;return input;}
function tapAt(f,target,x=10,y=10,id=1,moveTo){
  const ev=extra=>({pointerId:id,clientX:x,clientY:y,target,preventDefault(){},...extra});
  f.domListeners.get('pointerdown')(ev({type:'pointerdown'}));
  if(moveTo)f.domListeners.get('pointermove')(ev({type:'pointermove',clientX:moveTo[0],clientY:moveTo[1]}));
  f.domListeners.get('pointerup')(ev({type:'pointerup',...(moveTo?{clientX:moveTo[0],clientY:moveTo[1]}:{})}));
}
const edits=async f=>(await f.bridge.drainEvents()).events.filter(e=>e.type==='edit_field');
test('a single tap on a prompt field keeps the canvas and does not open the native editor',async()=>{
  const f=fixture(),input=field(f);tapAt(f,input);
  assert.equal((await edits(f)).length,0);assert.equal(input.inputmode,'none');
});
test('a double tap on the same prompt field opens the exact native field once',async()=>{
  const f=fixture(),input=field(f);tapAt(f,input);tapAt(f,input,14,12);
  const found=await edits(f);assert.equal(found.length,1);
  assert.equal(found[0].data.name,'text');assert.equal(found[0].data.value,'原始提示词');assert.equal(found[0].data.target.nodeId,6);
});
test('drags, distant taps, different fields and pinches never count as a double tap',async()=>{
  const f=fixture(),input=field(f);
  tapAt(f,input);tapAt(f,input,10,10,1,[60,60]);assert.equal((await edits(f)).length,0);
  tapAt(f,input);tapAt(f,input,200,200);assert.equal((await edits(f)).length,0);
  const other={closest(){return this;},setAttribute(){}};const second={name:'negative',type:'customtext',value:'neg',element:other};f.node.widgets.push(second);
  tapAt(f,input);tapAt(f,other);assert.equal((await edits(f)).length,0);
  f.domListeners.get('pointerdown')({pointerId:1,clientX:10,clientY:10,target:input});
  f.domListeners.get('pointerdown')({pointerId:2,clientX:90,clientY:90,target:input});
  f.domListeners.get('pointerup')({pointerId:1,clientX:10,clientY:10,target:input});
  tapAt(f,input);assert.equal((await edits(f)).length,0);
});
test('the focus that follows a first tap is released without navigation',async()=>{
  const f=fixture(),input=field(f);
  f.domListeners.get('pointerdown')({pointerId:1,clientX:10,clientY:10,target:input});
  f.domListeners.get('focusin')({type:'focusin',target:input});
  assert.equal(input.blurred,true);assert.equal((await edits(f)).length,0);
});
test('keyboard Tab into a prompt field is an explicit edit request',async()=>{
  const f=fixture(),input=field(f);
  f.domListeners.get('keydown')({key:'Tab'});f.domListeners.get('focusin')({type:'focusin',target:input});
  assert.equal((await edits(f)).length,1);
});
function canvasFixture(){
  const f=fixture();const surface={getBoundingClientRect(){return {left:0,top:0};}};
  f.app.canvas.canvas=surface;f.app.canvas.ds={scale:1,offset:[0,0]};f.node.size=[200,100];f.node.title='提示词';
  f.graph.getNodeOnPos=(x,y)=>f.graph._nodes.find(n=>x>=n.pos[0]&&x<=n.pos[0]+n.size[0]&&y>=n.pos[1]-30&&y<=n.pos[1]+n.size[1])||null;
  return {f,surface};
}
test('double tapping the body of a node with one prompt field opens it; the title does not',async()=>{
  const {f,surface}=canvasFixture();
  tapAt(f,surface,50,-10);tapAt(f,surface,50,-10);assert.equal((await edits(f)).length,0);
  tapAt(f,surface,50,50);tapAt(f,surface,52,48);const found=await edits(f);
  assert.equal(found.length,1);assert.equal(found[0].data.target.nodeId,6);
});
test('double tapping a node with several prompt fields asks which field instead of guessing',async()=>{
  const {f,surface}=canvasFixture();f.node.widgets.push({name:'negative',type:'customtext',value:'neg'});
  tapAt(f,surface,50,50);tapAt(f,surface,50,50);const events=(await f.bridge.drainEvents()).events;
  assert.equal(events.some(e=>e.type==='edit_field'),false);
  const pick=events.find(e=>e.type==='pick_field');assert.ok(pick);assert.deepEqual(pick.data.fields.map(x=>x.name),['text','negative']);
});
test('focus mode command hides and restores the original frontend menus',async()=>{
  const f=fixture();f.app.extensionManager={focusMode:false};
  assert.equal((await f.bridge.command('chrome',{focus:true})).focus,true);assert.equal(f.app.extensionManager.focusMode,true);
  assert.equal((await f.bridge.command('chrome',{focus:false})).focus,false);
  delete f.app.extensionManager;assert.equal((await f.bridge.command('chrome',{focus:true})).ok,false);
});
test('search inputs and string dropdowns never trigger prompt-page navigation',async()=>{
  const f=fixture();const input={closest(){return this;}};
  f.domListeners.get('pointerdown')?.({target:input,preventDefault(){throw Error('search intercepted');}});
  f.widget.type='combo';f.widget.options={values:['a','b']};f.widget.element=input;
  f.domListeners.get('pointerdown')?.({target:input,preventDefault(){throw Error('combo intercepted');}});
  assert.equal((await f.bridge.drainEvents()).events.length,0);
});
test('frontend initialization focusing a widget does not open the native prompt page',async()=>{
  const f=fixture();const input={closest(){return this;},blur(){throw Error('boot focus should not be intercepted');}};f.widget.element=input;
  f.domListeners.get('focusin')?.({type:'focusin',target:input});
  assert.equal((await f.bridge.drainEvents()).events.length,0);
});
test('a pointerup lost outside the page does not swallow the next double tap',async()=>{
  const f=fixture(),input=field(f);
  f.domListeners.get('pointerdown')({pointerId:1,clientX:10,clientY:10,target:input});
  await new Promise(r=>setTimeout(r,500));
  tapAt(f,input,10,10,2);tapAt(f,input,10,10,3);assert.equal((await edits(f)).length,1);
});
test('a text field driven by a link is not offered for editing from the node body',async()=>{
  const {f,surface}=canvasFixture();f.node.inputs=[{name:'text',link:9,widget:{name:'text'}}];
  tapAt(f,surface,50,50);tapAt(f,surface,50,50);assert.equal((await f.bridge.drainEvents()).events.length,0);
});
test('a canvas-drawn single-line text widget is claimed so the web prompt never opens on one tap',async()=>{
  const {f,surface}=canvasFixture();delete f.widget.element;f.node.getWidgetOnPos=()=>f.widget;let blocked=false;
  f.domListeners.get('pointerdown')({pointerId:1,clientX:50,clientY:50,target:surface,preventDefault(){blocked=true;},stopImmediatePropagation(){}});
  f.domListeners.get('pointerup')({pointerId:1,clientX:50,clientY:50,target:surface});
  assert.equal(blocked,true);assert.equal((await edits(f)).length,0);
  tapAt(f,surface,50,50);const found=await edits(f);assert.equal(found.length,1);assert.equal(found[0].data.name,'text');
});
function linkedFixture(){
  const f=fixture();
  const sampler={id:9,type:'KSampler',title:'K采样器',widgets:[{name:'seed',type:'number',value:5,options:{min:0,max:100,step2:1,precision:0}},{name:'cfg',type:'number',value:7,options:{min:0,max:30,step2:0.1,precision:1}}],
    inputs:[{name:'positive',link:1},{name:'negative',link:2}],outputs:[],pos:[300,0],setDirtyCanvas(){}};
  const negative={id:7,type:'CLIPTextEncode',title:'负面',widgets:[{name:'text',type:'customtext',value:'blurry'}],inputs:[],outputs:[{links:[2]}],pos:[0,200],setDirtyCanvas(){}};
  f.node.outputs=[{links:[1]}];f.graph._nodes.push(sampler,negative);
  f.graph.links=new Map([[1,{target_id:9,target_slot:0}],[2,{target_id:9,target_slot:1}]]);
  return {f,sampler,negative};
}
test('prompt fields report whether they feed the positive or negative sampler input',async()=>{
  const {f}=linkedFixture();const fields=(await f.bridge.fields()).fields;
  assert.equal(fields.find(x=>x.target.nodeId===6).role,'positive');assert.equal(fields.find(x=>x.target.nodeId===7).role,'negative');
});
test('programmatic edits are recorded so the frontend undo restores exactly them',async()=>{
  const {f}=linkedFixture();const before=f.tracker.checks;
  f.graph.remove=function(n){this._nodes=this._nodes.filter(x=>x!==n);};
  await f.bridge.command('remove',{nodeId:7});
  await f.bridge.writeField((await f.bridge.fields()).fields[0].target,'new text');
  assert.equal(f.tracker.checks,before+2);
});
test('numeric parameters are validated against the node limits',async()=>{
  const {f}=linkedFixture();const fields=(await f.bridge.command('nodes',{})).fields;
  const seed=fields.find(x=>x.name==='seed'&&x.target.nodeId===9),cfg=fields.find(x=>x.name==='cfg');
  assert.equal(seed.number.precision,0);assert.equal(seed.number.max,100);
  assert.equal((await f.bridge.command('widget',{target:seed.target,value:7.5})).ok,false);
  assert.equal((await f.bridge.command('widget',{target:seed.target,value:101})).ok,false);
  assert.equal((await f.bridge.command('widget',{target:cfg.target,value:6.5})).ok,true);
});
test('a text field driven by a link is not offered as an editable prompt field',async()=>{
  const f=fixture();f.node.inputs=[{name:'text',link:3,widget:{name:'text'}}];
  assert.equal((await f.bridge.fields()).fields.length,0);
  assert.equal((await f.bridge.command('nodes',{})).fields.find(x=>x.name==='text').linked,true);
});
test('bypass and mute toggle node state and are recorded',async()=>{
  const f=fixture();f.node.mode=0;
  assert.equal((await f.bridge.command('mode',{nodeId:6,mode:'bypass'})).mode,'bypass');assert.equal(f.node.mode,4);
  assert.equal((await f.bridge.command('mode',{nodeId:6,mode:'bypass'})).mode,'normal');assert.equal(f.tracker.checks,2);
});
function subgraphFixture(){
  const f=fixture();
  const innerWidget={name:'text',type:'customtext',value:'inside'};
  const inner={id:1,type:'CLIPTextEncode',title:'内部提示词',widgets:[innerWidget],inputs:[],outputs:[],pos:[0,0],setDirtyCanvas(){}};
  const sub={id:'sg-1',name:'人物子图',_nodes:[inner],getNodeById(id){return this._nodes.find(n=>String(n.id)===String(id));},setDirtyCanvas(){}};
  f.graph.subgraphs=new Map([['sg-1',sub]]);
  return {f,sub,inner,innerWidget};
}
test('prompt fields inside subgraphs are listed, labelled and written to the right graph',async()=>{
  const {f,innerWidget}=subgraphFixture();const fields=(await f.bridge.fields()).fields;
  const inside=fields.find(x=>x.target.graphId==='sg-1');assert.equal(inside.nodeTitle,'人物子图 › 内部提示词');
  assert.equal((await f.bridge.writeField(inside.target,'edited')).ok,true);assert.equal(innerWidget.value,'edited');
  assert.equal(f.widget.value,'原始提示词');
});
test('a subgraph removed from the workflow invalidates its fields',async()=>{
  const {f}=subgraphFixture();const inside=(await f.bridge.fields()).fields.find(x=>x.target.graphId==='sg-1');
  f.graph.subgraphs.clear();assert.equal((await f.bridge.writeField(inside.target,'lost')).ok,false);
});
test('while a subgraph is open, taps and the node list use the graph on screen',async()=>{
  const {f,sub,innerWidget}=subgraphFixture();f.app.canvas.graph=sub;
  const input={closest(){return this;},setAttribute(){}};innerWidget.element=input;
  tapAt(f,input);tapAt(f,input);const found=await edits(f);
  assert.equal(found.length,1);assert.equal(found[0].data.target.graphId,'sg-1');
  assert.deepEqual([...(await f.bridge.command('nodes',{})).nodes.map(n=>n.id)],[1]);
});
test('Nodes 2.0 text boxes map to their widget through the input slot key',async()=>{
  const f=fixture();f.node.inputs=[{name:'clip'},{name:'text',widget:{name:'text'}}];
  const nodeEl={getAttribute:k=>k==='data-node-id'?'6':null};
  const holder={closest:sel=>sel==='[data-node-id]'?nodeEl:null,querySelector:sel=>sel==='[data-slot-key]'?{getAttribute:()=>'6-in-1'}:null};
  const area={closest:sel=>sel==='[data-testid="node-widget"]'?holder:sel.startsWith('textarea')?area:null,setAttribute(){}};
  tapAt(f,area);tapAt(f,area);const found=await edits(f);
  assert.equal(found.length,1);assert.equal(found[0].data.name,'text');
});
test('simple mode detects key controls, and a saved selection is stored in the workflow App-mode data',async()=>{
  const {f}=linkedFixture();f.graph.extra={};
  const auto=await f.bridge.command('simple',{});assert.equal(auto.source,'auto');
  assert.deepEqual([...auto.fields.slice(0,2).map(x=>x.role)],['positive','negative']);
  assert.ok(auto.fields.some(x=>x.name==='seed'));
  const saved=await f.bridge.command('setSimple',{entries:[{nodeId:9,name:'cfg'},{nodeId:6,name:'text'}]});
  assert.equal(saved.source,'workflow');assert.deepEqual([...saved.fields.map(x=>x.name)],['cfg','text']);
  assert.equal(JSON.stringify(f.graph.extra.linearData.inputs),JSON.stringify([[9,'cfg'],[6,'text']]));
});
test('re-running a past task submits its prompt with fresh seeds',async()=>{
  const f=fixture();f.api.responses['/jobs/abc']={workflow:{prompt:{'3':{class_type:'KSampler',inputs:{seed:1,steps:20}}},extra_data:{extra_pnginfo:{}}}};
  const r=await f.bridge.command('loadJob',{id:'abc',mode:'rerun'});
  assert.equal(r.ok,true);const sent=f.api.posted[0];assert.equal(sent.path,'/prompt');
  assert.notEqual(sent.body.prompt['3'].inputs.seed,1);assert.equal(sent.body.prompt['3'].inputs.steps,20);assert.equal(sent.body.client_id,'phone');
});
test('an empty queue clears a running job whose end event was lost during a reconnect',async()=>{
  const f=fixture();f.listeners.get('execution_start')({detail:{prompt_id:'p'}});
  assert.notEqual((await f.bridge.drainEvents()).state.running,null);
  f.listeners.get('status')({detail:{exec_info:{queue_remaining:0}}});
  assert.equal((await f.bridge.drainEvents()).state.running,null);
});
test('a simple-mode entry for a deleted subgraph never falls back to a root node with the same id',async()=>{
  const {f}=linkedFixture();f.graph.subgraphs=new Map();
  f.graph.extra={linearData:{inputs:[['gone-sg:9:cfg','cfg']]}};
  const r=await f.bridge.command('simple',{});
  assert.equal(r.source,'auto');
});
test('simple mode lists every model loader (UNET, CLIP, VAE, each LoRA with its strength)',async()=>{
  const {f}=linkedFixture();f.graph.extra={};
  const combo=(name,value)=>({name,type:'combo',value,options:{values:[value,'other.safetensors']}});
  const loaders=[
    {id:20,type:'UNETLoader',title:'UNet加载器',widgets:[combo('unet_name','flux.safetensors'),combo('weight_dtype','default')]},
    {id:21,type:'CLIPLoader',title:'CLIP加载器',widgets:[combo('clip_name','qwen.safetensors'),combo('type','qwen_image'),combo('device','default')]},
    {id:22,type:'VAELoader',title:'VAE加载器',widgets:[combo('vae_name','ae.safetensors')]},
    ...[23,24,25].map(id=>({id,type:'LoraLoaderModelOnly',title:'LoRA '+id,widgets:[combo('lora_name','l'+id+'.safetensors'),{name:'strength_model',type:'number',value:1,options:{min:-10,max:10,step2:0.01}}]})),
  ].map(n=>({...n,inputs:[],outputs:[],pos:[0,0],setDirtyCanvas(){}}));
  f.graph._nodes.push(...loaders);
  const names=[...(await f.bridge.command('simple',{})).fields.map(x=>x.name)];
  for(const n of ['unet_name','clip_name','type','vae_name'])assert.ok(names.includes(n),n);
  assert.equal(names.filter(n=>n==='lora_name').length,3);assert.equal(names.filter(n=>n==='strength_model').length,3);
  assert.ok(!names.includes('device')&&!names.includes('weight_dtype'));
  const i=names.indexOf('lora_name');assert.equal(names[i+1],'strength_model');
});
test('state names the node that is currently running',async()=>{
  const f=fixture();f.listeners.get('execution_start')({detail:{prompt_id:'p'}});
  f.listeners.get('executing')({detail:{node:'6',prompt_id:'p'}});
  assert.equal((await f.bridge.drainEvents()).state.executingTitle,'自定义文本');
});
