// cm-simple: a finished workflow reduced to its key controls, a big generate button and the latest result.
// The controls shown are the workflow's own App-mode selection (extra.linearData), or an automatic guess.
(() => {
  'use strict';
  const { $, store, escapeHtml } = CM;
  const NAMES = { seed: '种子', noise_seed: '种子', control_after_generate: '生成后种子', steps: '步数', cfg: 'CFG 引导', sampler_name: '采样器', scheduler: '调度器',
    denoise: '重绘幅度', width: '宽度', height: '高度', batch_size: '一次出图张数', ckpt_name: '大模型', unet_name: '模型', lora_name: 'LoRA', strength_model: 'LoRA 强度', image: '输入图片', text: '提示词',
    vae_name: 'VAE', clip_name: 'CLIP 模型', clip_name1: 'CLIP 模型 1', clip_name2: 'CLIP 模型 2', type: 'CLIP 类型', model_name: '模型', strength_clip: 'LoRA CLIP 强度', upscale_model_name: '放大模型', control_net_name: 'ControlNet' };
  const SEED_MODES = { fixed: '固定', increment: '递增', decrement: '递减', randomize: '每次随机' };
  const SIZES = [[1024, 1024], [832, 1216], [1216, 832], [768, 1344], [1344, 768], [512, 768], [768, 512]];
  let fields = [], source = 'auto', latest = null, uploadTarget = null;
  const label = f => CM.roleLabel(f) + (NAMES[f.name] || f.label || f.name);

  CM.showSimple = () => {
    CM.closeSheet(); for (const id of ['#prompt', '#results']) $(id).hidden = true; CM.stopResults?.();
    $('#simple').hidden = false; $('#handle').hidden = true; store.set('startPage', 'simple');
    $('#batch-count').textContent = `${store.get('batch', 1)} 次`; reload(); loadLatest();
  };
  async function reload(attempt = 0) {
    if ($('#simple').hidden) return;
    const r = await CM.command('simple');
    if (!r.ok) { $('#simple-status').textContent = '正在等待电脑编辑器加载…'; if (attempt < 40) setTimeout(() => reload(attempt + 1), 1200); return; }
    $('#simple-status').textContent = CM.running ? CM.progressText : '';
    source = r.source; fields = r.fields; render();
  }
  CM.reloadSimple = () => reload();
  CM.on('editorReady', () => reload());

  function render() {
    const box = $('#simple-fields'); box.replaceChildren();
    if (!fields.length) { box.insertAdjacentHTML('beforeend', '<p class="muted">这个工作流里没有识别到常用参数。点右上角“⋯ → 选择显示的参数”自己挑选。</p>'); return; }
    box.insertAdjacentHTML('beforeend', `<p class="muted small">${source === 'workflow' ? '显示的是此工作流保存的参数选择' : '自动识别的关键参数 · 可在“⋯”中自定义'}</p>`);
    let width = null, height = null;
    for (const f of fields) {
      box.append(control(f));
      if (f.name === 'width') width = f; if (f.name === 'height') height = f;
      if (width && height && String(width.target.nodeId) === String(height.target.nodeId)) { box.append(sizes(width, height)); width = height = null; }
    }
  }
  function card(f) {
    const el = document.createElement('div'); el.className = 'card control';
    el.innerHTML = `<div class="control-head"><strong>${escapeHtml(label(f))}</strong><span class="muted small">${escapeHtml(f.nodeTitle)}</span></div>`;
    return el;
  }
  function control(f) {
    const el = card(f);
    if (f.name === 'image' && /LoadImage/.test(f.nodeType)) {
      const img = document.createElement('img'); img.className = 'input-image'; img.alt = f.value;
      if (f.value) { const i = f.value.lastIndexOf('/'); img.src = CM.mediaUrl({ filename: f.value.slice(i + 1), subfolder: i > 0 ? f.value.slice(0, i) : '', type: 'input' }, true); }
      const row = document.createElement('div'); row.className = 'row';
      row.append(CM.button('相册 / 拍照', () => { uploadTarget = f; $('#simple-upload').click(); }, true));
      if (f.options?.length) row.append(CM.button('电脑上的图片', () => CM.editField(f, render)));
      el.append(img, Object.assign(document.createElement('span'), { className: 'muted small', textContent: f.value }), row);
    } else if (f.options) {
      const shown = f.name === 'control_after_generate' ? SEED_MODES[f.value] || f.value : f.value;
      el.append(CM.button(String(shown), async () => {
        const pick = await CM.choose(label(f), f.options.map(o => ({ label: f.name === 'control_after_generate' ? SEED_MODES[o] || o : String(o), value: o })));
        if (pick) write(f, pick.value);
      }));
    } else if (typeof f.value === 'boolean') {
      el.append(CM.button(f.value ? '开' : '关', () => write(f, !f.value)));
    } else if (typeof f.value === 'number') {
      const n = f.number || {}, integer = n.precision === 0;
      let step = n.step > 0 ? n.step : integer ? 1 : 0.1; if (integer) step = Math.max(1, Math.round(step));
      const clamp = v => { if (n.min != null) v = Math.max(v, n.min); if (n.max != null) v = Math.min(v, n.max); return integer ? Math.round(v) : Math.round(v * 1000) / 1000; };
      const decimals = integer ? 0 : step >= 1 ? 0 : step >= 0.1 ? 1 : step >= 0.01 ? 2 : 3;
      const row = document.createElement('div'); row.className = 'row stepper';
      row.append(CM.button('−', () => write(f, clamp(f.value - step))), CM.button(f.value.toFixed(decimals), () => CM.editField(f, render)), CM.button('+', () => write(f, clamp(f.value + step))));
      if (/seed/.test(f.name)) row.append(CM.button('随机', () => write(f, clamp(Math.floor(Math.random() * Math.min(n.max ?? 1e15, 1e15))))));
      el.append(row);
    } else {
      const preview = document.createElement('p'); preview.className = 'preview-text'; preview.textContent = f.value || '（空）点此编辑';
      el.append(preview, Object.assign(document.createElement('span'), { className: 'muted small', textContent: '点击进入提示词编辑（词库、权重、收藏）' }));
      el.classList.add('tappable'); el.onclick = () => CM.openEdit(f);
    }
    return el;
  }
  function sizes(width, height) {
    const el = document.createElement('div'); el.className = 'sizes';
    el.insertAdjacentHTML('beforeend', '<span class="muted small">常用尺寸</span>');
    const row = document.createElement('div'); row.className = 'scroll-row';
    for (const [w, h] of SIZES) row.append(CM.button(`${w}×${h}`, async () => { if (await write(width, w, true)) await write(height, h); }));
    el.append(row); return el;
  }
  async function write(f, value, quiet) {
    const r = await CM.command('widget', { target: f.target, value });
    if (!r.ok) { CM.report(r); reload(); return false; }
    f.value = r.value; if (!quiet) { render(); CM.refreshTitle?.(); }
    return true;
  }
  $('#simple-upload').addEventListener('change', async event => {
    const file = event.target.files[0]; event.target.value = ''; const target = uploadTarget; uploadTarget = null;
    if (!file || !target) return;
    $('#simple-status').textContent = '正在把图片传到电脑…';
    try {
      const form = new FormData(); form.append('image', file, file.name || `ComfyMobile-${Date.now()}.jpg`); form.append('type', 'input'); form.append('overwrite', 'false');
      const reply = await CM.api('/upload/image', { method: 'POST', body: form });
      const name = reply.subfolder ? `${reply.subfolder}/${reply.name}` : reply.name;
      $('#simple-status').textContent = '图片已上传：' + name; await write(target, name);
    } catch (e) { $('#simple-status').textContent = ''; CM.toast('上传失败：' + e.message); }
  });

  async function options() {
    const pick = await CM.choose('简洁模式', [
      { label: '选择显示的参数…', run: customize }, { label: '恢复自动识别', run: () => save([]) },
      { label: '提示词编辑页', run: () => CM.showPrompt() }, { label: '任务', run: () => CM.showResults('tasks') }, { label: '图库', run: () => CM.showResults('gallery') },
      { label: '工作流（保存 / 打开）', run: CM.workflowMenu }, { label: '刷新参数', run: () => reload() },
    ], { search: false });
    pick?.run();
  }
  async function customize() {
    const r = await CM.command('allFields'); if (!CM.report(r)) return;
    const key = f => `${f.target.graphId}|${f.target.nodeId}|${f.target.widgetName}`, chosen = new Set(fields.map(key));
    const result = await CM.dialog((box, done) => {
      box.insertAdjacentHTML('beforeend', '<h2>在简洁模式显示哪些参数</h2>');
      const list = document.createElement('div'); list.className = 'list checks';
      for (const f of r.fields) {
        const id = 'pick-' + Math.random().toString(36).slice(2);
        const row = document.createElement('label'); row.className = 'check'; row.htmlFor = id;
        row.innerHTML = `<input type="checkbox" id="${id}" ${chosen.has(key(f)) ? 'checked' : ''}><span>${escapeHtml(f.nodeTitle)} · ${escapeHtml(label(f))}</span>`;
        row.querySelector('input').onchange = e => { e.target.checked ? chosen.add(key(f)) : chosen.delete(key(f)); };
        list.append(row);
      }
      box.append(list);
      const buttons = document.createElement('div'); buttons.className = 'buttons';
      buttons.append(CM.button('保存', () => done(true), true), CM.button('取消', () => done(null))); box.append(buttons);
    });
    if (!result) return;
    await save(r.fields.filter(f => chosen.has(key(f))).map(f => ({ graphId: f.target.graphId, nodeId: f.target.nodeId, name: f.target.widgetName })));
    CM.toast('已保存到工作流；保存工作流文件后，电脑和手机也会使用这个选择');
  }
  async function save(entries) {
    const r = await CM.command('setSimple', { entries }); if (!CM.report(r)) return;
    source = r.source; fields = r.fields; render(); CM.refreshTitle?.();
  }

  // ---------- result ----------
  async function loadLatest() {
    try {
      const page = await CM.api('/jobs?limit=1&status=completed'), output = page.jobs?.[0]?.preview_output;
      if (!output) return;
      latest = output; const img = $('#simple-image');
      if (CM.isVideo(output.filename)) { img.hidden = true; $('#simple-result-label').textContent = '最新结果是视频，点击播放'; }
      else { img.src = CM.mediaUrl(output, true); img.hidden = false; $('#simple-result-label').textContent = ''; }
    } catch { /* keep the previous result */ }
  }
  CM.on('completed', () => { if (!$('#simple').hidden) { $('#simple-status').textContent = '生成完成'; loadLatest(); } });
  CM.on('progress', text => { if (!$('#simple').hidden) $('#simple-status').textContent = text; });
  CM.on('preview', dataUrl => { if (!$('#simple').hidden) { const img = $('#simple-image'); img.src = dataUrl; img.hidden = false; $('#simple-result-label').textContent = ''; latest = null; } });
  CM.on('running', busy => { $('#simple-stop').disabled = !busy; });
  // Progress panel: the running node, a bar for its sampling steps, and the queue behind it.
  CM.on('state', (state, busy) => {
    const panel = $('#simple-progress'); panel.hidden = !busy; if (!busy) return;
    const p = state.progress, bar = $('#simple-bar');
    $('#simple-stage').textContent = state.executingTitle ? '正在执行：' + state.executingTitle : '正在生成…';
    bar.classList.toggle('indeterminate', !(p?.max > 0)); bar.style.width = p?.max > 0 ? `${Math.min(100, 100 * p.value / p.max)}%` : '';
    let text = p?.max > 0 ? `第 ${p.value} / ${p.max} 步 · ${Math.round(100 * p.value / p.max)}%` : '等待电脑开始采样';
    if (state.queueRemaining > 1) text += ` · 后面还有 ${state.queueRemaining - 1} 个任务`;
    $('#simple-steps').textContent = text;
  });
  $('#simple-result').addEventListener('click', () => latest ? CM.openOutput(latest) : null);

  const batch = delta => { const n = Math.max(1, Math.min(100, store.get('batch', 1) + delta)); store.set('batch', n); $('#batch-count').textContent = `${n} 次`; };
  CM.simpleHandlers = {
    'simple-options': options, workflow: () => CM.workflowMenu(), generate: () => CM.generate(false),
    'batch-down': () => batch(-1), 'batch-up': () => batch(1), batch: CM.chooseBatch,
  };
})();
