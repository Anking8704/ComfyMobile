(() => {
  if (window.ComfyMobileEditor) return;
  const sessions = new WeakMap();
  const targets = new Map();
  const tokenByPlace = new Map();
  // Only rare, meaningful events are queued; high-frequency status/progress/preview data lives in `state`.
  const events = [];
  const state = { progress: null, queueRemaining: 0, running: null, executing: null, previewSeq: 0 };
  const pageId = Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
  const hidden = { toolbar: false, selection: false };
  let serial = 0, submitting = false, hookedApi = null, submission = null, latestPreview = null, nativeDownloads = false;
  const fail = error => ({ ok: false, error: String(error?.message || error) });
  const app = () => window.comfyAPI?.app?.app || window.app;
  const api = () => window.comfyAPI?.api?.api;

  // ---------- graphs: the root workflow, the graph on screen (may be a subgraph) and all subgraph definitions ----------
  function root() {
    let current;
    try { current = app()?.rootGraph ?? app()?.graph; } catch (ignored) { current = app()?.graph; }
    if (!current || !Array.isArray(current._nodes)) throw new Error('工作流尚未加载，请等待编辑器就绪');
    if (!sessions.has(current)) sessions.set(current, `${pageId}-graph-${++serial}`);
    return current;
  }
  function viewed() {
    const r = root(), shown = app()?.canvas?.graph;
    return shown && Array.isArray(shown._nodes) && ownsGraph(r, shown) ? shown : r;
  }
  const isMap = value => typeof value?.get === 'function' && typeof value?.values === 'function';
  function ownsGraph(r, g) { return g === r || (isMap(r.subgraphs) && r.subgraphs.get(g?.id) === g); }
  function allGraphs() {
    const r = root(), subs = isMap(r.subgraphs) ? [...r.subgraphs.values()] : [];
    return [r, ...subs.filter(g => Array.isArray(g?._nodes))];
  }
  const graphPrefix = graph => graph === root() ? '' : `${graph.name || '子图'} › `;
  function track() {
    // Programmatic edits must be recorded, or the frontend's undo jumps back past them.
    try {
      const tracker = app()?.extensionManager?.workflow?.activeWorkflow?.changeTracker;
      (tracker?.checkState || tracker?.captureCanvasState)?.call(tracker);
    } catch (ignored) {}
  }

  // ---------- field identity ----------
  function bind(graph, node, widget, index) {
    const r = root(), session = sessions.get(r), sequence = app().graphLoadSequence;
    const describeTarget = token => ({ session, nodeId: node.id, graphId: graph === r ? 'root' : String(graph.id), widgetName: widget.name || '', widgetIndex: index, token });
    const place = `${session}|${graph === r ? 'root' : graph.id}|${node.id}|${index}|${sequence}`, known = targets.get(tokenByPlace.get(place));
    if (known && known.root === r && known.graph === graph && known.node === node && known.widget === widget && known.index === index && known.sequence === sequence)
      return describeTarget(tokenByPlace.get(place));
    const token = `field-${++serial}`;
    targets.set(token, { root: r, graph, node, widget, index, sequence });tokenByPlace.set(place, token);
    if (targets.size > 5000) targets.delete(targets.keys().next().value);
    if (tokenByPlace.size > 5000) tokenByPlace.delete(tokenByPlace.keys().next().value);
    return describeTarget(token);
  }
  function lookup(target) {
    const entry = targets.get(target?.token), r = root();
    if (!entry || entry.root !== r || sessions.get(r) !== target.session || !ownsGraph(r, entry.graph)
        || entry.graph.getNodeById(target.nodeId) !== entry.node
        || entry.node.widgets?.[entry.index] !== entry.widget || (entry.widget.name || '') !== target.widgetName
        || entry.sequence !== app().graphLoadSequence)
      throw new Error('工作流或字段已改变，草稿已保留，请重新选择目标');
    return entry;
  }
  function comboOptions(widget) {
    const options = typeof widget.options?.values === 'function' ? widget.options.values() : widget.options?.values;
    return Array.isArray(options) ? options : null;
  }
  function isTextWidget(widget) {
    return typeof widget?.value === 'string' && widget.type !== 'combo' && !comboOptions(widget);
  }
  // A widget converted to a linked input is driven by its link, so editing its text would have no effect.
  function linked(node, widget) {
    return (node.inputs || []).some(input => input.link != null && (input.widget?.name === widget.name || input.name === widget.name));
  }
  function linkById(graph, id) { return graph.getLink?.(id) ?? graph.links?.get?.(id) ?? graph.links?.[id]; }
  /** Follows the conditioning a text node produces down to a sampler input named positive/negative. */
  function conditioningRole(graph, node) {
    const found = new Set(), seen = new Set([node]);
    let frontier = [node];
    for (let depth = 0; depth < 5 && frontier.length; depth++) {
      const next = [];
      // Only conditioning and text flow carry a prompt; following CLIP or MODEL links would mark loaders as both.
      for (const current of frontier) for (const output of current.outputs || []) if (/CONDITIONING|STRING/i.test(String(output.type ?? 'CONDITIONING'))) for (const id of output.links || []) {
        const link = linkById(graph, id), target = link && graph.getNodeById(link.target_id);
        const name = String(target?.inputs?.[link?.target_slot]?.name || '').toLowerCase();
        if (/^(positive|negative)/.test(name)) { found.add(name.startsWith('positive') ? 'positive' : 'negative'); continue; }
        if (target && !seen.has(target)) { seen.add(target); next.push(target); }
      }
      frontier = next;
    }
    return found.size === 1 ? [...found][0] : found.size > 1 ? 'mixed' : null;
  }
  function numberOptions(widget) {
    const o = widget.options || {};
    const step = o.step2 ?? (typeof o.step === 'number' ? o.step / 10 : null);
    return { min: o.min ?? null, max: o.max ?? null, step, precision: o.precision ?? null, round: o.round ?? null };
  }
  function describe(graph, node, widget, index) {
    const options = comboOptions(widget);
    return {
      nodeTitle: graphPrefix(graph) + (node.title || node.type), nodeType: node.type, name: widget.name || `字段 ${index+1}`,
      label: widget.label || widget.name || `字段 ${index+1}`, value: widget.value, type: widget.type || typeof widget.value,
      options: options ? options.filter(x => ['string','number'].includes(typeof x)) : null,
      number: typeof widget.value === 'number' ? numberOptions(widget) : null,
      role: isTextWidget(widget) ? conditioningRole(graph, node) : null,
      linked: linked(node, widget), bypassed: node.mode === 4 || node.mode === 2,
      target: bind(graph, node, widget, index)
    };
  }
  function fieldsOf(graph, textOnly) {
    return graph._nodes.flatMap(node => (node.widgets || []).flatMap((widget, index) => {
      if (!['string','number','boolean'].includes(typeof widget.value)) return [];
      if (textOnly && (!isTextWidget(widget) || linked(node, widget))) return [];
      return [describe(graph, node, widget, index)];
    }));
  }
  function validateNumber(widget, value) {
    if (!Number.isFinite(value)) throw new Error('请输入有效数字');
    const { min, max, precision } = numberOptions(widget);
    if (min != null && value < min) throw new Error(`不能小于 ${min}`);
    if (max != null && value > max) throw new Error(`不能大于 ${max}`);
    if (precision === 0 && !Number.isInteger(value)) throw new Error('该参数只接受整数');
  }
  function write(target, value, textOnly) {
    const { graph, node, widget } = lookup(target);
    if (textOnly && typeof widget.value !== 'string') throw new Error('该控件不是标准文本字段');
    if (typeof widget.value !== typeof value) throw new Error('字段类型不匹配');
    if (typeof value === 'number') validateNumber(widget, value);
    const previous = widget.value, choices = comboOptions(widget);
    graph.beforeChange?.();
    try {
      // A freshly uploaded image is not in the dropdown yet; add it so the widget and the frontend agree.
      if (typeof value === 'string' && Array.isArray(widget.options?.values) && choices && !choices.includes(value)) widget.options.values.push(value);
      widget.value = value;
      if (widget.element && 'value' in widget.element) {
        widget.element.value = value;
        widget.element.dispatchEvent(new Event('input', { bubbles: true }));
      }
      widget.callback?.call(widget, value, app().canvas, node);
      node.onWidgetChanged?.(widget.name, value, previous, widget);
      node.setDirtyCanvas?.(true,true);
      graph.setDirtyCanvas?.(true,true);
    } finally { graph.afterChange?.(); track(); }
    return { ok:true, value:widget.value };
  }
  function push(type, data) {
    events.push({ type, data });
    if (events.length > 100) events.shift();
  }

  // ---------- touch: a double tap edits a prompt; single taps keep selecting and dragging ----------
  let lastEditToken='',lastEditAt=0,lastPointerAt=0,lastKeyAt=0,tap=null,lastTap=null;
  const TAP_MOVE=14,TAP_TIME=450,DOUBLE_GAP=450,DOUBLE_DIST=40;
  function editableOf(event) {return event.target?.closest?.('textarea,input[type="text"],input:not([type]),[contenteditable="true"]')||null;}
  // Nodes 2.0 (Vue) renders widgets as DOM; the input slot key names the widget exactly.
  function vueWidget(holder) {
    const id = holder?.closest?.('[data-node-id]')?.getAttribute?.('data-node-id');if(id==null)return null;
    const graph = viewed(), node = graph.getNodeById(id);if(!node)return null;
    const key = holder.querySelector('[data-slot-key]')?.getAttribute('data-slot-key')||'';
    const slot = /-in-(\d+)$/.exec(key), input = slot ? node.inputs?.[Number(slot[1])] : null;
    let name = input ? (input.widget?.name ?? input.name) : null;
    if (name == null) name = holder.querySelector('[aria-label]')?.getAttribute('aria-label');
    const index = (node.widgets||[]).findIndex(w => w.name === name);
    return index < 0 ? null : { graph, node, widget: node.widgets[index], index };
  }
  function textFieldFor(element) {
    let graph;try{graph=viewed();}catch(ignored){return null;}
    if(!element)return null;
    for(const g of graph===root()?[graph]:[graph,root()]) for(const node of g._nodes) for(let index=0;index<(node.widgets||[]).length;index++) {
      const widget=node.widgets[index];if(!isTextWidget(widget))continue;
      const box=widget.element;
      if(box && (box===element || box.contains?.(element)))return linked(node,widget)?null:describe(g,node,widget,index);
    }
    const found=vueWidget(element.closest?.('[data-testid="node-widget"]'));
    return found && isTextWidget(found.widget) && !linked(found.node,found.widget) ? describe(found.graph,found.node,found.widget,found.index) : null;
  }
  function nodeFields(graph,node){return (node.widgets||[]).flatMap((w,index)=>isTextWidget(w)&&!linked(node,w)?[describe(graph,node,w,index)]:[]);}
  // Double tap on the body of a node (not its title) edits its text field; several fields ask the phone to choose.
  function nodeHit(event) {
    const nodeEl=event.target?.closest?.('[data-node-id]');
    if(nodeEl?.getAttribute){
      if(event.target.closest('[data-testid^="node-header"],[data-testid*="slot"],button,input,select,[data-testid="node-widget"]'))return null;
      const graph=viewed(),node=graph.getNodeById(nodeEl.getAttribute('data-node-id'));if(!node)return null;
      const fields=nodeFields(graph,node);return fields.length?{key:`node-${node.id}`,node,fields}:null;
    }
    const canvas=app()?.canvas;
    if(!canvas?.canvas || event.target!==canvas.canvas || !canvas.ds)return null;
    const graph=viewed();if(!graph.getNodeOnPos)return null;
    const rect=canvas.canvas.getBoundingClientRect(),scale=canvas.ds.scale||1;
    const x=(event.clientX-rect.left)/scale-canvas.ds.offset[0],y=(event.clientY-rect.top)/scale-canvas.ds.offset[1];
    const node=graph.getNodeOnPos(x,y,graph._nodes);
    if(!node || node.flags?.collapsed || y<node.pos[1])return null;
    const widget=node.getWidgetOnPos?.(x,y,true);if(widget && !isTextWidget(widget))return null;
    // Canvas-drawn single-line text widgets would open the web prompt on the first tap; they are claimed directly.
    if(widget && !widget.element && !linked(node,widget)){const field=describe(graph,node,widget,node.widgets.indexOf(widget));return {key:field.target.token,field,block:true};}
    const fields=nodeFields(graph,node);
    return fields.length?{key:`node-${node.id}`,node,fields}:null;
  }
  function requestEdit(field) {
    const now=Date.now();
    if(field.target.token!==lastEditToken || now-lastEditAt>700) {push('edit_field',field);lastEditToken=field.target.token;lastEditAt=now;}
  }
  function openHit(hit) {
    if(hit.field)requestEdit(hit.field);
    else if(hit.fields.length===1)requestEdit(hit.fields[0]);
    else push('pick_field',{nodeId:hit.node.id,nodeTitle:hit.node.title||hit.node.type,fields:hit.fields});
  }
  document.addEventListener('pointerdown',event=>{
    lastPointerAt=Date.now();
    if(tap && tap.id!==event.pointerId){
      // A second finger cancels the tap; a pointerup that never arrived (released outside the page) must not swallow this one.
      if(lastPointerAt-tap.at<TAP_TIME){tap=null;lastTap=null;return;}
      tap=null;
    }
    const editable=editableOf(event),field=textFieldFor(editable);let hit=null;
    if(field){editable.setAttribute?.('inputmode','none');hit={key:field.target.token,field};}
    else if(!editable){try{hit=nodeHit(event);}catch(ignored){hit=null;}}
    if(hit?.block){event.preventDefault?.();event.stopImmediatePropagation?.();}
    tap={id:event.pointerId,x:event.clientX,y:event.clientY,at:lastPointerAt,hit,moved:false};
  },true);
  document.addEventListener('pointermove',event=>{
    if(tap && tap.id===event.pointerId && Math.hypot(event.clientX-tap.x,event.clientY-tap.y)>TAP_MOVE)tap.moved=true;
  },true);
  document.addEventListener('pointercancel',()=>{tap=null;lastTap=null;},true);
  document.addEventListener('pointerup',event=>{
    const done=tap;if(!done || done.id!==event.pointerId)return;tap=null;
    const now=Date.now();
    if(done.moved || !done.hit || now-done.at>TAP_TIME){lastTap=null;return;}
    if(lastTap && lastTap.key===done.hit.key && now-lastTap.at<DOUBLE_GAP && Math.hypot(done.x-lastTap.x,done.y-lastTap.y)<DOUBLE_DIST) {
      lastTap=null;openHit(done.hit);
    } else lastTap={key:done.hit.key,x:done.x,y:done.y,at:now};
  },true);
  document.addEventListener('keydown',event=>{if(!event.isComposing && (event.key==='Tab'||event.key==='Enter'))lastKeyAt=Date.now();},true);
  // A tap must not raise the web keyboard; a keyboard Tab/Enter into a prompt field is an explicit edit request.
  document.addEventListener('focusin',event=>{
    const editable=editableOf(event),field=textFieldFor(editable);if(!field)return;
    const now=Date.now();
    if(now-lastKeyAt<1500 && lastKeyAt>lastPointerAt){editable.blur?.();requestEdit(field);}
    else if(now-lastPointerAt<1500)editable.blur?.();
  },true);

  // ---------- downloads started by the original editor (export, save image) are handed to the phone ----------
  function captureDownload(anchor) {
    if(!nativeDownloads || !anchor?.hasAttribute?.('download'))return false;
    const href=anchor.href||'',name=anchor.getAttribute('download')||'download';
    if(href.startsWith('blob:')||href.startsWith('data:')) {
      fetch(href).then(r=>r.blob()).then(blob=>{
        if(blob.size>20*1024*1024){push('download',{name,error:'文件超过 20 MB，请在电脑上下载'});return;}
        const reader=new FileReader();reader.onload=()=>push('download',{name,mime:blob.type,dataUrl:reader.result});reader.readAsDataURL(blob);
      }).catch(error=>push('download',{name,error:String(error.message||error)}));
      return true;
    }
    try{const url=new URL(href,location.href);if(url.origin===location.origin){push('download',{name,url:url.pathname+url.search});return true;}}catch(ignored){}
    return false;
  }
  if(typeof HTMLAnchorElement!=='undefined'){
    const anchorClick=HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click=function(){if(captureDownload(this))return;return anchorClick.call(this);};
  }
  document.addEventListener('click',event=>{const a=event.target?.closest?.('a[download]');if(a&&captureDownload(a))event.preventDefault();},true);

  // ---------- server events ----------
  function hook() {
    const source = api();
    if (!source || source === hookedApi) return;
    hookedApi = source;
    source.addEventListener('promptQueueing', event => {
      if(submission && submission.requestId==null) submission.requestId=event.detail?.requestId;
    });
    source.addEventListener('promptQueued', event => {
      if(submission && event.detail?.requestId===submission.requestId && event.detail?.batchCount>0) submission.accepted=true;
    });
    source.addEventListener('status', event => { const remaining=event.detail?.exec_info?.queue_remaining; if(typeof remaining==='number')state.queueRemaining=remaining; });
    source.addEventListener('progress', event => { const d=event.detail||{}; state.progress={value:d.value,max:d.max,node:d.node??null,prompt_id:d.prompt_id??null}; });
    source.addEventListener('executing', event => { const d=event.detail; state.executing=d==null?null:(typeof d==='object'?(d.node??null):d); });
    source.addEventListener('execution_start', event => { state.running=event.detail?.prompt_id??true;state.progress=null;push('execution_start',{prompt_id:event.detail?.prompt_id}); });
    for (const type of ['execution_success','execution_error','execution_interrupted']) source.addEventListener(type, event => {
      const d=event.detail||{};state.running=null;state.progress=null;state.executing=null;
      push(type,{prompt_id:d.prompt_id,node_id:d.node_id,node_type:d.node_type,exception_message:d.exception_message});
    });
    source.addEventListener('executed', event => push('executed',{node:event.detail?.node,prompt_id:event.detail?.prompt_id}));
    const preview = blob => { if (blob instanceof Blob) { latestPreview=blob; state.previewSeq++; } };
    source.addEventListener('b_preview', event => preview(event.detail));
    source.addEventListener('b_preview_with_metadata', event => preview(event.detail?.blob));
  }
  async function liteGraph() {
    if (window.LiteGraph) return window.LiteGraph;
    // The frontend publishes LiteGraph on its stable compatibility API.
    const exposed = window.comfyAPI?.litegraph?.LiteGraph;
    if (exposed) return exposed;
    throw new Error('此版本未公开节点注册表，请用编辑器自带的添加节点菜单');
  }
  function workflowStore() {
    const store=app()?.extensionManager?.workflow;
    if(!store)throw new Error('此前端版本不支持工作流管理，请使用原版菜单');
    return store;
  }
  const workflowName = wf => wf ? (wf.filename || String(wf.path||'').split('/').pop().replace(/\.json$/i,'') || '未命名') : '未命名';
  function workflowInfo() {
    const store=workflowStore(),active=store.activeWorkflow,shown=app()?.canvas?.graph;
    return {ok:true,name:workflowName(active),path:active?.path||null,temporary:!!active?.isTemporary,modified:!!active?.isModified,
      subgraph:shown&&shown!==root()?(shown.name||'子图'):null,
      open:(store.openWorkflows||[]).map(w=>({path:w.path,name:workflowName(w),modified:!!w.isModified,active:w===active,temporary:!!w.isTemporary}))};
  }
  async function openWorkflow(path) {
    const store=workflowStore(),wf=store.getWorkflowByPath?.(path);
    if(!wf)throw new Error('找不到该工作流，可能已在电脑上删除或改名');
    if(wf===store.activeWorkflow)return {ok:true};
    const loaded=await wf.load?.()??wf;
    await app().loadGraphData(loaded.activeState??loaded.initialState,true,true,wf);
    targets.clear();return {ok:true};
  }
  function seedInputs(prompt) {
    for (const node of Object.values(prompt || {})) for (const key of ['seed','noise_seed'])
      if (typeof node?.inputs?.[key] === 'number') node.inputs[key] = Math.floor(Math.random() * 2 ** 48);
    return prompt;
  }
  // ---------- simple mode: the workflow's own App-mode selection (extra.linearData), or detected key controls ----------
  const KEY_WIDGETS=['seed','noise_seed','control_after_generate','steps','cfg','sampler_name','scheduler','denoise','width','height','batch_size','ckpt_name','unet_name','lora_name','strength_model','image'];
  function parseEntry(entry) {
    const [first,name]=Array.isArray(entry)?entry:[];
    if(typeof first==='string'&&/^[^:]+:[^:]+:[^:]+$/.test(first)){const [graphId,nodeId,widget]=first.split(':').map(decodeURIComponent);return {graphId,nodeId,name:widget};}
    return first==null||typeof name!=='string'?null:{graphId:null,nodeId:first,name};
  }
  function resolveEntry(parsed) {
    const r=root(),graph=parsed.graphId&&isMap(r.subgraphs)?(r.subgraphs.get(parsed.graphId)||r):r;
    const node=graph.getNodeById(parsed.nodeId),index=(node?.widgets||[]).findIndex(w=>w.name===parsed.name);
    return index<0?null:describe(graph,node,node.widgets[index],index);
  }
  function detectKeyFields() {
    const r=root(),picked=[];
    for(const node of r._nodes){
      if(node.mode===4||node.mode===2)continue;
      (node.widgets||[]).forEach((widget,index)=>{
        if(linked(node,widget)||!['string','number','boolean'].includes(typeof widget.value))return;
        const text=isTextWidget(widget),role=text?conditioningRole(r,node):null;
        if((text&&role)||KEY_WIDGETS.includes(widget.name))picked.push(describe(r,node,widget,index));
      });
    }
    const rank=f=>f.role==='positive'?0:f.role==='negative'?1:f.role?2:3+Math.max(0,KEY_WIDGETS.indexOf(f.name));
    // At most two of each kind (prompts by role, other controls by name), so big workflows still show size, model and image.
    const seen=new Map();
    return picked.sort((a,b)=>rank(a)-rank(b)).filter(f=>{const kind=f.role?'text-'+f.role:f.name,n=(seen.get(kind)||0)+1;seen.set(kind,n);return n<=(f.role==='mixed'?1:2);}).slice(0,24);
  }
  function simpleFields() {
    const data=root().extra?.linearData,entries=Array.isArray(data?.inputs)?data.inputs:[];
    const chosen=entries.map(parseEntry).filter(Boolean).map(resolveEntry).filter(Boolean);
    return chosen.length?{ok:true,source:'workflow',fields:chosen}:{ok:true,source:'auto',fields:detectKeyFields()};
  }
  async function command(name,args={}) {
    const instance = app();
    switch(name) {
      case 'nodes': {
        const graph=viewed();
        return {ok:true,graph:graph===root()?null:(graph.name||'子图'),nodes:graph._nodes.map(n=>({id:n.id,title:n.title||n.type,type:n.type,mode:n.mode===4?'bypass':n.mode===2?'mute':'normal',
          subgraph:!!n.isSubgraphNode?.(),
          inputs:(n.inputs||[]).map((s,i)=>({index:i,name:s.label||s.name,type:String(s.type),linked:s.link!=null,widget:!!s.widget})),
          outputs:(n.outputs||[]).map((s,i)=>({index:i,name:s.label||s.name,type:String(s.type)}))})),fields:fieldsOf(graph,false)};
      }
      case 'types': {
        const L=await liteGraph();
        return {ok:true,types:Object.entries(L.registered_node_types).map(([type,cls])=>({type,title:cls.title||cls.display_name||type,category:cls.category||''}))
          .sort((a,b)=>(a.category+a.title).localeCompare(b.category+b.title))};
      }
      case 'add': {
        const L=await liteGraph(), node=L.createNode(args.type), graph=viewed();
        if(!node) throw new Error('无法创建该节点');
        const canvas=instance.canvas,ds=canvas?.ds,el=canvas?.canvas;
        if(ds&&el){const w=el.clientWidth||el.width||400,h=el.clientHeight||el.height||600;node.pos=[w/2/ds.scale-ds.offset[0]-(node.size?.[0]||200)/2,h/2/ds.scale-ds.offset[1]-(node.size?.[1]||100)/2];}
        else node.pos=[100,100];
        graph.add(node); canvas?.selectNode?.(node); graph.setDirtyCanvas?.(true,true); track();
        return {ok:true,nodeId:node.id};
      }
      case 'select': {
        const node=viewed().getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        instance.canvas?.selectNode?.(node);instance.canvas?.centerOnNode?.(node);return {ok:true};
      }
      case 'remove': {
        const graph=viewed(),node=graph.getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        graph.remove(node);track();return {ok:true};
      }
      case 'duplicate': {
        const graph=viewed(),source=graph.getNodeById(args.nodeId), clone=source?.clone?.();
        if(!clone) throw new Error('此节点不支持复制');
        clone.pos=[source.pos[0]+50,source.pos[1]+50];graph.add(clone);track();return {ok:true};
      }
      case 'connect': {
        const graph=viewed(),from=graph.getNodeById(args.from),to=graph.getNodeById(args.to);
        if(!from||!to) throw new Error('节点不存在');
        const link=from.connect(args.output,to,args.input);if(!link) throw new Error('端口类型不兼容，无法连线');
        track();return {ok:true};
      }
      case 'disconnect': {
        const node=viewed().getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        node.disconnectInput(args.input);track();return {ok:true};
      }
      case 'mode': {
        const graph=viewed(),node=graph.getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        const wanted={bypass:4,mute:2,normal:0}[args.mode];if(wanted==null)throw new Error('未知节点状态');
        node.mode=node.mode===wanted?0:wanted;node.setDirtyCanvas?.(true,true);graph.setDirtyCanvas?.(true,true);track();
        return {ok:true,mode:node.mode===4?'bypass':node.mode===2?'mute':'normal'};
      }
      case 'widget':return write(args.target,args.value,false);
      case 'chrome': {
        // Focus mode is the frontend's own way to hide its menus; its hamburger button restores them.
        const manager=instance.extensionManager;
        if(!manager || typeof manager.focusMode!=='boolean') throw new Error('此前端版本不支持隐藏原版菜单');
        if(typeof args.focus==='boolean') manager.focusMode=args.focus;
        return {ok:true,focus:manager.focusMode};
      }
      case 'configure': {
        // Runtime-only tweaks for touch screens; nothing here is written to the computer's settings.
        const canvas=instance?.canvas;if(!canvas)throw new Error('编辑器尚未就绪');
        if(typeof args.nativeDownloads==='boolean')nativeDownloads=args.nativeDownloads;
        if(typeof args.hideInfo==='boolean'){
          // The frontend re-applies its setting on some state changes, so the debug overlay is pinned off on this canvas only.
          if(args.hideInfo)Object.defineProperty(canvas,'show_info',{configurable:true,get:()=>false,set(){}});
          else {delete canvas.show_info;canvas.show_info=true;}
          canvas.setDirty?.(true,true);
        }
        if(canvas&&typeof args.performance==='boolean'&&'min_font_size_for_lod' in canvas){
          if(args.performance){canvas.__cmLod??=canvas.min_font_size_for_lod;canvas.min_font_size_for_lod=Math.max(12,canvas.__cmLod||0);}
          else if(canvas.__cmLod!=null){canvas.min_font_size_for_lod=canvas.__cmLod;}
          canvas.setDirty?.(true,true);
        }
        if(typeof args.toolbar==='boolean')hidden.toolbar=!args.toolbar;
        // On a phone the selection toolbox pops up over the node just tapped, where the second tap of a double tap lands.
        if(typeof args.selectionToolbox==='boolean')hidden.selection=!args.selectionToolbox;
        let style=document.getElementById('comfymobile-style');
        if(!style){style=document.createElement('style');style.id='comfymobile-style';document.head.appendChild(style);}
        style.textContent=(hidden.toolbar?'.graph-canvas-panel [role="toolbar"].p-buttongroup{display:none!important}':'')+(hidden.selection?'.selection-toolbox{display:none!important}':'');
        return {ok:true};
      }
      case 'escape': {
        const open='.litecontextmenu,.p-dialog-mask,.p-contextmenu,.p-popover,.p-select-overlay,.comfy-vue-node-search-container,.graphdialog,.litesearchbox';
        const had=!!document.querySelector(open);
        const target=document.activeElement&&document.activeElement!==document.body?document.activeElement:document;
        for(const type of ['keydown','keyup'])target.dispatchEvent(new KeyboardEvent(type,{key:'Escape',code:'Escape',keyCode:27,bubbles:true,cancelable:true}));
        window.LiteGraph?.closeAllContextMenus?.(window);
        document.querySelectorAll('.graphdialog,.litesearchbox').forEach(d=>d.close?d.close():d.remove());
        await new Promise(r=>setTimeout(r,60));
        return {ok:true,closed:had};
      }
      case 'fit': {
        const canvas=instance.canvas,graph=viewed();if(canvas?.fitView) canvas.fitView();
        else if(canvas?.ds && graph._nodes.length) {
          const minX=Math.min(...graph._nodes.map(n=>n.pos[0])),minY=Math.min(...graph._nodes.map(n=>n.pos[1]));
          const maxX=Math.max(...graph._nodes.map(n=>n.pos[0]+(n.size?.[0]||300))),maxY=Math.max(...graph._nodes.map(n=>n.pos[1]+(n.size?.[1]||180)));
          const w=canvas.canvas?.width||400,h=canvas.canvas?.height||650;
          canvas.ds.scale=Math.max(.1,Math.min(1,(w-80)/(maxX-minX),(h-80)/(maxY-minY)));
          canvas.ds.offset=[40/canvas.ds.scale-minX,40/canvas.ds.scale-minY];canvas.setDirty?.(true,true);
        }return {ok:true};
      }
      case 'undo': case 'redo': {
        const manager=instance.extensionManager?.command;
        if(manager?.execute) {await manager.execute(name==='undo'?'Comfy.Undo':'Comfy.Redo');return {ok:true};}
        const tracker=instance.changeTracker;
        if(tracker?.[name]) {await tracker[name]();return {ok:true};}
        throw new Error('请使用编辑器菜单中的撤销/重做');
      }
      case 'export':return {ok:true,json:JSON.stringify(root().serialize(),null,2)};
      case 'import': {
        const data=JSON.parse(args.json);
        if(!data || !Array.isArray(data.nodes)) throw new Error('请选择 ComfyUI 工作流 JSON，不能导入 API prompt');
        for(const n of data.nodes) if(n.id==null || typeof n.type!=='string') throw new Error('工作流节点数据不完整');
        const previous=JSON.parse(JSON.stringify(root().serialize()));
        try {
          const loaded=await instance.loadGraphData(data);
          if(loaded===false) throw new Error('工作流未成功载入');
        } catch(error) {
          let restored=false;
          try {restored=(await instance.loadGraphData(previous))!==false;}catch(ignored){}
          targets.clear();
          throw new Error(`${error.message || error}；${restored?'原工作流已恢复':'自动恢复失败，请从“更多”导出导入前备份'}`);
        }
        targets.clear();tokenByPlace.clear();return {ok:true};
      }
      case 'generate': {
        if(submitting || instance.processingQueue) throw new Error('正在提交，请勿重复点击');
        if(typeof instance.queuePrompt!=='function') throw new Error('当前前端不支持提交，请使用编辑器中的运行按钮');
        const batch=Math.max(1,Math.min(100,Math.floor(Number(args.batch)||1)));
        submitting=true;
        const attempt={requestId:null,accepted:false};submission=attempt;
        try {
          const accepted=await instance.queuePrompt(args.front?-1:0,batch);
          if(accepted===false || !attempt.accepted) throw new Error('未收到电脑接受此任务的确认，请查看节点校验提示和任务记录，勿直接重复生成');
          return {ok:true};
        } finally {submitting=false;submission=null;}
      }
      case 'workflowInfo':return workflowInfo();
      case 'workflows': {
        const store=workflowStore();try{await store.syncWorkflows?.();}catch(ignored){}
        return {ok:true,active:store.activeWorkflow?.path||null,workflows:(store.workflows||[])
          .filter(w=>!w.isTemporary&&String(w.path||'').startsWith('workflows/')&&/\.json$/i.test(w.path))
          .map(w=>({path:w.path,name:String(w.path).slice('workflows/'.length).replace(/\.json$/i,''),modified:w.lastModified??null,open:(store.openWorkflows||[]).includes(w)}))
          .sort((a,b)=>(b.modified||0)-(a.modified||0))};
      }
      case 'openWorkflow':return openWorkflow(args.path);
      case 'newWorkflow': {await instance.extensionManager.command.execute('Comfy.NewBlankWorkflow');targets.clear();return {ok:true};}
      case 'saveWorkflow': {
        const store=workflowStore(),active=store.activeWorkflow;
        if(!args.force&&active&&!active.isTemporary&&!args.as){
          // Warn before overwriting a file that the computer (or another device) changed after it was opened here.
          try{
            const dir=String(active.path).split('/').slice(0,-1).join('/')||'workflows',file=String(active.path).slice(dir.length+1);
            const list=await (await api().fetchApi(`/userdata?dir=${encodeURIComponent(dir)}&recurse=true&full_info=true`)).json();
            const info=Array.isArray(list)?list.find(x=>x.path===file):null;
            if(info&&active.lastModified&&info.modified>active.lastModified+1000)return {ok:false,conflict:true,error:'电脑上的这个工作流在你打开之后被修改过，保存会覆盖那些修改'};
          }catch(ignored){}
        }
        // The frontend's own command handles naming new workflows and overwrite confirmation on screen.
        instance.extensionManager.command.execute(args.as||active?.isTemporary?'Comfy.SaveWorkflowAs':'Comfy.SaveWorkflow');
        return {ok:true,prompted:!!(args.as||active?.isTemporary)};
      }
      case 'loadJob': {
        const response=await api().fetchApi(`/jobs/${encodeURIComponent(args.id)}`);
        if(!response.ok)throw new Error('读取历史任务失败：HTTP '+response.status);
        const job=await response.json(),source=job.workflow||{};
        if(args.mode==='rerun'){
          const body={prompt:seedInputs(JSON.parse(JSON.stringify(source.prompt||{}))),extra_data:source.extra_data||{},client_id:api().clientId??api().initialClientId};
          const queued=await api().fetchApi('/prompt',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
          const result=await queued.json().catch(()=>({}));
          if(!queued.ok||result.error)throw new Error('电脑拒绝了这个任务：'+(result.error?.message||result.error||queued.status));
          return {ok:true,prompt_id:result.prompt_id};
        }
        const graph=source.extra_data?.extra_pnginfo?.workflow;
        if(!graph)throw new Error('这个任务没有保存工作流信息，无法载入');
        await instance.loadGraphData(graph,true,true,`历史任务 ${String(args.id).slice(0,8)}`);
        targets.clear();return {ok:true};
      }
      case 'simple':return simpleFields();
      case 'allFields':return {ok:true,fields:allGraphs().flatMap(g=>fieldsOf(g,false)).filter(x=>!x.linked)};
      case 'setSimple': {
        const r=root(),entries=(args.entries||[]).map(e=>e.graphId&&e.graphId!=='root'?[`${encodeURIComponent(e.graphId)}:${encodeURIComponent(e.nodeId)}:${encodeURIComponent(e.name)}`,e.name]:[e.nodeId,e.name]);
        r.extra=r.extra||{};const previous=r.extra.linearData||{};
        r.extra.linearData={...previous,inputs:entries,outputs:Array.isArray(previous.outputs)?previous.outputs:[]};
        track();return simpleFields();
      }
      default:throw new Error('不支持的操作');
    }
  }
  window.ComfyMobileEditor={
    snapshot:async()=>{try{hook();const g=root();return {ok:true,session:sessions.get(g),nodeCount:g._nodes.length};}catch(e){return fail(e);}},
    fields:async()=>{try{hook();return {ok:true,fields:allGraphs().flatMap(g=>fieldsOf(g,true))};}catch(e){return fail(e);}},
    readField:async(target)=>{try{return {ok:true,value:lookup(target).widget.value};}catch(e){return fail(e);}},
    writeField:async(target,text)=>{try{return write(target,text,true);}catch(e){return fail(e);}},
    command:async(name,args)=>{try{hook();return await command(name,args);}catch(e){return fail(e);}},
    drainEvents:async()=>{hook();return {ok:true,events:events.splice(0),state:{...state,progress:state.progress&&{...state.progress}}};},
    /** The latest sampler preview, converted only when the caller does not have this sequence yet. */
    preview:async(known)=>{
      try{
        if(!latestPreview)return {ok:true,seq:0};
        const seq=state.previewSeq;if(known===seq)return {ok:true,seq,same:true};
        if(latestPreview.size>4*1024*1024)return {ok:true,seq,same:true};
        const dataUrl=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(latestPreview);});
        return {ok:true,seq,dataUrl};
      }catch(e){return fail(e);}
    }
  };
  hook();
})();
