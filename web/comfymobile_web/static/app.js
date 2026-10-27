// ComfyMobile for iPad: wraps the original editor (same origin) with a touch shell.
// The embedded editor is driven only through editor-adapter.js, the same bridge the Android app uses.
(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const frame = $('#editor');
  const store = {
    get(key, fallback) { try { const v = localStorage.getItem('comfymobile.' + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; } },
    set(key, value) { try { localStorage.setItem('comfymobile.' + key, JSON.stringify(value)); return true; } catch { return false; } },
  };
  const escapeHtml = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---------- feedback ----------
  let toastTimer = 0;
  function toast(message) {
    const el = $('#toast'); el.textContent = message; el.classList.add('show');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
  }
  function setStatus(text) { $('#status').textContent = text; }
  let chipTimer = 0;
  function chip(text) {
    const el = $('#chip'); el.textContent = text; el.hidden = false; setStatus(text);
    clearTimeout(chipTimer); chipTimer = setTimeout(() => { el.hidden = true; }, 5000);
  }

  // ---------- generic dialogs ----------
  const modal = $('#modal');
  let closeOpenDialog = null;
  function dialog(build) {
    closeOpenDialog?.(null);
    return new Promise(resolve => {
      const box = document.createElement('div'); box.className = 'dialog';
      const done = value => { if (closeOpenDialog !== done) return; closeOpenDialog = null; modal.hidden = true; modal.replaceChildren(); resolve(value); };
      closeOpenDialog = done;
      build(box, done);
      modal.replaceChildren(box); modal.hidden = false;
      modal.onclick = event => { if (event.target === modal) done(null); };
    });
  }
  function ask(message, buttons, title) {
    return dialog((box, done) => {
      if (title) box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
      box.insertAdjacentHTML('beforeend', `<p>${escapeHtml(message)}</p>`);
      const row = document.createElement('div'); row.className = 'buttons';
      buttons.forEach((label, index) => {
        const b = document.createElement('button'); b.type = 'button'; b.textContent = label;
        if (index === 0) b.className = 'primary'; b.onclick = () => done(index); row.append(b);
      });
      const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = '取消'; cancel.onclick = () => done(null); row.append(cancel);
      box.append(row);
    });
  }
  /** A searchable list; resolves to the chosen item or null. */
  function choose(title, items, { search = false, limit = 120 } = {}) {
    return dialog((box, done) => {
      box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
      let input = null;
      if (search) { input = document.createElement('input'); input.type = 'search'; input.placeholder = '搜索'; input.autocomplete = 'off'; box.append(input); }
      const list = document.createElement('div'); list.className = 'list'; box.append(list);
      const render = () => {
        const q = (input?.value || '').trim().toLowerCase(); list.replaceChildren(); let shown = 0;
        for (const item of items) {
          if (q && !(item.label + ' ' + (item.sub || '')).toLowerCase().includes(q)) continue;
          if (shown++ >= limit) { list.insertAdjacentHTML('beforeend', '<p class="muted small">输入关键词缩小范围</p>'); break; }
          const b = document.createElement('button'); b.type = 'button';
          b.innerHTML = escapeHtml(item.label) + (item.sub ? `<br><span class="muted small">${escapeHtml(item.sub)}</span>` : '');
          b.onclick = () => done(item); list.append(b);
        }
        if (!shown) list.insertAdjacentHTML('beforeend', '<p class="muted">没有匹配项</p>');
      };
      input?.addEventListener('input', render); render();
      const row = document.createElement('div'); row.className = 'buttons';
      const close = document.createElement('button'); close.type = 'button'; close.textContent = '关闭'; close.onclick = () => done(null); row.append(close); box.append(row);
    });
  }
  function promptText(title, value = '', placeholder = '') {
    return dialog((box, done) => {
      box.insertAdjacentHTML('beforeend', `<h2>${escapeHtml(title)}</h2>`);
      const area = document.createElement('textarea'); area.rows = 4; area.value = value; area.placeholder = placeholder; box.append(area);
      const row = document.createElement('div'); row.className = 'buttons';
      const save = document.createElement('button'); save.type = 'button'; save.className = 'primary'; save.textContent = '保存'; save.onclick = () => done(area.value);
      const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = '取消'; cancel.onclick = () => done(null);
      row.append(save, cancel); box.append(row); setTimeout(() => area.focus(), 50);
    });
  }

  // ---------- editor bridge ----------
  let adapterSource = null, bridge = null, frameLoads = 0, appliedLoad = -1, injectedLoad = -1;
  const adapterReady = fetch('editor-adapter.js', { cache: 'no-cache' }).then(r => r.text()).then(t => { adapterSource = t; });
  frame.addEventListener('load', () => { frameLoads++; bridge = null; setStatus('正在加载编辑器'); });
  function editorWindow() { try { return frame.contentWindow; } catch { return null; } }
  function attachBridge() {
    const w = editorWindow();
    if (!w || !adapterSource || !w.comfyAPI?.app?.app?.graph) return null;
    if (!w.ComfyMobileEditor && injectedLoad !== frameLoads) {
      injectedLoad = frameLoads;
      const script = w.document.createElement('script'); script.textContent = adapterSource; w.document.head.append(script);
    }
    if (w.ComfyMobileEditor && bridge !== w.ComfyMobileEditor) { bridge = w.ComfyMobileEditor; setStatus('编辑器已加载 · 双击提示词节点进入编辑'); }
    return bridge;
  }
  async function call(method, ...args) {
    const b = attachBridge(); if (!b) return { ok: false, error: '编辑器尚未就绪，请稍候' };
    let timer;
    const timeout = new Promise(resolve => { timer = setTimeout(() => resolve({ ok: false, error: '操作超时；提交结果可能已被电脑接收，请先检查任务，勿直接重复生成' }), 45000); });
    try { return await Promise.race([b[method](...args), timeout]); } catch (e) { return { ok: false, error: String(e?.message || e) }; } finally { clearTimeout(timer); }
  }
  const command = (name, args = {}) => call('command', name, args);
  function report(result) { if (!result?.ok) toast(result?.error || '操作失败'); return !!result?.ok; }

  async function applyMenus() {
    if (!bridge || appliedLoad === frameLoads) return;
    const load = frameLoads; const r = await command('chrome', { focus: !store.get('webMenus', false) });
    if (r.ok) appliedLoad = load;
  }

  // ---------- event loop ----------
  let polling = false;
  async function poll() {
    if (polling) return; polling = true;
    try {
      await adapterReady.catch(() => {});
      if (!attachBridge()) return;
      await applyMenus();
      if (!$('#prompt').hidden) return;
      const r = await call('drainEvents'); if (!r.ok) return;
      let edit = null, pick = null, done = false;
      for (const event of r.events) {
        const data = event.data;
        if (event.type === 'progress' && data) chip(`采样进度 ${data.value} / ${data.max}`);
        if (event.type === 'execution_success') { chip('生成完成 · 可在图库查看'); done = true; }
        if (event.type === 'executed') done = true;
        if (event.type === 'execution_error') chip('节点执行失败，请查看任务');
        if (event.type === 'edit_field' && data) { edit = data; pick = null; }
        if (event.type === 'pick_field' && data) { pick = data; edit = null; }
      }
      if (done && !$('#results').hidden) refreshResults(true);
      if (edit) openEdit(edit);
      if (pick) {
        const chosen = await choose(`${pick.nodeTitle} · 选择要编辑的字段`, pick.fields.map(f => ({ label: f.name, sub: String(f.value).slice(0, 60), field: f })));
        if (chosen) openEdit(chosen.field);
      }
    } finally { polling = false; }
  }
  setInterval(poll, 180);

  // ---------- floating handle and menu ----------
  const handle = $('#handle'), sheet = $('#sheet');
  function placeHandle() {
    const m = 12, size = handle.offsetWidth || 56;
    const right = store.get('menuRight', true), ratio = Math.min(1, Math.max(0, store.get('menuY', 0.82)));
    const safe = getComputedStyle(document.documentElement);
    const top = parseFloat(safe.getPropertyValue('--safe-top')) || 0, bottom = parseFloat(safe.getPropertyValue('--safe-bottom')) || 0;
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
    const end = e => {
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

  function openSheet() { renderActions(); sheet.hidden = false; handle.hidden = true; }
  function closeSheet() { sheet.hidden = true; handle.hidden = false; }
  let generating = false;
  const fullscreenSupported = !!(document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen);
  function actions() {
    const list = [
      ['开始生成', generate, true], ['提示词', () => showPrompt()], ['节点', nodeList], ['添加', addNode],
      ['适应画布', async () => report(await command('fit'))], ['撤销', async () => report(await command('undo'))],
      ['任务', () => showResults('tasks')], ['图库', () => showResults('gallery')], ['原版菜单', toggleMenus],
      ['导入', () => $('#import-file').click()], ['导出', exportWorkflow], ['刷新编辑器', reloadEditor],
    ];
    if (fullscreenSupported) list.push([document.fullscreenElement || document.webkitFullscreenElement ? '退出全屏' : '全屏', toggleFullscreen]);
    return list;
  }
  function renderActions() {
    const grid = $('#actions'); grid.replaceChildren();
    for (const [label, run, primary] of actions()) {
      const b = document.createElement('button'); b.type = 'button'; b.textContent = label;
      if (primary) { b.className = 'primary'; b.disabled = generating; }
      b.onclick = () => { closeSheet(); run(); }; grid.append(b);
    }
  }

  async function generate() {
    if (generating) return; generating = true; setStatus('正在提交');
    try {
      const r = await command('generate');
      if (r.ok) { toast('已发送生成请求，可在任务查看'); setStatus('已发送生成请求'); }
      else { report(r); setStatus('提交未确认，请检查任务'); }
    } finally { generating = false; }
  }
  async function toggleMenus() {
    const state = await command('chrome'); if (!report(state)) return;
    const r = await command('chrome', { focus: !state.focus }); if (!report(r)) return;
    store.set('webMenus', !r.focus); toast(r.focus ? '已隐藏原版菜单，画布全屏' : '已显示原版菜单；可在这里再次隐藏');
  }
  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement || document.webkitFullscreenElement) await (document.exitFullscreen || document.webkitExitFullscreen).call(document);
      else await (document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen).call(document.documentElement);
    } catch { toast('此浏览器不允许网页全屏；用 Safari 分享 → 添加到主屏幕 即可全屏使用'); }
  }
  async function reloadEditor() {
    if (await ask('刷新会重新加载电脑端编辑器。请先保存或导出尚未保存的工作流。', ['刷新']) !== 0) return;
    prompt.target = null; renderTarget(); frame.contentWindow.location.reload();
  }
  async function nodeList() {
    const r = await command('nodes'); if (!report(r)) return;
    if (!r.nodes.length) { toast('当前工作流没有节点'); return; }
    const node = await choose('节点列表', r.nodes.map(n => ({ label: `#${n.id} · ${n.title}`, sub: n.type, node: n })), { search: true });
    if (!node) return;
    const n = node.node;
    const fields = r.fields.filter(f => String(f.target.nodeId) === String(n.id) && typeof f.value === 'string' && !f.options);
    const options = ['定位到画布', '复制节点', '删除节点']; if (fields.length) options.unshift('编辑文字字段');
    const act = await choose(`${n.title} · #${n.id}`, options.map(label => ({ label })));
    if (!act) return;
    if (act.label === '编辑文字字段') {
      const f = fields.length === 1 ? { field: fields[0] } : await choose('选择字段', fields.map(field => ({ label: field.name, sub: field.value.slice(0, 60), field })));
      if (f) openEdit(f.field);
    } else if (act.label === '定位到画布') report(await command('select', { nodeId: n.id }));
    else if (act.label === '复制节点') { if (report(await command('duplicate', { nodeId: n.id }))) toast('已复制'); }
    else if (await ask('删除此节点及其连线？', ['删除']) === 0) { if (report(await command('remove', { nodeId: n.id }))) toast('已删除'); }
  }
  async function addNode() {
    const r = await command('types'); if (!report(r)) return;
    const chosen = await choose('添加电脑已安装的节点', r.types.map(type => ({ label: type })), { search: true, limit: 80 });
    if (chosen && report(await command('add', { type: chosen.label }))) toast('已添加节点');
  }
  function download(name, text) {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }
  async function exportWorkflow() { const r = await command('export'); if (report(r)) download('ComfyMobile-workflow.json', r.json); }
  $('#import-file').addEventListener('change', async event => {
    const file = event.target.files[0]; event.target.value = ''; if (!file) return;
    if (file.size > 20 * 1024 * 1024) { toast('工作流 JSON 过大'); return; }
    let json; try { json = await file.text(); JSON.parse(json); } catch { toast('不是有效的 JSON 文件'); return; }
    if (await ask('导入将替换当前画布。导入前会先下载一份当前工作流备份。', ['备份并导入']) !== 0) return;
    const before = await command('export'); if (!report(before)) return;
    download('ComfyMobile-before-import.json', before.json);
    const r = await command('import', { json }); prompt.target = null; renderTarget();
    if (report(r)) toast('工作流已导入');
  });

  // ---------- prompt editor ----------
  const text = $('#text');
  const prompt = { target: null, label: '', saved: store.get('draftSaved', null), undo: [], redo: [], last: '' };
  text.value = store.get('draft', ''); prompt.last = text.value;
  if (text.value) $('#field-label').textContent = '手机草稿 · 上次：' + store.get('draftLabel', '未绑定字段');
  const dirty = () => prompt.saved == null ? text.value.trim() !== '' : text.value !== prompt.saved;
  function persist() { store.set('draft', text.value); store.set('draftLabel', prompt.label || store.get('draftLabel', '')); store.set('draftSaved', prompt.saved); }
  function renderTarget() {
    $('#field-label').textContent = prompt.target ? prompt.label : (text.value ? '草稿未绑定 · 点此选择节点字段' : '选择节点文字字段');
  }
  function replaceText(value, start, end) {
    text.value = value; prompt.last = value; persist();
    if (start != null) text.setSelectionRange(start, end ?? start);
  }
  function remember() { prompt.undo.push(prompt.last); if (prompt.undo.length > 80) prompt.undo.shift(); prompt.redo = []; }
  text.addEventListener('input', () => { if (text.value !== prompt.last) { remember(); prompt.last = text.value; persist(); } });

  function showPrompt() { closeSheet(); $('#results').hidden = true; $('#prompt').hidden = false; renderTarget(); renderLibrary(); }
  function backToCanvas() {
    persist(); text.blur(); $('#prompt').hidden = true; $('#results').hidden = true; $('#library').classList.remove('open');
    clearInterval(resultsTimer); handle.hidden = false;
  }
  async function openEdit(field) {
    const fresh = await call('readField', field.target); if (!report(fresh)) return;
    const value = fresh.value, label = `${field.nodeTitle} · #${field.target.nodeId} / ${field.name}`;
    const sameTarget = prompt.target && prompt.target.token === field.target.token && prompt.target.session === field.target.session;
    showPrompt();
    if (sameTarget && dirty()) { text.focus(); return; }
    const bind = () => { prompt.target = field.target; prompt.label = label; prompt.saved = value; persist(); renderTarget(); text.focus(); };
    if (dirty() && text.value !== value) {
      const choice = await ask('保留手机草稿并绑定此字段，还是载入节点当前文字？', ['保留草稿并绑定', '载入节点文字']);
      if (choice === 0) bind();
      else if (choice === 1) { remember(); replaceText(value, value.length); bind(); }
    } else { if (text.value !== value) { remember(); replaceText(value, value.length); } bind(); }
  }
  async function chooseField() {
    const r = await call('fields'); if (!report(r)) return;
    if (!r.fields.length) { toast('当前工作流没有可识别的标准文字字段'); return; }
    const chosen = await choose('选择实际文字字段', r.fields.map(f => ({ label: `${f.nodeTitle} · #${f.target.nodeId} / ${f.name}`, sub: f.value.slice(0, 60), field: f })), { search: true });
    if (chosen) openEdit(chosen.field);
  }
  async function save() {
    persist();
    if (!prompt.target) { toast('请先选择工作流中的目标字段，草稿已保存在本机'); return; }
    const value = text.value, r = await call('writeField', prompt.target, value);
    if (!report(r)) return;
    prompt.saved = value; persist(); rememberItem('history', value); toast('已写回节点；工作流可在编辑器中保存或导出');
  }
  // Same rules as the Android PromptText helper: separators only where needed, weights as (phrase:1.1).
  function insertSnippet(snippet, replaceAll) {
    remember();
    if (replaceAll) { replaceText(snippet, snippet.length); return; }
    const value = text.value, start = Math.min(text.selectionStart, text.selectionEnd), end = Math.max(text.selectionStart, text.selectionEnd);
    const left = value.slice(0, start), right = value.slice(end);
    let before = '', after = '';
    if (start === end) {
      if (left && !/\s$/.test(left) && !',;(['.includes(left.at(-1))) before = ', ';
      if (right && !/^\s/.test(right) && !',;)]'.includes(right[0])) after = ', ';
    }
    const caret = start + before.length + snippet.length;
    replaceText(left + before + snippet + after + right, caret);
  }
  function weight(delta) {
    const start = Math.min(text.selectionStart, text.selectionEnd), end = Math.max(text.selectionStart, text.selectionEnd);
    if (start === end) { toast('先选择需要调整权重的词句'); return; }
    let phrase = text.value.slice(start, end), previous = 1;
    const m = /^\((.+):([0-9]+(?:\.[0-9]+)?)\)$/s.exec(phrase); if (m) { phrase = m[1]; previous = parseFloat(m[2]); }
    const next = `(${phrase}:${Math.max(0.1, previous + delta).toFixed(1)})`;
    remember(); replaceText(text.value.slice(0, start) + next + text.value.slice(end), start, start + next.length);
  }
  function history(forward) {
    const from = forward ? prompt.redo : prompt.undo, to = forward ? prompt.undo : prompt.redo;
    if (!from.length) return; to.push(text.value); replaceText(from.pop());
  }

  // ---------- library ----------
  let builtin = { terms: [], templates: [] }, libraryMode = 'favorites';
  fetch('prompt-library.json', { cache: 'no-cache' }).then(r => r.json()).then(data => {
    builtin.terms = data.categories.flatMap(c => c.items.map(i => ({ label: i.label, text: i.text, translation: i.translation || i.label, category: c.label, note: i.note || '', keywords: i.keywords || '' })));
    builtin.templates = data.templates.map(t => ({ label: t.label, text: t.text, translation: t.translation || t.label, category: '内置模板', note: t.note || '', negative: t.negative || '', keywords: t.category || '' }));
    renderLibrary(true);
  }).catch(() => toast('内置词库读取失败，仍可使用自己的收藏'));
  function rememberItem(key, value) { const old = store.get(key, []); store.set(key, [value, ...old.filter(v => v !== value)].slice(0, 60)); }
  function savedEntries(key) {
    return store.get(key, []).map(item => { const i = item.indexOf(' / '); return { label: i >= 0 ? item.slice(0, i) : item, text: i >= 0 ? item.slice(i + 3) : item, translation: '', category: '我的收藏', note: '', keywords: '', saved: item }; });
  }
  function libraryEntries() {
    if (libraryMode === 'history') return store.get('history', []).map(v => ({ label: v.slice(0, 40), text: v, translation: '', category: '历史', note: '', keywords: '', saved: v }));
    if (libraryMode === 'templates') return [...savedEntries('templates'), ...builtin.templates];
    return [...savedEntries('favorites'), ...builtin.terms];
  }
  const titles = { favorites: '英文词库', templates: '英文模板', history: '历史' };
  function renderLibrary(resetCategory) {
    const entries = libraryEntries(), select = $('#library-category');
    $('#library-title').textContent = titles[libraryMode];
    const categories = ['全部分类', ...new Set(entries.map(e => e.category))];
    if (resetCategory || select.dataset.mode !== libraryMode || select.options.length !== categories.length) {
      const keep = select.value; select.replaceChildren(...categories.map(c => new Option(c, c)));
      select.value = categories.includes(keep) && select.dataset.mode === libraryMode ? keep : '全部分类'; select.dataset.mode = libraryMode;
    }
    const q = $('#library-search').value.trim().toLowerCase(), group = select.value;
    const visible = entries.filter(e => (group === '全部分类' || e.category === group) && (!q || `${e.label} ${e.text} ${e.translation} ${e.category} ${e.keywords}`.toLowerCase().includes(q)));
    const list = $('#library-list'); list.replaceChildren();
    for (const entry of visible.slice(0, 300)) {
      const row = document.createElement('div'); row.className = 'entry-row';
      const b = document.createElement('button'); b.type = 'button'; b.className = 'entry';
      const tr = entry.translation && entry.translation !== entry.label ? `<span class="tr">${escapeHtml(entry.translation)}</span>` : '';
      b.innerHTML = `<span class="zh">${escapeHtml(entry.category)} / ${escapeHtml(entry.label)}</span>${tr}<span class="en">${escapeHtml(entry.text)}</span>${entry.note ? `<span class="note">${escapeHtml(entry.note)}</span>` : ''}`;
      b.onclick = async () => {
        const all = libraryMode !== 'favorites';
        if (all && dirty() && await ask('用此英文内容替换当前草稿？', ['替换']) !== 0) return;
        insertSnippet(entry.text, all); if (innerWidth < 900) $('#library').classList.remove('open');
      };
      const star = document.createElement('button'); star.type = 'button'; star.className = 'star';
      if (entry.saved != null) {
        star.textContent = '✎'; star.setAttribute('aria-label', '编辑或删除');
        star.onclick = () => editSaved(entry.saved);
      } else {
        star.textContent = '☆'; star.setAttribute('aria-label', '加入我的收藏');
        star.onclick = () => { rememberItem(libraryMode === 'templates' ? 'templates' : 'favorites', `${entry.label} / ${entry.text}`); toast('已收藏'); renderLibrary(); };
      }
      row.append(b, star); list.append(row);
    }
    $('#library-count').textContent = `${visible.length} 项 · 点选插入英文，中文仅作说明`;
  }
  async function editSaved(original) {
    const key = libraryMode === 'history' ? 'history' : libraryMode === 'templates' ? 'templates' : 'favorites';
    const choice = await ask(original, ['编辑', '删除'], '我的收藏');
    // Always re-read the latest list and locate the item by content, so entries added elsewhere are never lost.
    const latest = store.get(key, []), index = latest.indexOf(original);
    if (choice == null) return;
    if (index < 0) { toast('该条目已被修改，请重新打开词库'); return; }
    if (choice === 1) { latest.splice(index, 1); store.set(key, latest); renderLibrary(); return; }
    const value = await promptText('编辑收藏（分类 / 内容）', original); if (value == null || !value.trim()) return;
    const now = store.get(key, []), at = now.indexOf(original); if (at < 0) { toast('该条目已被修改，请重新打开词库'); return; }
    now[at] = value.trim(); store.set(key, now); renderLibrary();
  }
  function openLibrary(mode) { libraryMode = mode; $('#library-search').value = ''; renderLibrary(true); $('#library').classList.add('open'); }
  $('#library-search').addEventListener('input', () => renderLibrary());
  $('#library-category').addEventListener('change', () => renderLibrary());

  // ---------- tasks and gallery ----------
  let resultsTab = 'tasks', resultsTimer = 0, lastHistory = '', viewingTask = false;
  function showResults(tab) {
    closeSheet(); resultsTab = tab; viewingTask = false; lastHistory = '';
    $('#prompt').hidden = true; $('#results').hidden = false; handle.hidden = true;
    document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
    refreshResults(true); clearInterval(resultsTimer); resultsTimer = setInterval(() => refreshResults(false), 4000);
  }
  const mediaUrl = (image, preview) => `/api/view?${new URLSearchParams({ filename: image.filename, subfolder: image.subfolder || '', type: image.type || 'output', ...(preview ? { preview: 'webp' } : {}) })}`;
  function imagesOf(item) { return Object.values(item.outputs || {}).flatMap(o => (o.images || []).filter(i => i && i.filename)); }
  function imageCard(image) {
    const card = document.createElement('div'); card.className = 'card';
    card.innerHTML = `<img loading="lazy" alt="${escapeHtml(image.filename)}" src="${mediaUrl(image, true)}"><span class="name">${escapeHtml(image.filename)}</span><div class="row"><button type="button" data-view>查看原图</button><a class="button" download="${escapeHtml(image.filename)}" href="${mediaUrl(image, false)}">下载</a></div>`;
    card.querySelector('[data-view]').onclick = () => { $('#viewer-image').src = mediaUrl(image, false); $('#viewer-open').href = mediaUrl(image, false); $('#viewer').hidden = false; };
    return card;
  }
  async function refreshResults(force) {
    if ($('#results').hidden || viewingTask) return;
    try {
      const [queue, historyData] = await Promise.all([fetch('/api/queue').then(r => r.json()), fetch('/api/history?max_items=30').then(r => r.json())]);
      const running = queue.queue_running || [], pending = queue.queue_pending || [];
      $('#results-summary').textContent = `运行中 ${running.length} · 等待 ${pending.length} · 生成记录 ${Object.keys(historyData).length}`;
      const signature = resultsTab + JSON.stringify(historyData).length + Object.keys(historyData).join() + running.length;
      if (!force && signature === lastHistory) return; lastHistory = signature;
      const list = $('#results-list'); list.replaceChildren();
      const ids = Object.keys(historyData).reverse();
      if (resultsTab === 'tasks') {
        for (const r of running) list.insertAdjacentHTML('beforeend', `<div class="card ok">正在执行 · ${escapeHtml(String(r[1]))}</div>`);
        for (const id of ids) {
          const item = historyData[id], status = item.status?.status_str || '已结束';
          const errors = (item.status?.messages || []).filter(m => m[0] === 'execution_error').map(m => m[1]?.exception_message || '节点执行出错');
          const card = document.createElement('div'); card.className = 'card';
          card.innerHTML = `<strong class="${status === 'success' ? 'ok' : status === 'error' ? 'bad' : ''}">${status === 'success' ? '已完成' : status === 'error' ? '执行失败' : escapeHtml(status)} · ${escapeHtml(id.slice(0, 10))}</strong>${errors.map(e => `<span class="muted small">${escapeHtml(e)}</span>`).join('')}`;
          const b = document.createElement('button'); b.type = 'button'; b.textContent = `查看此任务图片（${imagesOf(item).length}）`;
          b.onclick = () => {
            viewingTask = true; list.replaceChildren();
            const back = document.createElement('button'); back.type = 'button'; back.textContent = '返回任务列表'; back.onclick = () => { viewingTask = false; refreshResults(true); };
            list.append(back); const images = imagesOf(item);
            if (!images.length) list.insertAdjacentHTML('beforeend', '<p class="muted">该任务没有图片输出；视频及插件专用结果请在完整编辑器查看。</p>');
            images.forEach(i => list.append(imageCard(i)));
          };
          card.append(b); list.append(card);
        }
        if (!ids.length && !running.length) list.insertAdjacentHTML('beforeend', '<p class="muted">还没有历史任务。提交后可以在这里查看。</p>');
      } else {
        const images = ids.flatMap(id => imagesOf(historyData[id])).slice(0, 60);
        images.forEach(i => list.append(imageCard(i)));
        if (!images.length) list.insertAdjacentHTML('beforeend', '<p class="muted">还没有图片。先在画布生成，完成后刷新这里。</p>');
      }
    } catch (e) { $('#results-summary').textContent = `连接失败：${e.message}。恢复网络后刷新即可。`; }
  }

  // ---------- wiring ----------
  const handlers = {
    'close-sheet': closeSheet, back: backToCanvas, save, 'choose-field': chooseField,
    library: () => openLibrary('favorites'), templates: () => openLibrary('templates'), history: () => openLibrary('history'),
    'close-library': () => $('#library').classList.remove('open'),
    'undo-text': () => history(false), 'redo-text': () => history(true),
    'weight-down': () => weight(-0.1), 'weight-up': () => weight(0.1),
    'favorite-text': async () => {
      if (!text.value.trim()) { toast('先输入文字'); return; }
      const name = await promptText('收藏名称或分类', '', '例如 人像光线'); if (name == null) return;
      rememberItem('favorites', `${name.trim() || '我的收藏'} / ${text.value}`); toast('已收藏到英文词库'); renderLibrary();
    },
    refresh: () => { viewingTask = false; refreshResults(true); },
    'close-viewer': () => { $('#viewer').hidden = true; $('#viewer-image').removeAttribute('src'); },
  };
  document.addEventListener('click', event => {
    const el = event.target.closest('[data-act],[data-tab]'); if (!el) return;
    if (el.dataset.tab) { showResults(el.dataset.tab); return; }
    handlers[el.dataset.act]?.();
  });
  // Keep the toolbar above the on-screen keyboard: size the overlay pages to the visual viewport.
  const syncViewport = () => document.documentElement.style.setProperty('--vh', `${(window.visualViewport?.height || innerHeight)}px`);
  window.visualViewport?.addEventListener('resize', syncViewport); addEventListener('resize', syncViewport); syncViewport();
  addEventListener('pagehide', persist);

  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  if (!standalone && !store.get('installHint', false)) {
    store.set('installHint', true);
    setTimeout(() => toast('提示：Safari 分享 → 添加到主屏幕，可全屏使用；双击提示词节点进入编辑'), 1500);
  }
})();
