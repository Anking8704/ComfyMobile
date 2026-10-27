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
  let lastEditToken='',lastEditAt=0,lastInteractionAt=0;
  function textFieldFor(element) {
    const current=app()?.graph;
    if(!element || !current || !Array.isArray(current._nodes))return null;
    for(const node of current._nodes) for(let index=0;index<(node.widgets||[]).length;index++) {
      const widget=node.widgets[index];
      if(typeof widget.value!=='string' || widget.type==='combo')continue;
      const options=typeof widget.options?.values==='function'?widget.options.values():widget.options?.values;
      if(Array.isArray(options))continue;
      const root=widget.element;
      if(root && (root===element || root.contains?.(element)))
        return {nodeTitle:node.title||node.type,nodeType:node.type,name:widget.name||`字段 ${index+1}`,value:widget.value,target:bind(node,widget,index)};
    }
    return null;
  }
  function requestNativeEditor(event) {
    if(event.type==='focusin' && Date.now()-lastInteractionAt>1500)return;
    const editable=event.target?.closest?.('textarea,input[type="text"],input:not([type]),[contenteditable="true"]');
    const field=textFieldFor(editable);if(!field)return;
    if(event.type!=='focusin') {event.preventDefault?.();event.stopImmediatePropagation?.();}
    const now=Date.now();
    if(field.target.token!==lastEditToken || now-lastEditAt>700) {
      push('edit_field',field);lastEditToken=field.target.token;lastEditAt=now;
    }
    if(event.type==='focusin')editable.blur?.();
  }
  document.addEventListener('pointerdown',event=>{lastInteractionAt=Date.now();requestNativeEditor(event);},true);
  document.addEventListener('keydown',event=>{if(!event.isComposing && (event.key==='Tab'||event.key==='Enter'))lastInteractionAt=Date.now();},true);
  document.addEventListener('focusin',requestNativeEditor,true);
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
