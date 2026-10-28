// cm-prompt: full-screen prompt editor, bilingual library, tag view and favorites synced through the computer.
(() => {
  'use strict';
  const { $, store, escapeHtml } = CM;
  const P = window.PromptText;
  const text = $('#text');
  const prompt = { target: null, label: '', saved: store.get('draftSaved', null), undo: [], redo: [], last: '', typedAt: 0 };
  text.value = store.get('draft', ''); prompt.last = text.value;
  if (text.value) $('#field-label').textContent = '草稿 · 上次：' + store.get('draftLabel', '未绑定字段');
  const dirty = () => prompt.saved == null ? text.value.trim() !== '' : text.value !== prompt.saved;
  function persist() { store.set('draft', text.value); store.set('draftLabel', prompt.label || store.get('draftLabel', '')); store.set('draftSaved', prompt.saved); }
  function renderTarget() { $('#field-label').textContent = prompt.target ? prompt.label : (text.value ? '草稿未绑定 · 点此选择节点字段' : '选择节点文字字段'); }
  CM.resetTarget = () => { prompt.target = null; renderTarget(); };
  function replaceText(value, start, end) { prompt.typedAt = 0; text.value = value; prompt.last = value; persist(); if (start != null) text.setSelectionRange(start, end ?? start); }
  function remember() { prompt.undo.push(prompt.last); if (prompt.undo.length > 80) prompt.undo.shift(); prompt.redo = []; }
  text.addEventListener('input', () => {
    if (text.value === prompt.last) return;
    // Typing within a second is one undo step, so undo does not go back one character at a time.
    if (Date.now() - prompt.typedAt > 1000 || !prompt.undo.length) remember(); else prompt.redo = [];
    prompt.typedAt = Date.now(); prompt.last = text.value; persist();
  });

  CM.showPrompt = () => {
    CM.closeSheet(); prompt.returnTo = !$('#simple').hidden ? 'simple' : 'canvas';
    $('#results').hidden = true; $('#simple').hidden = true; $('#prompt').hidden = false; renderTarget(); renderLibrary(); sync();
  };
  CM.leavePrompt = () => { persist(); text.blur(); $('#library').classList.remove('open'); prompt.returnTo === 'simple' ? CM.showSimple() : CM.showCanvas(); };
  CM.openEdit = async field => {
    const fresh = await CM.call('readField', field.target); if (!CM.report(fresh)) return;
    const value = fresh.value, label = `${CM.roleLabel(field)}${field.nodeTitle} · #${field.target.nodeId} / ${field.name}`;
    const sameTarget = prompt.target && prompt.target.token === field.target.token && prompt.target.session === field.target.session;
    CM.showPrompt();
    if (sameTarget && dirty()) { text.focus(); return; }
    const bind = () => { prompt.target = field.target; prompt.label = label; prompt.saved = value; persist(); renderTarget(); text.focus(); };
    if (dirty() && text.value !== value) {
      const choice = await CM.ask('保留草稿并绑定此字段，还是载入节点当前文字？', ['保留草稿并绑定', '载入节点文字']);
      if (choice === 0) bind(); else if (choice === 1) { remember(); replaceText(value, value.length); bind(); }
    } else { if (text.value !== value) { remember(); replaceText(value, value.length); } bind(); }
  };
  CM.on('edit', field => { if ($('#prompt').hidden && $('#results').hidden) CM.openEdit(field); });
  async function chooseField() {
    const r = await CM.call('fields'); if (!CM.report(r)) return;
    if (!r.fields.length) { CM.toast('当前工作流没有可识别的标准文字字段'); return; }
    const chosen = await CM.choose('选择实际文字字段', r.fields.map(f => ({ label: `${CM.roleLabel(f)}${f.nodeTitle} · #${f.target.nodeId} / ${f.name}`, sub: f.value.slice(0, 60), field: f })));
    if (chosen) CM.openEdit(chosen.field);
  }
  async function save() {
    persist();
    if (!prompt.target) { CM.toast('请先选择工作流中的目标字段，草稿已保存在本机'); return; }
    const value = text.value, r = await CM.call('writeField', prompt.target, value);
    if (!CM.report(r)) return;
    prompt.saved = value; persist(); rememberItem('history', value); CM.toast('已写回节点；记得在“工作流”里保存到电脑'); CM.refreshTitle?.();
  }
  function selectionOrPhrase() {
    let start = Math.min(text.selectionStart, text.selectionEnd), end = Math.max(text.selectionStart, text.selectionEnd);
    if (start === end) { const span = P.phraseAt(text.value, start); if (!span) return null; [start, end] = span; }
    return [start, end];
  }
  function weight(delta) {
    const span = selectionOrPhrase(); if (!span) { CM.toast('把光标放在要调整的词上，或选中词句'); return; }
    const e = P.weight(text.value, span[0], span[1], delta); remember(); replaceText(e.text, e.start, e.end);
  }
  function insertSnippet(snippet, replaceAll) {
    remember();
    if (replaceAll) { replaceText(snippet, snippet.length); return; }
    const e = P.insert(text.value, Math.min(text.selectionStart, text.selectionEnd), Math.max(text.selectionStart, text.selectionEnd), snippet);
    replaceText(e.text, e.start, e.end);
  }
  function history(forward) {
    const from = forward ? prompt.redo : prompt.undo, to = forward ? prompt.undo : prompt.redo;
    if (!from.length) return; to.push(text.value); replaceText(from.pop());
  }
  /** Tags view: tap a tag to change its weight, move or delete it. */
  function tagView() {
    CM.dialog((box, done) => {
      box.insertAdjacentHTML('beforeend', '<h2>标签视图</h2><p class="muted small">点标签：调权重、左右移动、删除。改动直接作用于草稿，可撤销。</p>');
      const flow = document.createElement('div'); flow.className = 'tags'; box.append(flow);
      const render = () => {
        flow.replaceChildren(); const spans = P.tags(text.value);
        if (!spans.length) flow.insertAdjacentHTML('beforeend', '<p class="muted">还没有内容</p>');
        spans.forEach(([s, e], index) => {
          const b = CM.button(text.value.slice(s, e), async () => {
            const actions = ['权重 +0.1', '权重 −0.1', '向左移', '向右移', '删除'];
            const pick = await CM.choose(text.value.slice(s, e), actions.map((label, i) => ({ label, i })), { search: false });
            reopen(pick?.i, index);
          });
          flow.append(b);
        });
      };
      render();
      const row = document.createElement('div'); row.className = 'buttons'; row.append(CM.button('完成', () => done(null), true)); box.append(row);
    });
    function reopen(action, index) {
      if (action != null) {
        const spans = P.tags(text.value), at = spans[index]; let e = null;
        if (at) {
          if (action === 0) e = P.weight(text.value, at[0], at[1], 0.1);
          if (action === 1) e = P.weight(text.value, at[0], at[1], -0.1);
          if (action === 2 && index > 0) e = P.swap(text.value, at, spans[index - 1]);
          if (action === 3 && index < spans.length - 1) e = P.swap(text.value, at, spans[index + 1]);
          if (action === 4) e = P.remove(text.value, at[0], at[1]);
        }
        if (e) { remember(); replaceText(e.text); }
      }
      tagView();
    }
  }

  // ---------- library ----------
  const builtin = { terms: [], templates: [] }; let libraryMode = 'favorites';
  fetch('prompt-library.json', { cache: 'no-cache' }).then(r => r.json()).then(data => {
    builtin.terms = data.categories.flatMap(c => c.items.map(i => ({ label: i.label, text: i.text, translation: i.translation || i.label, category: c.label, note: i.note || '', keywords: i.keywords || '' })));
    builtin.templates = data.templates.map(t => ({ label: t.label, text: t.text, translation: t.translation || t.label, category: '内置模板', note: t.note || '', negative: t.negative || '', keywords: t.category || '' }));
    renderLibrary(true);
  }).catch(() => CM.toast('内置词库读取失败，仍可使用自己的收藏'));
  function rememberItem(key, value) { const old = store.get(key, []); store.set(key, [value, ...old.filter(v => v !== value)].slice(0, 60)); sync(); }
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
        if (all && dirty() && await CM.ask('用此英文内容替换当前草稿？', ['替换']) !== 0) return;
        insertSnippet(entry.text, all); if (innerWidth < 900) $('#library').classList.remove('open');
      };
      row.append(b);
      if (entry.negative) row.append(Object.assign(CM.button('反', () => writeNegative(entry.negative)), { className: 'star', title: '写入反向字段' }));
      const star = CM.button(entry.saved != null ? '✎' : '☆', entry.saved != null ? () => editSaved(entry.saved)
        : () => { rememberItem(libraryMode === 'templates' ? 'templates' : 'favorites', `${entry.label} / ${entry.text}`); CM.toast('已收藏'); renderLibrary(); });
      star.className = 'star'; star.setAttribute('aria-label', entry.saved != null ? '编辑或删除' : '加入我的收藏'); row.append(star);
      list.append(row);
    }
    $('#library-count').textContent = `${visible.length} 项 · 点选插入英文，中文仅作说明`;
  }
  async function writeNegative(negative) {
    const r = await CM.call('fields'); if (!CM.report(r)) return;
    let list = r.fields.filter(f => f.role === 'negative'); if (!list.length) list = r.fields;
    const pick = await CM.choose('写入哪个反向字段？', list.map(f => ({ label: CM.roleLabel(f) + f.nodeTitle + ' / ' + f.name, sub: f.value.slice(0, 60), field: f })));
    if (!pick || await CM.ask('用模板的反向词替换该字段当前内容？\n\n' + negative, ['替换']) !== 0) return;
    if (CM.report(await CM.call('writeField', pick.field.target, negative))) CM.toast('已写入反向字段');
  }
  async function editSaved(original) {
    const key = libraryMode === 'history' ? 'history' : libraryMode === 'templates' ? 'templates' : 'favorites';
    const choice = await CM.ask(original, ['编辑', '删除'], '我的收藏');
    // Always re-read the latest list and locate the item by content, so entries added elsewhere are never lost.
    const latest = store.get(key, []), index = latest.indexOf(original);
    if (choice == null) return;
    if (index < 0) { CM.toast('该条目已被修改，请重新打开词库'); return; }
    if (choice === 1) { latest.splice(index, 1); store.set(key, latest); renderLibrary(); sync(); return; }
    const value = await CM.promptText('编辑收藏（分类 / 内容）', original); if (value == null || !value.trim()) return;
    const now = store.get(key, []), at = now.indexOf(original); if (at < 0) { CM.toast('该条目已被修改，请重新打开词库'); return; }
    now[at] = value.trim(); store.set(key, now); renderLibrary(); sync();
  }
  function openLibrary(mode) { libraryMode = mode; $('#library-search').value = ''; renderLibrary(true); $('#library').classList.add('open'); }
  $('#library-search').addEventListener('input', () => renderLibrary());
  $('#library-category').addEventListener('change', () => renderLibrary());

  // Favorites, templates and history live in the computer's user folder, shared with phones and other iPads.
  const FILE = '/userdata/comfymobile%2Flibrary.json', KEYS = ['favorites', 'templates', 'history'];
  let syncing = false;
  async function sync() {
    if (syncing) return; syncing = true;
    try {
      const start = Object.fromEntries(KEYS.map(k => [k, store.get(k, [])]));
      let remote = {};
      try { remote = await CM.api(FILE); } catch (e) { if (!/404/.test(e.message)) throw e; }
      const next = Object.fromEntries(KEYS.map(k => [k, P.merge(remote[k] || [], start[k], store.get('synced_' + k, []))]));
      if (JSON.stringify(next) !== JSON.stringify(remote)) await CM.postJson(FILE + '?overwrite=true', next);
      let changed = false;
      for (const k of KEYS) {
        if (JSON.stringify(store.get(k, [])) !== JSON.stringify(start[k])) { changed = true; continue; }
        store.set(k, next[k]); store.set('synced_' + k, next[k]);
      }
      if (!$('#prompt').hidden) renderLibrary();
      if (changed) setTimeout(sync, 0);
    } catch { /* offline: keep the local copy; the next sync merges it */ }
    finally { syncing = false; }
  }

  const handlers = {
    back: () => { if (!$('#prompt').hidden) CM.leavePrompt(); },
    save, 'choose-field': chooseField, tags: tagView,
    library: () => openLibrary('favorites'), templates: () => openLibrary('templates'), history: () => openLibrary('history'),
    'close-library': () => $('#library').classList.remove('open'),
    'undo-text': () => history(false), 'redo-text': () => history(true),
    'weight-down': () => weight(-0.1), 'weight-up': () => weight(0.1),
    'favorite-text': async () => {
      if (!text.value.trim()) { CM.toast('先输入文字'); return; }
      const name = await CM.promptText('收藏名称或分类', '', '例如 人像光线', 'text'); if (name == null) return;
      rememberItem('favorites', `${name.trim() || '我的收藏'} / ${text.value}`); CM.toast('已收藏到英文词库'); renderLibrary();
    },
  };
  CM.promptHandlers = handlers;
  addEventListener('pagehide', persist);
  // Keyboard users on iPad: ⌘S saves to the node, Esc goes back.
  text.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save(); }
    if (event.key === 'Escape') CM.leavePrompt();
  });
  sync();
})();
