// cm-canvas: the floating handle, the action sheet and everything it opens on the canvas.
(() => {
  'use strict';
  const { $, store } = CM;
  const handle = $('#handle'), sheet = $('#sheet');

  // ---------- draggable handle ----------
  function placeHandle() {
    const m = 12, size = handle.offsetWidth || 56;
    const right = store.get('menuRight', true), ratio = Math.min(1, Math.max(0, store.get('menuY', 0.82)));
    const style = getComputedStyle(document.documentElement);
    const top = parseFloat(style.getPropertyValue('--safe-top')) || 0, bottom = parseFloat(style.getPropertyValue('--safe-bottom')) || 0;
    const range = Math.max(0, innerHeight - size - 2 * m - top - bottom);
    handle.style.left = right ? '' : `calc(var(--safe-left) + ${m}px)`;
    handle.style.right = right ? `calc(var(--safe-right) + ${m}px)` : '';
    handle.style.top = `${top + m + range * ratio}px`;
  }
  (() => {
    let start = null, dragging = false, dragEndedAt = 0;
    handle.addEventListener('pointerdown', e => { start = { x: e.clientX, y: e.clientY, left: handle.offsetLeft, top: handle.offsetTop }; dragging = false; handle.setPointerCapture(e.pointerId); });
    handle.addEventListener('pointermove', e => {
      if (!start) return;
      if (!dragging && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 8) { dragging = true; handle.classList.add('dragging'); }
      if (!dragging) return;
      handle.style.right = ''; handle.style.left = `${Math.min(innerWidth - handle.offsetWidth, Math.max(0, start.left + e.clientX - start.x))}px`;
      handle.style.top = `${Math.min(innerHeight - handle.offsetHeight, Math.max(0, start.top + e.clientY - start.y))}px`;
    });
    const end = () => {
      if (!start) return; const wasDrag = dragging; start = null; dragging = false; handle.classList.remove('dragging');
      if (!wasDrag) return;
      dragEndedAt = Date.now();
      store.set('menuRight', handle.offsetLeft + handle.offsetWidth / 2 > innerWidth / 2);
      store.set('menuY', Math.min(1, Math.max(0, (handle.offsetTop - 12) / Math.max(1, innerHeight - handle.offsetHeight - 24))));
      placeHandle();
    };
    handle.addEventListener('pointerup', end); handle.addEventListener('pointercancel', end);
    // Open on click, not pointerup: otherwise the same tap's click lands on the menu button that appears under the finger.
    handle.addEventListener('click', () => { if (Date.now() - dragEndedAt > 300) openSheet(); });
  })();
  addEventListener('resize', placeHandle); placeHandle();
  CM.on('canvasTouch', () => { if (!sheet.hidden) closeSheet(); });

  function openSheet() { renderActions(); sheet.hidden = false; handle.hidden = true; refreshTitle(); }
  function closeSheet() { sheet.hidden = true; handle.hidden = false; }
  CM.closeSheet = closeSheet;
  CM.showCanvas = () => {
    for (const id of ['#prompt', '#results', '#simple']) $(id).hidden = true;
    $('#library').classList.remove('open'); handle.hidden = false; store.set('startPage', 'canvas'); CM.stopResults?.();
  };
  let generating = false;
  const fullscreenSupported = !!(document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen);
  function actions() {
    return [
      ['开始生成', () => CM.generate(false), 'primary'], ['停止生成', CM.interrupt, CM.running ? '' : 'disabled'],
      ['简洁模式', () => CM.showSimple()], ['提示词', () => CM.showPrompt()], ['工作流', CM.workflowMenu],
      ['节点', nodeList], ['添加', addNode], ['适应画布', async () => CM.report(await CM.command('fit'))],
      ['撤销', async () => { CM.report(await CM.command('undo')); refreshTitle(); }, 'keep'], ['重做', async () => { CM.report(await CM.command('redo')); refreshTitle(); }, 'keep'],
      ['任务', () => CM.showResults('tasks')], ['图库', () => CM.showResults('gallery')], ['原版菜单', toggleMenus], ['更多', more],
    ];
  }
  function renderActions() {
    const grid = $('#actions'); grid.replaceChildren();
    for (const [label, run, flag] of actions()) {
      const b = CM.button(label, () => { if (flag !== 'keep') closeSheet(); run(); }, flag === 'primary');
      if (flag === 'primary') b.disabled = generating; if (flag === 'disabled') b.disabled = true;
      grid.append(b);
    }
  }
  CM.on('running', () => { if (!sheet.hidden) renderActions(); });
  async function refreshTitle() {
    const info = await CM.command('workflowInfo'); if (!info.ok) return;
    const text = `工作流：${info.name}${info.modified ? ' ●未保存' : ''}${info.subgraph ? ' › ' + info.subgraph : ''}`;
    $('#workflow-title').textContent = text; $('#workflow-title').hidden = false;
    $('#simple-title').textContent = text.replace('工作流：', '');
  }
  CM.refreshTitle = refreshTitle;
  CM.on('editorReady', refreshTitle); CM.on('completed', refreshTitle);

  CM.generate = async front => {
    if (generating) return; generating = true; CM.setStatus('正在提交'); $('#simple-generate').disabled = true;
    try {
      const r = await CM.command('generate', { batch: store.get('batch', 1), front: !!front });
      if (r.ok) { CM.toast('已提交给电脑；进度显示在顶部，结果在图库'); CM.setStatus('已发送生成请求'); }
      else { CM.report(r); CM.setStatus('提交未确认，请检查任务'); }
    } finally { generating = false; $('#simple-generate').disabled = false; }
  };
  async function toggleMenus() {
    const state = await CM.command('chrome'); if (!CM.report(state)) return;
    const r = await CM.command('chrome', { focus: !state.focus }); if (!CM.report(r)) return;
    store.set('webMenus', !r.focus); CM.toast(r.focus ? '已隐藏原版菜单，画布全屏' : '已显示原版菜单；可在这里再次隐藏');
  }
  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement || document.webkitFullscreenElement) await (document.exitFullscreen || document.webkitExitFullscreen).call(document);
      else await (document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen).call(document.documentElement);
    } catch { CM.toast('此浏览器不允许网页全屏；用 Safari 分享 → 添加到主屏幕 即可全屏使用'); }
  }
  CM.chooseBatch = async () => {
    const pick = await CM.choose('每次生成数量', [1, 2, 3, 4, 6, 8, 10].map(n => ({ label: `${n} 次`, n })), { search: false });
    if (pick) { store.set('batch', pick.n); $('#batch-count').textContent = `${pick.n} 次`; }
  };
  async function more() {
    const items = [
      { label: `每次生成数量：${store.get('batch', 1)}`, run: CM.chooseBatch },
      { label: '插队生成（排到最前）', run: () => CM.generate(true) },
      { label: '导入工作流 JSON', run: () => $('#import-file').click() },
      { label: '导出工作流 JSON', run: exportWorkflow },
      { label: `画布工具条：${store.get('toolbar', true) ? '显示' : '隐藏'}`, run: () => { store.set('toolbar', !store.get('toolbar', true)); CM.applySetup(); } },
      { label: `选中节点时的工具条：${store.get('selectionToolbox', false) ? '显示' : '隐藏（避免挡住双击）'}`, run: () => { store.set('selectionToolbox', !store.get('selectionToolbox', false)); CM.applySetup(); } },
      { label: `性能模式（缩小时简化绘制）：${store.get('performance', false) ? '开' : '关'}`, run: () => { store.set('performance', !store.get('performance', false)); CM.applySetup(); } },
      { label: '刷新编辑器', run: reloadEditor },
      { label: '下载安卓版安装包', run: () => { location.href = 'ComfyMobile.apk'; } },
    ];
    if (fullscreenSupported) items.push({ label: document.fullscreenElement || document.webkitFullscreenElement ? '退出全屏' : '全屏', run: toggleFullscreen });
    const pick = await CM.choose('更多', items, { search: false }); pick?.run();
  }
  async function reloadEditor() {
    if (await CM.ask('刷新会重新加载电脑端编辑器。请先保存尚未保存的工作流。', ['刷新']) !== 0) return;
    CM.resetTarget?.(); CM.reloadEditor();
  }

  // ---------- workflows stored on the computer ----------
  CM.workflowMenu = async () => {
    const info = await CM.command('workflowInfo'); if (!CM.report(info)) return;
    const items = [
      { label: info.temporary ? '保存到电脑…（新工作流需要命名）' : `保存${info.modified ? '（有未保存的修改）' : '（已是最新）'}`, run: () => saveWorkflow(false, false) },
      { label: '另存为…', run: () => saveWorkflow(true, false) },
      { label: '打开电脑上的工作流…', run: openList },
      { label: '新建空白工作流', run: async () => { CM.report(await CM.command('newWorkflow')); CM.resetTarget?.(); refreshTitle(); CM.reloadSimple?.(); } },
      ...info.open.filter(w => !w.active).map(w => ({ label: `切换到已打开：${w.name}${w.modified ? ' ●' : ''}`, run: () => openWorkflow(w.path) })),
    ];
    const pick = await CM.choose(`当前：${info.name}${info.modified ? ' ●未保存' : ''}`, items, { search: false }); pick?.run();
  };
  async function saveWorkflow(as, force) {
    const r = await CM.command('saveWorkflow', { as, force });
    if (r.conflict) {
      const choice = await CM.ask(r.error + '。', ['仍然覆盖', '另存为新文件'], '电脑上的版本更新');
      if (choice === 0) saveWorkflow(false, true); else if (choice === 1) saveWorkflow(true, true);
      return;
    }
    if (!CM.report(r)) return;
    if (r.prompted) { CM.showCanvas(); CM.toast('请在画布弹出的窗口中输入名称并确认'); } else CM.toast('已保存到电脑');
    setTimeout(refreshTitle, 1500);
  }
  async function openList() {
    const r = await CM.command('workflows'); if (!CM.report(r)) return;
    if (!r.workflows.length) { CM.toast('电脑上还没有保存的工作流'); return; }
    const pick = await CM.choose('打开电脑上的工作流', r.workflows.map(w => ({ label: w.name, sub: (w.open ? '已打开 · ' : '') + (w.modified ? '修改于 ' + new Date(w.modified).toLocaleString() : ''), path: w.path })));
    if (pick) openWorkflow(pick.path);
  }
  async function openWorkflow(path) {
    const r = await CM.command('openWorkflow', { path }); if (!CM.report(r)) return;
    CM.resetTarget?.(); refreshTitle(); CM.reloadSimple?.(); CM.toast('已打开');
  }

  // ---------- nodes ----------
  const modeLabel = m => m === 'bypass' ? '【已旁路】' : m === 'mute' ? '【已静音】' : '';
  async function nodeList() {
    const r = await CM.command('nodes'); if (!CM.report(r)) return;
    if (!r.nodes.length) { CM.toast('当前工作流没有节点'); return; }
    const node = await CM.choose('节点列表' + (r.graph ? `（子图：${r.graph}）` : ''), r.nodes.map(n => ({ label: `${modeLabel(n.mode)}#${n.id} · ${n.title}`, sub: n.type + (n.subgraph ? ' · 子图' : ''), node: n })));
    if (!node) return;
    const n = node.node, fields = r.fields.filter(f => String(f.target.nodeId) === String(n.id));
    const act = await CM.choose(`${n.title} · #${n.id}`, [
      { label: '编辑参数', run: () => editParams(fields) }, { label: '定位到画布', run: async () => CM.report(await CM.command('select', { nodeId: n.id })) },
      { label: n.mode === 'bypass' ? '取消旁路' : '旁路（跳过此节点）', run: async () => { const x = await CM.command('mode', { nodeId: n.id, mode: 'bypass' }); if (CM.report(x)) CM.toast(x.mode === 'bypass' ? '已旁路' : '已恢复'); } },
      { label: n.mode === 'mute' ? '取消静音' : '静音（不执行此节点）', run: async () => { const x = await CM.command('mode', { nodeId: n.id, mode: 'mute' }); if (CM.report(x)) CM.toast(x.mode === 'mute' ? '已静音' : '已恢复'); } },
      { label: '复制节点', run: async () => { if (CM.report(await CM.command('duplicate', { nodeId: n.id }))) CM.toast('已复制'); } },
      { label: '删除节点', run: async () => { if (await CM.ask('删除此节点及其连线？可用“撤销”恢复。', ['删除']) === 0 && CM.report(await CM.command('remove', { nodeId: n.id }))) CM.toast('已删除'); } },
    ], { search: false });
    act?.run();
  }
  async function editParams(fields) {
    if (!fields.length) { CM.toast('没有标准参数；插件专用控件请在画布操作'); return; }
    const pick = await CM.choose('修改节点参数', fields.map(f => ({ label: CM.roleLabel(f) + (f.label || f.name), sub: f.linked ? '已由连线提供，不能在这里改' : String(f.value).slice(0, 60), field: f })));
    if (pick) CM.editField(pick.field);
  }
  /** Edits one widget value with the control its type needs; text opens the prompt editor. */
  CM.editField = async (f, after) => {
    if (f.linked) { CM.toast('这个参数由连线提供，请修改上游节点或断开连线'); return; }
    let value;
    if (f.options) { const pick = await CM.choose(`${f.label || f.name}（当前：${f.value}）`, f.options.map(o => ({ label: String(o), value: o }))); if (!pick) return; value = pick.value; }
    else if (typeof f.value === 'boolean') { const pick = await CM.ask(null, ['开启', '关闭'], f.label || f.name); if (pick == null) return; value = pick === 0; }
    else if (typeof f.value === 'number') {
      const n = f.number || {}, integer = n.precision === 0;
      const hint = [n.min != null ? `最小 ${n.min}` : '', n.max != null ? `最大 ${n.max}` : '', integer ? '整数' : ''].filter(Boolean).join(' · ');
      const raw = await CM.promptText(`${f.label || f.name}${hint ? '（' + hint + '）' : ''}`, String(f.value), '', integer ? 'numeric' : 'decimal');
      if (raw == null) return; value = Number(raw.trim());
      if (!Number.isFinite(value)) { CM.toast('请输入有效数字'); return; }
      if (integer) value = Math.round(value);
    } else { CM.openEdit(f); return; }
    const r = await CM.command('widget', { target: f.target, value });
    if (CM.report(r)) { f.value = r.value; CM.toast('参数已更新'); refreshTitle(); after?.(); }
  };
  async function addNode() {
    const r = await CM.command('types'); if (!CM.report(r)) return;
    const chosen = await CM.choose(`添加电脑已安装的节点（共 ${r.types.length} 种）`, r.types.map(t => ({ label: t.title, sub: `${t.category} · ${t.type}`, type: t.type })));
    if (chosen && CM.report(await CM.command('add', { type: chosen.type }))) CM.toast('已添加到画布中央');
  }

  // ---------- import / export ----------
  function download(name, text) {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }
  async function exportWorkflow() { const r = await CM.command('export'); if (CM.report(r)) download('ComfyMobile-workflow.json', r.json); }
  $('#import-file').addEventListener('change', async event => {
    const file = event.target.files[0]; event.target.value = ''; if (!file) return;
    if (file.size > 20 * 1024 * 1024) { CM.toast('工作流 JSON 过大'); return; }
    let json; try { json = await file.text(); JSON.parse(json); } catch { CM.toast('不是有效的 JSON 文件'); return; }
    if (await CM.ask('导入将替换当前画布。导入前会先下载一份当前工作流备份。', ['备份并导入']) !== 0) return;
    const before = await CM.command('export'); if (!CM.report(before)) return;
    download('ComfyMobile-before-import.json', before.json);
    const r = await CM.command('import', { json }); CM.resetTarget?.();
    if (CM.report(r)) { CM.toast('工作流已导入'); refreshTitle(); }
  });
})();
