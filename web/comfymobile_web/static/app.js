// app: connects buttons to the cm-*.js pages and restores the last mode on start.
(() => {
  'use strict';
  const { $, store } = CM;
  const handlers = {
    ...CM.promptHandlers, ...CM.simpleHandlers, ...CM.resultHandlers,
    'close-sheet': CM.closeSheet, stop: CM.interrupt, retry: CM.retry, agent: CM.openAgent,
    // "Back" depends on the page it sits on: prompt returns to where it came from, results to the mode in use.
    back: () => {
      if (!$('#prompt').hidden) CM.leavePrompt();
      else if (!$('#results').hidden) store.get('startPage', 'canvas') === 'simple' ? CM.showSimple() : CM.showCanvas();
      else CM.showCanvas();
    },
  };
  document.addEventListener('click', event => {
    const el = event.target.closest('[data-act],[data-tab]'); if (!el) return;
    if (el.dataset.tab) { CM.showResults(el.dataset.tab); return; }
    handlers[el.dataset.act]?.();
  });
  $('#chip').addEventListener('click', () => { const src = $('#chip-thumb').src; if (src && !$('#chip-thumb').hidden) { $('#viewer-image').src = src; $('#viewer-image').hidden = false; $('#viewer-video').hidden = true; $('#viewer-open').href = src; $('#viewer').hidden = false; } });
  // Keep the toolbars above the on-screen keyboard: size the overlay pages to the visual viewport.
  const syncViewport = () => document.documentElement.style.setProperty('--vh', `${(window.visualViewport?.height || innerHeight)}px`);
  window.visualViewport?.addEventListener('resize', syncViewport); addEventListener('resize', syncViewport); syncViewport();
  if (store.get('startPage', 'canvas') === 'simple') CM.showSimple();
  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  if (!standalone && !store.get('installHint', false)) {
    store.set('installHint', true);
    setTimeout(() => CM.toast('提示：Safari 分享 → 添加到主屏幕，可全屏使用；双击提示词节点进入编辑'), 1500);
  }
})();
