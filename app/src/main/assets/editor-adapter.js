(() => {
  if (window.ComfyMobileEditor) return;
  const sessions = new WeakMap();
  const targets = new Map();
  const events = [];
  const pageId = Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
  let serial = 0, submitting = false, hookedApi = null, submission = null;
  const fail = error => ({ ok: false, error: String(error?.message || error) });
  const app = () => window.comfyAPI?.app?.app || window.app;
  const graph = () => {
    const current = app()?.graph;
    if (!current || !Array.isArray(current._nodes)) throw new Error('工作流尚未加载，请等待编辑器就绪');
    if (!sessions.has(current)) sessions.set(current, `${pageId}-graph-${++serial}`);
    return current;
  };
  function bind(node, widget, index) {
    const current = graph(), session = sessions.get(current);
    for (const [token, entry] of targets) {
      if (entry.graph === current && entry.node === node && entry.widget === widget && entry.index === index && entry.sequence === app().graphLoadSequence)
        return { session, nodeId: node.id, widgetName: widget.name || '', widgetIndex: index, token };
    }
    const token = `field-${++serial}`;
    targets.set(token, { graph: current, node, widget, index, sequence: app().graphLoadSequence });
    if (targets.size > 5000) targets.delete(targets.keys().next().value);
    return { session, nodeId: node.id, widgetName: widget.name || '', widgetIndex: index, token };
  }
  function lookup(target) {
    const entry = targets.get(target?.token), current = graph();
    if (!entry || current !== entry.graph || sessions.get(current) !== target.session
        || current.getNodeById(target.nodeId) !== entry.node
        || entry.node.widgets?.[entry.index] !== entry.widget || (entry.widget.name || '') !== target.widgetName
        || entry.sequence !== app().graphLoadSequence)
      throw new Error('工作流或字段已改变，草稿已保留，请重新选择目标');
    return entry;
  }
  function allFields(textOnly) {
    return graph()._nodes.flatMap(node => (node.widgets || []).flatMap((widget, index) => {
      if (textOnly && typeof widget.value !== 'string') return [];
      if (!['string','number','boolean'].includes(typeof widget.value)) return [];
      const options = typeof widget.options?.values === 'function' ? widget.options.values() : widget.options?.values;
      if(textOnly && (widget.type==='combo' || Array.isArray(options))) return [];
      return [{ nodeTitle: node.title || node.type, nodeType: node.type, name: widget.name || `字段 ${index+1}`,
        value: widget.value, type: widget.type || typeof widget.value,
        options: Array.isArray(options) ? options.filter(x=>['string','number'].includes(typeof x)) : null,
        target: bind(node,widget,index) }];
    }));
  }
  function write(target, value, textOnly) {
    const { node, widget } = lookup(target);
    if (textOnly && typeof widget.value !== 'string') throw new Error('该控件不是标准文本字段');
    if (typeof widget.value !== typeof value) throw new Error('字段类型不匹配');
    const current = graph(), previous = widget.value;
    current.beforeChange?.();
    try {
      widget.value = value;
      if (widget.element && 'value' in widget.element) {
        widget.element.value = value;
        widget.element.dispatchEvent(new Event('input', { bubbles: true }));
      }
      widget.callback?.call(widget, value, app().canvas, node);
      node.onWidgetChanged?.(widget.name, value, previous, widget);
      node.setDirtyCanvas?.(true,true);
      current.setDirtyCanvas?.(true,true);
    } finally { current.afterChange?.(); }
    return { ok:true, value:widget.value };
  }
  function push(type, data) {
    events.push({ type, data });
    if (events.length > 50) events.shift();
  }
  let lastEditToken='',lastEditAt=0,lastPointerAt=0,lastKeyAt=0,tap=null,lastTap=null;
  const TAP_MOVE=14,TAP_TIME=450,DOUBLE_GAP=450,DOUBLE_DIST=40;
  function isTextWidget(widget) {
    if(typeof widget?.value!=='string' || widget.type==='combo')return false;
    const options=typeof widget.options?.values==='function'?widget.options.values():widget.options?.values;
    return !Array.isArray(options);
  }
  function describe(node,widget,index) {
    return {nodeTitle:node.title||node.type,nodeType:node.type,name:widget.name||`字段 ${index+1}`,value:widget.value,target:bind(node,widget,index)};
  }
  function textFieldFor(element) {
    const current=app()?.graph;
    if(!element || !current || !Array.isArray(current._nodes))return null;
    for(const node of current._nodes) for(let index=0;index<(node.widgets||[]).length;index++) {
      const widget=node.widgets[index];if(!isTextWidget(widget))continue;
      const root=widget.element;
      if(root && (root===element || root.contains?.(element)))return describe(node,widget,index);
    }
    return null;
  }
  function editableOf(event) {return event.target?.closest?.('textarea,input[type="text"],input:not([type]),[contenteditable="true"]')||null;}
  // Double tap on the body of a node (not its title) edits its text field; several fields ask the phone to choose.
  function nodeHit(event) {
    const canvas=app()?.canvas,current=app()?.graph;
    if(!canvas?.canvas || event.target!==canvas.canvas || !canvas.ds || !current?.getNodeOnPos)return null;
    const rect=canvas.canvas.getBoundingClientRect(),scale=canvas.ds.scale||1;
    const x=(event.clientX-rect.left)/scale-canvas.ds.offset[0],y=(event.clientY-rect.top)/scale-canvas.ds.offset[1];
    const node=current.getNodeOnPos(x,y,current._nodes);
    if(!node || node.flags?.collapsed || y<node.pos[1])return null;
    const widget=node.getWidgetOnPos?.(x,y,true);if(widget && !isTextWidget(widget))return null;
    // Canvas-drawn single-line text widgets would open the web prompt on the first tap; they are claimed directly.
    if(widget && !widget.element && !linked(node,widget)){const index=node.widgets.indexOf(widget),field=describe(node,widget,index);return {key:field.target.token,field,block:true};}
    const fields=(node.widgets||[]).flatMap((w,index)=>isTextWidget(w)&&!linked(node,w)?[describe(node,w,index)]:[]);
    return fields.length?{key:`node-${node.id}`,node,fields}:null;
  }
  // A widget converted to a linked input is driven by its link, so editing its text would have no effect.
  function linked(node,widget){return (node.inputs||[]).some(input=>input.link!=null && (input.widget?.name===widget.name || input.name===widget.name));}
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
    else{try{hit=nodeHit(event);}catch(ignored){hit=null;}}
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
  function hook() {
    const api = window.comfyAPI?.api?.api;
    if (!api || api === hookedApi) return;
    hookedApi = api;
    api.addEventListener('promptQueueing', event => {
      if(submission && submission.requestId==null) submission.requestId=event.detail?.requestId;
    });
    api.addEventListener('promptQueued', event => {
      if(submission && event.detail?.requestId===submission.requestId && event.detail?.batchCount>0) submission.accepted=true;
    });
    for (const type of ['status','progress','executing','executed','execution_error','execution_start','execution_success'])
      api.addEventListener(type, event => push(type,event.detail));
    const preview = blob => {
      if (!(blob instanceof Blob)) return;
      if (window.ComfyMobileEditor.previewUrl) URL.revokeObjectURL(window.ComfyMobileEditor.previewUrl);
      window.ComfyMobileEditor.previewUrl = URL.createObjectURL(blob);
      if (blob.size < 2*1024*1024 && typeof FileReader !== 'undefined') {
        const reader=new FileReader();reader.onload=()=>push('preview',{dataUrl:reader.result});reader.readAsDataURL(blob);
      }
    };
    api.addEventListener('b_preview', event => preview(event.detail));
    api.addEventListener('b_preview_with_metadata', event => preview(event.detail?.blob));
  }
  async function liteGraph() {
    if (window.LiteGraph) return window.LiteGraph;
    // The frontend publishes LiteGraph on its stable compatibility API.
    const exposed = window.comfyAPI?.litegraph?.LiteGraph;
    if (exposed) return exposed;
    throw new Error('此版本未公开节点注册表，请用编辑器自带的添加节点菜单');
  }
  async function command(name,args={}) {
    const current = graph(), instance = app();
    switch(name) {
      case 'nodes': return {ok:true,nodes:current._nodes.map(n=>({id:n.id,title:n.title||n.type,type:n.type,
        inputs:(n.inputs||[]).map((s,i)=>({index:i,name:s.name,type:s.type,linked:s.link!=null})),
        outputs:(n.outputs||[]).map((s,i)=>({index:i,name:s.name,type:s.type}))})),fields:allFields(false)};
      case 'types': { const L=await liteGraph();return {ok:true,types:Object.keys(L.registered_node_types).sort()}; }
      case 'add': {
        const L=await liteGraph(), node=L.createNode(args.type);
        if(!node) throw new Error('无法创建该节点');
        node.pos=[100,100]; current.add(node); instance.canvas?.selectNode?.(node); current.setDirtyCanvas?.(true,true);
        return {ok:true,nodeId:node.id};
      }
      case 'select': {
        const node=current.getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        instance.canvas?.selectNode?.(node);instance.canvas?.centerOnNode?.(node);return {ok:true};
      }
      case 'remove': {
        const node=current.getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        current.remove(node);return {ok:true};
      }
      case 'duplicate': {
        const source=current.getNodeById(args.nodeId), clone=source?.clone?.();
        if(!clone) throw new Error('此节点不支持复制');
        clone.pos=[source.pos[0]+50,source.pos[1]+50];current.add(clone);return {ok:true};
      }
      case 'connect': {
        const from=current.getNodeById(args.from),to=current.getNodeById(args.to);
        if(!from||!to) throw new Error('节点不存在');
        const link=from.connect(args.output,to,args.input);if(!link) throw new Error('端口类型不兼容，无法连线');
        return {ok:true};
      }
      case 'disconnect': {
        const node=current.getNodeById(args.nodeId);if(!node) throw new Error('节点不存在');
        node.disconnectInput(args.input);return {ok:true};
      }
      case 'widget':return write(args.target,args.value,false);
      case 'chrome': {
        // Focus mode is the frontend's own way to hide its menus; its hamburger button restores them.
        const manager=instance.extensionManager;
        if(!manager || typeof manager.focusMode!=='boolean') throw new Error('此前端版本不支持隐藏原版菜单');
        if(typeof args.focus==='boolean') manager.focusMode=args.focus;
        return {ok:true,focus:manager.focusMode};
      }
      case 'fit': {
        const canvas=instance.canvas;if(canvas?.fitView) canvas.fitView();
        else if(canvas?.ds && current._nodes.length) {
          const minX=Math.min(...current._nodes.map(n=>n.pos[0])),minY=Math.min(...current._nodes.map(n=>n.pos[1]));
          const maxX=Math.max(...current._nodes.map(n=>n.pos[0]+(n.size?.[0]||300))),maxY=Math.max(...current._nodes.map(n=>n.pos[1]+(n.size?.[1]||180)));
          const w=canvas.canvas?.width||400,h=canvas.canvas?.height||650;
          canvas.ds.scale=Math.max(.1,Math.min(1,(w-80)/(maxX-minX),(h-80)/(maxY-minY)));
          canvas.ds.offset=[40/canvas.ds.scale-minX,40/canvas.ds.scale-minY];canvas.setDirty?.(true,true);
        }return {ok:true};
      }
      case 'undo': {
        const manager=window.comfyAPI?.app?.app?.extensionManager?.command;
        if(manager?.execute) {await manager.execute('Comfy.Undo');return {ok:true};}
        if(instance.changeTracker?.undo) {await instance.changeTracker.undo();return {ok:true};}
        throw new Error('请使用编辑器菜单中的撤销');
      }
      case 'export':return {ok:true,json:JSON.stringify((instance.rootGraph || current).serialize(),null,2)};
      case 'import': {
        const data=JSON.parse(args.json);
        if(!data || !Array.isArray(data.nodes)) throw new Error('请选择 ComfyUI 工作流 JSON，不能导入 API prompt');
        for(const n of data.nodes) if(n.id==null || typeof n.type!=='string') throw new Error('工作流节点数据不完整');
        const previous=JSON.parse(JSON.stringify((instance.rootGraph || current).serialize()));
        try {
          const loaded=await instance.loadGraphData(data);
          if(loaded===false) throw new Error('工作流未成功载入');
        } catch(error) {
          let restored=false;
          try {restored=(await instance.loadGraphData(previous))!==false;}catch(ignored){}
          targets.clear();
          throw new Error(`${error.message || error}；${restored?'原工作流已恢复':'自动恢复失败，请从“更多”导出导入前备份'}`);
        }
        targets.clear();return {ok:true};
      }
      case 'generate': {
        if(submitting || instance.processingQueue) throw new Error('正在提交，请勿重复点击');
        if(typeof instance.queuePrompt!=='function') throw new Error('当前前端不支持提交，请使用编辑器中的运行按钮');
        submitting=true;
        const attempt={requestId:null,accepted:false};submission=attempt;
        try {
          const accepted=await instance.queuePrompt(0,1);
          if(accepted===false || !attempt.accepted) throw new Error('未收到电脑接受此任务的确认，请查看节点校验提示和任务记录，勿直接重复生成');
          return {ok:true};
        } finally {submitting=false;submission=null;}
      }
      default:throw new Error('不支持的操作');
    }
  }
  window.ComfyMobileEditor={
    snapshot:async()=>{try{hook();const g=graph();return {ok:true,session:sessions.get(g),nodeCount:g._nodes.length};}catch(e){return fail(e);}},
    fields:async()=>{try{hook();return {ok:true,fields:allFields(true)};}catch(e){return fail(e);}},
    readField:async(target)=>{try{return {ok:true,value:lookup(target).widget.value};}catch(e){return fail(e);}},
    writeField:async(target,text)=>{try{return write(target,text,true);}catch(e){return fail(e);}},
    command:async(name,args)=>{try{hook();return await command(name,args);}catch(e){return fail(e);}},
    drainEvents:async()=>{hook();return {ok:true,events:events.splice(0)};},
    previewUrl:null
  };
  hook();
})();
