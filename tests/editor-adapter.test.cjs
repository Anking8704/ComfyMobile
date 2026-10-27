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
  const app = { graph, canvas: { selected_nodes: {}, setDirty() {}, selectNode() {}, ds:{scale:1,offset:[0,0]} }, queuePrompt: async()=>{}, loadGraphData: async(data)=>{graph.configure(data);} };
  const document = { createElement() { return {}; }, head:{appendChild(){}}, querySelector(){return null;} };
  const ctx = vm.createContext({ console, setTimeout, clearTimeout, document, Event, URL, Blob, TextDecoder });
  ctx.window = ctx;
  ctx.comfyAPI = { app:{app}, api:{api:{addEventListener(name,cb){listeners.set(name,cb);}}} };
  ctx.LiteGraph = {registered_node_types:{ThirdPartyText:{}},createNode(){return {...node};}};
  vm.runInContext(source, ctx);
  return { bridge:ctx.ComfyMobileEditor, app, graph, widget, node, listeners };
}

test('custom string widgets are editable and numeric widgets are not prompt fields', async()=>{
  const f=fixture(), result=await f.bridge.fields();
  assert.equal(result.ok,true); assert.equal(result.fields.length,1);
  assert.equal(result.fields[0].value,'原始提示词');
  const target=result.fields[0].target;
  assert.equal((await f.bridge.writeField(target,'新词\nmoonlight')).ok,true);
  assert.equal(f.widget.value,'新词\nmoonlight'); assert.equal(f.widget.last,f.widget.value);
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
