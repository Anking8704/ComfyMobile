// Prompt editing rules shared with the Android app (PromptText.java, LibraryMerge.java).
// tests/prompt-cases.tsv and tests/merge-cases.tsv check both implementations against the same cases.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.PromptText = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const edit = (text, start, end) => ({ text, start, end });
  function weight(text, start, end, delta) {
    if (start < 0 || end <= start || end > text.length) return edit(text, Math.max(0, start), Math.max(0, end));
    let phrase = text.slice(start, end), previous = 1;
    const m = /^\((.+):([0-9]+(?:\.[0-9]+)?)\)$/s.exec(phrase);
    if (m) { phrase = m[1]; previous = parseFloat(m[2]); }
    const replacement = `(${phrase}:${Math.max(0.1, previous + delta).toFixed(1)})`;
    return edit(text.slice(0, start) + replacement + text.slice(end), start, start + replacement.length);
  }
  function insert(text, start, end, snippet) {
    start = Math.max(0, Math.min(start, text.length)); end = Math.max(start, Math.min(end, text.length));
    const left = text.slice(0, start), right = text.slice(end);
    let before = '', after = '';
    if (start === end) {
      if (left && !/\s$/.test(left) && !',;(['.includes(left[left.length - 1])) before = ', ';
      if (right && !/^\s/.test(right) && !',;)]'.includes(right[0])) after = ', ';
    }
    const caret = start + before.length + snippet.length;
    return edit(left + before + snippet + after + right, caret, caret);
  }
  function tags(text) {
    const spans = []; let depth = 0, from = 0;
    for (let i = 0; i <= text.length; i++) {
      const c = i < text.length ? text[i] : ',';
      if (c === '(' || c === '[') depth++; else if ((c === ')' || c === ']') && depth > 0) depth--;
      if (i === text.length || ((c === ',' || c === '\n' || c === '，') && depth === 0)) {
        let s = from, e = i;
        while (s < e && /\s/.test(text[s])) s++;
        while (e > s && /\s/.test(text[e - 1])) e--;
        if (e > s) spans.push([s, e]);
        from = i + 1;
      }
    }
    return spans;
  }
  function phraseAt(text, cursor) { return tags(text).find(([s, e]) => cursor >= s && cursor <= e) || null; }
  function remove(text, start, end) {
    let e = end; while (e < text.length && text[e] === ' ') e++;
    if (e < text.length && (text[e] === ',' || text[e] === '，')) { e++; while (e < text.length && text[e] === ' ') e++; return edit(text.slice(0, start) + text.slice(e), start, start); }
    let s = start; while (s > 0 && text[s - 1] === ' ') s--;
    if (s > 0 && (text[s - 1] === ',' || text[s - 1] === '，')) s--;
    return edit(text.slice(0, s) + text.slice(end), s, s);
  }
  function swap(text, a, b) {
    const first = a[0] < b[0] ? a : b, second = a[0] < b[0] ? b : a;
    const one = text.slice(first[0], first[1]), two = text.slice(second[0], second[1]);
    const result = text.slice(0, first[0]) + two + text.slice(first[1], second[0]) + one + text.slice(second[1]);
    const moved = a === first ? second[0] + (two.length - one.length) : first[0];
    return edit(result, moved, moved + text.slice(a[0], a[1]).length);
  }
  /** Content-based three-way merge of a favorites list kept on the computer and this device's copy. */
  function merge(server, local, lastSynced, limit = 60) {
    const result = [];
    for (const item of local) if (!server.includes(item) && !lastSynced.includes(item) && !result.includes(item)) result.push(item);
    for (const item of server) if (!(lastSynced.includes(item) && !local.includes(item)) && !result.includes(item)) result.push(item);
    return result.slice(0, limit);
  }
  return { weight, insert, tags, phraseAt, remove, swap, merge };
});
