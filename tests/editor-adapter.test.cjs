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
  const domListeners=new Map();
  const document = { addEventListener(name,cb){domListeners.set(name,cb);}, createElement() { return {}; }, head:{appendChild(){}}, querySelector(){return null;} };
  const ctx = vm.createContext({ console, setTimeout, clearTimeout, document, Event, URL, Blob, TextDecoder });
  ctx.window = ctx;
  ctx.comfyAPI = { app:{app}, api:{api:{addEventListener(name,cb){listeners.set(name,cb);}}} };
  ctx.LiteGraph = {registered_node_types:{ThirdPartyText:{}},createNode(){return {...node};}};
  vm.runInContext(source, ctx);
  return { bridge:ctx.ComfyMobileEditor, app, graph, widget, node, listeners, domListeners };
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
test('status and progress events are drained once',async()=>{
  const f=fixture(); f.listeners.get('progress')({detail:{value:7,max:28}});
  const result=await f.bridge.drainEvents(); assert.equal(result.events[0].type,'progress');
  assert.equal(result.events[0].data.value,7); assert.equal((await f.bridge.drainEvents()).events.length,0);
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
