// ComfyMobile for iPad: wraps the original editor (same origin) with a touch shell.
// The embedded editor is driven only through editor-adapter.js, the same bridge the Android app uses.
// cm-core: shared helpers, dialogs, the bridge, and the event loop. Other cm-*.js files add the pages.
window.CM = (() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const frame = $('#editor');
  const store = {
    get(key, fallback) { try { const v = localStorage.getItem('comfymobile.' + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; } },
    set(key, value) { try { localStorage.setItem('comfymobile.' + key, JSON.stringify(value)); return true; } catch { return false; } },
  };
  const escapeHtml = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const roleLabel = f => ({ positive: '【正向】', negative: '【反向】', mixed: '【正反向都用】' }[f?.role] || '');
  const CM = { $, frame, store, escapeHtml, roleLabel, running: false, progressText: '', hooks: {} };
  const emit = (name, ...args) => { for (const fn of CM.hooks[name] || []) try { fn(...args); } catch (e) { console.error(e); } };
  CM.on = (name, fn) => (CM.hooks[name] ||= []).push(fn);

  // ---------- feedback ----------
  let toastTimer = 0;
  CM.toast = message => {
    const el = $('#toast'); el.textContent = message; el.classList.add('show');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
  };
  CM.setStatus = text => { $('#status').textContent = text; };
  let chipTimer = 0;
  CM.chip = (text, image) => {
    if (text != null) { $('#chip-text').textContent = text; CM.setStatus(text); }
    if (image) { $('#chip-thumb').src = image; $('#chip-thumb').hidden = false; }
    $('#chip').hidden = false;
    clearTimeout(chipTimer); chipTimer = setTimeout(() => { $('#chip').hidden = true; $('#chip-thumb').hidden = true; }, 8000);
  };

  // ---------- dialogs ----------
  const modal = $('#modal');
  let closeOpenDialog = null;
  CM.dialog = build => {
    closeOpenDialog?.(null);
    return new Promise(resolve => {
      const box = document.createElement('div'); box.className = 'dialog';
      const done = value => { if (closeOpenDialog !== done) return; closeOpenDialog = null; modal.hidden = true; modal.replaceChildren(); resolve(value); };
      closeOpenDialog = done;
      build(box, done);
      modal.replaceChildren(box); modal.hidden = false;
      modal.onclick = event => { if (event.target === modal) done(null); };
    });
  };
  const button = (label, onClick, primary) => { const b = document.createElement('button'); b.type = 'button'; b.textContent = label; if (primary) b.className = 'primary'; b.onclick = onClick; return b; };
  CM.button = button;
  CM.ask = (message, buttons, title) => CM.dialog((box, done) => {
    if (title) box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
    if (message) box.insertAdjacentHTML('beforeend', `<p>${escapeHtml(message)}</p>`);
    const row = document.createElement('div'); row.className = 'buttons';
    buttons.forEach((label, index) => row.append(button(label, () => done(index), index === 0)));
    row.append(button('取消', () => done(null))); box.append(row);
  });
  /** A searchable list; resolves to the chosen item or null. Renders lazily, so long lists stay fast. */
  CM.choose = (title, items, { search = items.length > 8 } = {}) => CM.dialog((box, done) => {
    box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
    let input = null;
    if (search) { input = document.createElement('input'); input.type = 'search'; input.placeholder = '搜索'; input.autocomplete = 'off'; box.append(input); }
    const list = document.createElement('div'); list.className = 'list'; box.append(list);
    let visible = [], shown = 0;
    const more = () => {
      const end = Math.min(visible.length, shown + 80);
      for (; shown < end; shown++) {
        const item = visible[shown];
        const b = document.createElement('button'); b.type = 'button';
        b.innerHTML = escapeHtml(item.label) + (item.sub ? `<br><span class="muted small">${escapeHtml(item.sub)}</span>` : '');
        b.onclick = () => done(item); list.append(b);
      }
    };
    const render = () => {
      const q = (input?.value || '').trim().toLowerCase(); list.replaceChildren(); shown = 0;
      visible = items.filter(item => !q || `${item.label} ${item.sub || ''}`.toLowerCase().includes(q));
      if (!visible.length) list.insertAdjacentHTML('beforeend', '<p class="muted">没有匹配项</p>'); else more();
    };
    list.addEventListener('scroll', () => { if (list.scrollTop + list.clientHeight > list.scrollHeight - 200) more(); });
    input?.addEventListener('input', render); render();
    const row = document.createElement('div'); row.className = 'buttons'; row.append(button('关闭', () => done(null))); box.append(row);
  });
  CM.promptText = (title, value = '', placeholder = '', type = 'textarea') => CM.dialog((box, done) => {
    box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
    const area = document.createElement(type === 'textarea' ? 'textarea' : 'input');
    if (type !== 'textarea') { area.type = 'text'; area.inputMode = type; } else area.rows = 4;
    area.value = value; area.placeholder = placeholder; box.append(area);
    const row = document.createElement('div'); row.className = 'buttons';
    row.append(button('确定', () => done(area.value), true), button('取消', () => done(null))); box.append(row);
    setTimeout(() => { area.focus(); area.select?.(); }, 50);
  });

  // ---------- editor bridge ----------
  let adapterSource = null, bridge = null, frameLoads = 0, injectedLoad = -1, setupLoad = -1, lastTouch = 0;
  const adapterReady = fetch('editor-adapter.js', { cache: 'no-cache' }).then(r => r.text()).then(t => { adapterSource = t; });
  frame.addEventListener('load', () => { frameLoads++; bridge = null; CM.setStatus('正在加载编辑器'); });
  function editorWindow() { try { return frame.contentWindow; } catch { return null; } }
  function attachBridge() {
    const w = editorWindow();
    if (!w || !adapterSource || !w.comfyAPI?.app?.app?.graph) return null;
    if (!w.ComfyMobileEditor && injectedLoad !== frameLoads) {
      injectedLoad = frameLoads;
      const script = w.document.createElement('script'); script.textContent = adapterSource; w.document.head.append(script);
      w.document.addEventListener('pointerdown', () => { lastTouch = Date.now(); emit('canvasTouch'); }, true);
    }
    if (w.ComfyMobileEditor && bridge !== w.ComfyMobileEditor) { bridge = w.ComfyMobileEditor; CM.setStatus('编辑器已加载 · 双击提示词节点进入编辑'); }
    return bridge;
  }
  CM.call = async (method, ...args) => {
    const b = attachBridge(); if (!b) return { ok: false, error: '编辑器尚未就绪，请稍候' };
    let timer;
    const timeout = new Promise(resolve => { timer = setTimeout(() => resolve({ ok: false, error: '操作超时；提交结果可能已被电脑接收，请先检查任务，勿直接重复生成' }), 45000); });
    try { return await Promise.race([b[method](...args), timeout]); } catch (e) { return { ok: false, error: String(e?.message || e) }; } finally { clearTimeout(timer); }
  };
  CM.command = (name, args = {}) => CM.call('command', name, args);
  CM.report = result => { if (!result?.ok) CM.toast(result?.error || '操作失败'); return !!result?.ok; };
  CM.ready = () => !!attachBridge();
  CM.reloadEditor = () => { setupLoad = -1; frame.contentWindow.location.reload(); };
  CM.applySetup = () => { setupLoad = -1; };
  async function setup() {
    if (!bridge || setupLoad === frameLoads) return;
    const load = frameLoads;
    const config = await CM.command('configure', { hideInfo: true, closeAgent: true, toolbar: store.get('toolbar', true), selectionToolbox: store.get('selectionToolbox', false), agentButton: store.get('agentButton', false), performance: store.get('performance', false) });
    if (!config.ok) return;
    const r = await CM.command('chrome', { focus: !store.get('webMenus', false) });
    if (r.ok) { setupLoad = load; emit('editorReady'); }
  }

  // ---------- reachability: show a reconnect card while the computer cannot be reached ----------
  let failures = 0;
  async function checkServer() {
    let ok = false;
    try { ok = (await fetch('/api/system_stats', { cache: 'no-store' })).ok; } catch { ok = false; }
    // After a short outage the open editor usually reconnects by itself; reloading would drop its undo history.
    if (ok) { if (failures >= 2) { $('#offline').hidden = true; if (!editorWindow()?.comfyAPI?.app?.app?.graph) CM.reloadEditor(); } failures = 0; }
    else if (++failures >= 2) $('#offline').hidden = false;
  }
  setInterval(checkServer, 5000);
  CM.retry = () => { failures = 0; $('#offline').hidden = true; CM.reloadEditor(); };

  // ---------- event loop: fast right after a touch (double taps) or while generating, slow when idle ----------
  let polling = false, previewSeq = 0, previewAt = 0;
  async function poll() {
    if (polling) return; polling = true;
    try {
      await adapterReady.catch(() => {});
      if (!attachBridge()) return;
      await setup();
      if (!$('#prompt').hidden) return;
      const r = await CM.call('drainEvents'); if (!r.ok) return;
      const state = r.state || {};
      const busy = state.running != null || state.queueRemaining > 0;
      emit('state', state, busy);
      // While the AI assistant's chat is open, the handle and progress chip step aside so they do not cover it.
      document.body.classList.toggle('agent-open', !!state.agentOpen);
      if (agentWasOpen && !state.agentOpen && agentReturnsToSimple) { agentReturnsToSimple = false; CM.showSimple?.(); }
      agentWasOpen = !!state.agentOpen;
      if (busy !== CM.running) { CM.running = busy; emit('running', busy); }
      if (state.progress?.max > 0) {
        CM.progressText = (state.executingTitle ? state.executingTitle + ' · ' : '') + `采样 ${state.progress.value} / ${state.progress.max}` + (state.queueRemaining > 1 ? ` · 排队 ${state.queueRemaining - 1}` : '');
        CM.chip(CM.progressText); emit('progress', CM.progressText);
      }
      if (state.previewSeq !== previewSeq && Date.now() - previewAt > 900) {
        previewAt = Date.now();
        const p = await CM.call('preview', previewSeq);
        if (p.ok && p.dataUrl) { previewSeq = p.seq; CM.chip(null, p.dataUrl); emit('preview', p.dataUrl); } else if (p.ok) previewSeq = p.seq;
      }
      let edit = null, pick = null, done = false;
      for (const event of r.events) {
        const data = event.data;
        if (event.type === 'execution_success') { CM.chip('生成完成 · 可在图库查看'); done = true; }
        if (event.type === 'executed') done = true;
        if (event.type === 'execution_interrupted') CM.chip('已停止生成');
        if (event.type === 'execution_error') { CM.chip('执行失败' + (data?.node_type ? '：' + data.node_type : '')); CM.toast(`节点执行失败${data?.node_type ? '（' + data.node_type + '）' : ''}：${data?.exception_message || '请查看任务'}`); }
        if (event.type === 'edit_field' && data) { edit = data; pick = null; }
        if (event.type === 'pick_field' && data) { pick = data; edit = null; }
      }
      if (done) emit('completed');
      if (edit) emit('edit', edit);
      if (pick) {
        const chosen = await CM.choose(`${pick.nodeTitle} · 选择要编辑的字段`, pick.fields.map(f => ({ label: roleLabel(f) + f.name, sub: String(f.value).slice(0, 60), field: f })));
        if (chosen) emit('edit', chosen.field);
      }
    } finally { polling = false; }
  }
  (function loop() { poll().finally(() => setTimeout(loop, Date.now() - lastTouch < 2500 ? 150 : CM.running ? 500 : 1000)); })();

  CM.api = async (path, init) => {
    const response = await fetch('/api' + path, init);
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const text = await response.text(); return text ? JSON.parse(text) : {};
  };
  CM.postJson = (path, body) => CM.api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  /** Opens the ComfyUI-AgentLink chat inside the editor; it lives on the canvas, so other pages give way first. */
  let agentReturnsToSimple = false, agentWasOpen = false;
  CM.openAgent = async () => {
    // Opened from simple mode: closing the chat returns there.
    agentReturnsToSimple = !$('#simple').hidden;
    CM.showCanvas?.();
    const r = await CM.command('agent', { action: 'open' });
    if (CM.report(r)) document.body.classList.add('agent-open');
  };
  /** Puts a generated result into a LoadImage node so the next run edits it; outputs are referenced as "name [output]". */
  CM.useAsInput = async output => {
    if (!output) return;
    const type = output.type || 'output', value = `${output.subfolder ? output.subfolder + '/' : ''}${output.filename}${type === 'input' ? '' : ` [${type}]`}`;
    const r = await CM.command('allFields'); if (!CM.report(r)) return;
    const loaders = r.fields.filter(f => f.name === 'image' && /LoadImage/.test(f.nodeType) && f.options);
    if (!loaders.length) { CM.toast('当前工作流没有“加载图像”节点'); return; }
    const pick = loaders.length === 1 ? { field: loaders[0] } : await CM.choose('设为哪个节点的输入图？', loaders.map(f => ({ label: `${f.nodeTitle} · #${f.target.nodeId}`, sub: '当前：' + f.value, field: f })));
    if (!pick) return;
    const w = await CM.command('widget', { target: pick.field.target, value });
    if (CM.report(w)) { CM.toast(`已设为“${pick.field.nodeTitle}”的输入图`); CM.refreshTitle?.(); CM.reloadSimple?.(); }
  };
  CM.interrupt = async () => { try { await CM.postJson('/interrupt', {}); CM.toast('已请求电脑停止当前生成'); } catch (e) { CM.toast('停止失败：' + e.message); } };
  CM.mediaUrl = (o, preview) => `/api/view?${new URLSearchParams({ filename: o.filename, subfolder: o.subfolder || '', type: o.type || 'output', ...(preview ? { preview: 'webp' } : {}) })}`;
  CM.isVideo = name => /\.(mp4|m4v|webm|mov)$/i.test(name || '');
  return CM;
})();
