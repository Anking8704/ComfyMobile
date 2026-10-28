// cm-results: tasks and gallery from ComfyUI's paginated /api/jobs, plus a zoomable viewer and video player.
(() => {
  'use strict';
  const { $, escapeHtml } = CM;
  let tab = 'tasks', timer = 0, signature = '', limit = 24, openJob = null, hasMore = false;
  CM.showResults = which => {
    CM.closeSheet(); tab = which; openJob = null; signature = ''; limit = 24;
    for (const id of ['#prompt', '#simple']) $(id).hidden = true;
    $('#results').hidden = false; $('#handle').hidden = true;
    document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('on', b.dataset.tab === which));
    refresh(true); clearInterval(timer); timer = setInterval(() => refresh(false), 4000);
  };
  CM.stopResults = () => clearInterval(timer);
  const statusLabel = s => ({ completed: '已完成', failed: '失败', cancelled: '已停止', in_progress: '正在生成', pending: '排队中' }[s] || s);
  function describe(job) {
    const when = job.create_time ? new Date(job.create_time).toLocaleString() : '';
    const took = job.execution_start_time && job.execution_end_time > job.execution_start_time ? ` · 用时 ${Math.round((job.execution_end_time - job.execution_start_time) / 1000)} 秒` : '';
    return `${statusLabel(job.status)}${when ? ' · ' + when : ''}${took}`;
  }
  async function refresh(force) {
    if ($('#results').hidden || openJob) return;
    try {
      const page = await CM.api(`/jobs?limit=${limit}${tab === 'gallery' ? '&status=completed' : ''}`);
      const jobs = page.jobs || [], running = jobs.filter(j => j.status === 'in_progress').length, pending = jobs.filter(j => j.status === 'pending').length;
      hasMore = !!page.pagination?.has_more;
      $('#results-summary').textContent = `${tab === 'tasks' ? `运行中 ${running} · 等待 ${pending} · ` : ''}共 ${page.pagination?.total ?? jobs.length} 条${running && CM.progressText ? ' · ' + CM.progressText : ''}`;
      const next = JSON.stringify(jobs) + tab;
      if (!force && next === signature) return; signature = next;
      const list = $('#results-list'); list.replaceChildren();
      for (const job of jobs) { if (tab === 'gallery' && !job.preview_output) continue; list.append(card(job)); }
      if (!list.children.length) list.insertAdjacentHTML('beforeend', `<p class="muted">${tab === 'gallery' ? '还没有图片。先生成，完成后会出现在这里。' : '还没有任务。提交后可以在这里查看。'}</p>`);
      if (hasMore) list.append(Object.assign(CM.button('加载更多', () => { limit += 24; refresh(true); }), { className: 'more' }));
    } catch (e) { $('#results-summary').textContent = `连接失败：${e.message}。恢复网络后刷新即可。`; }
  }
  function thumb(output) {
    if (!output) return '';
    if (CM.isVideo(output.filename)) return '<div class="video-thumb">▶ 视频</div>';
    return `<img loading="lazy" alt="${escapeHtml(output.filename)}" src="${CM.mediaUrl(output, true)}">`;
  }
  function card(job) {
    const el = document.createElement('div'); el.className = 'card tappable';
    const error = job.execution_error;
    const detail = error ? `出错节点：${escapeHtml(error.node_type || '')} #${escapeHtml(String(error.node_id ?? ''))}<br>${escapeHtml(error.exception_message || '')}`
      : job.status === 'in_progress' ? escapeHtml(CM.progressText) : job.outputs_count > 1 ? `共 ${job.outputs_count} 个结果` : '';
    el.innerHTML = `${thumb(job.preview_output)}<strong class="${job.status === 'completed' ? 'ok' : job.status === 'failed' ? 'bad' : ''}">${escapeHtml(tab === 'gallery' ? new Date(job.create_time || 0).toLocaleString() : describe(job))}</strong>${detail ? `<span class="muted small">${detail}</span>` : ''}`;
    el.onclick = () => act(job);
    return el;
  }
  async function act(job) {
    if (tab === 'gallery' && job.outputs_count <= 1) { CM.openOutput(job.preview_output); return; }
    const items = [];
    if (job.status === 'pending') items.push({ label: '取消这个排队任务', run: async () => { try { await CM.postJson('/queue', { delete: [job.id] }); CM.toast('已取消'); refresh(true); } catch (e) { CM.toast('取消失败：' + e.message); } } });
    if (job.status === 'in_progress') items.push({ label: '停止生成', run: CM.interrupt });
    if (job.outputs_count > 0) items.push({ label: `查看全部结果（${job.outputs_count}）`, run: () => showJob(job) });
    if (!['pending', 'in_progress'].includes(job.status)) {
      items.push({ label: '换个种子再来一次', run: async () => { if (CM.report(await CM.command('loadJob', { id: job.id, mode: 'rerun' }))) { CM.toast('已重新提交（随机新种子）'); refresh(true); } } });
      items.push({ label: '把这次的工作流载入画布', run: async () => { if (CM.report(await CM.command('loadJob', { id: job.id, mode: 'open' }))) { CM.resetTarget?.(); CM.showCanvas(); CM.toast('已在新标签中载入'); } } });
    }
    if (job.execution_error?.node_id != null) items.push({ label: '在画布上定位出错的节点', run: async () => { CM.showCanvas(); const r = await CM.command('select', { nodeId: job.execution_error.node_id }); if (!r.ok) CM.toast('当前画布不是这个任务的工作流，可先“载入画布”'); } });
    if (!items.length) return;
    const pick = await CM.choose(describe(job), items, { search: false }); pick?.run();
  }
  function outputsOf(full) {
    return Object.values(full.outputs || {}).flatMap(node => Object.values(node || {}).flatMap(files => Array.isArray(files) ? files : []))
      .filter(f => f && f.filename && /\.(png|jpe?g|webp|gif|mp4|m4v|webm|mov)$/i.test(f.filename));
  }
  async function showJob(job) {
    openJob = job; const list = $('#results-list'); list.replaceChildren(); $('#results-summary').textContent = '正在读取此任务的全部结果…';
    try {
      const outputs = outputsOf(await CM.api(`/jobs/${encodeURIComponent(job.id)}`));
      $('#results-summary').textContent = outputs.length ? `此任务共 ${outputs.length} 个结果` : '该任务没有图片或视频输出';
      list.append(Object.assign(CM.button('返回任务列表', () => { openJob = null; signature = ''; refresh(true); }), { className: 'more' }));
      for (const o of outputs) {
        const el = document.createElement('div'); el.className = 'card tappable';
        el.innerHTML = `${thumb(o)}<span class="name">${escapeHtml(o.filename)}</span>`; el.onclick = () => CM.openOutput(o); list.append(el);
      }
    } catch (e) { $('#results-summary').textContent = '读取失败：' + e.message; }
  }

  // ---------- viewer: pinch, drag and double-tap zoom ----------
  const stage = $('#viewer-stage'), image = $('#viewer-image'), video = $('#viewer-video');
  let view = { scale: 1, x: 0, y: 0 }; const pointers = new Map(); let gesture = null, lastTap = 0;
  const apply = () => { image.style.transform = `translate(${view.x}px, ${view.y}px) scale(${view.scale})`; };
  const reset = () => { view = { scale: 1, x: 0, y: 0 }; apply(); };
  stage.addEventListener('pointerdown', e => {
    if (video.hidden === false) return;
    stage.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const pts = [...pointers.values()];
    gesture = pts.length === 2 ? { dist: Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y), scale: view.scale, cx: (pts[0].x + pts[1].x) / 2, cy: (pts[0].y + pts[1].y) / 2, x: view.x, y: view.y }
      : { px: e.clientX, py: e.clientY, x: view.x, y: view.y };
    if (pts.length === 1) {
      if (Date.now() - lastTap < 300) {
        const rect = stage.getBoundingClientRect(), fx = e.clientX - rect.left - rect.width / 2, fy = e.clientY - rect.top - rect.height / 2;
        if (view.scale > 1.5) reset(); else { view = { scale: 3, x: -fx * 2, y: -fy * 2 }; apply(); }
        lastTap = 0;
      } else lastTap = Date.now();
    }
  });
  stage.addEventListener('pointermove', e => {
    if (!pointers.has(e.pointerId)) return; pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const pts = [...pointers.values()];
    if (pts.length === 2 && gesture?.dist) {
      const scale = Math.max(1, Math.min(8, gesture.scale * Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y) / gesture.dist));
      view = { scale, x: gesture.x * scale / gesture.scale, y: gesture.y * scale / gesture.scale }; apply();
    } else if (pts.length === 1 && gesture?.px != null && view.scale > 1) { view.x = gesture.x + e.clientX - gesture.px; view.y = gesture.y + e.clientY - gesture.py; apply(); }
  });
  const up = e => { pointers.delete(e.pointerId); gesture = null; if (view.scale <= 1.01) reset(); };
  stage.addEventListener('pointerup', up); stage.addEventListener('pointercancel', up);
  CM.openOutput = output => {
    if (!output) return; reset();
    const url = CM.mediaUrl(output, false), isVideo = CM.isVideo(output.filename);
    image.hidden = isVideo; video.hidden = !isVideo;
    if (isVideo) { video.src = url; video.play().catch(() => {}); $('#viewer-hint').textContent = '视频 · 用“新标签打开”后可保存'; }
    else { image.src = url; $('#viewer-hint').textContent = '双指或双击放大 · 长按图片可“存储到照片”'; }
    $('#viewer-open').href = url; $('#viewer').hidden = false;
  };
  CM.resultHandlers = {
    refresh: () => { openJob = null; signature = ''; refresh(true); },
    'clear-queue': async () => {
      if (await CM.ask('取消所有还在排队的任务？正在执行的任务不受影响。', ['全部取消']) !== 0) return;
      try { await CM.postJson('/queue', { clear: true }); CM.toast('排队已清空'); refresh(true); } catch (e) { CM.toast('操作失败：' + e.message); }
    },
    'close-viewer': () => { $('#viewer').hidden = true; image.removeAttribute('src'); video.pause(); video.removeAttribute('src'); },
  };
  CM.on('completed', () => { if (!$('#results').hidden) { signature = ''; refresh(true); } });
})();
