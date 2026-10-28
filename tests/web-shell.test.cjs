// The iPad page's prompt rules must behave exactly like the Android app's: both run the same case files.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const P = require('../web/comfymobile_web/static/prompt-text.js');
const rows = file => fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(l => l.trim() && !l.startsWith('#')).map(l => l.split('\t'));
const list = cell => cell ? cell.split('|') : [];
const unescape = s => s.replace(/\\n/g, '\n');

test('prompt editing cases shared with PromptText.java', () => {
  for (const [op, raw, a, b, arg, expected, start, end] of rows('tests/prompt-cases.tsv')) {
    const text = unescape(raw);
    if (op === 'phrase') {
      const span = P.phraseAt(text, Number(a));
      assert.equal(span ? String(span[0]) : 'none', start, raw); assert.equal(span ? String(span[1]) : 'none', end, raw);
      continue;
    }
    const result = op === 'weight' ? P.weight(text, +a, +b, parseFloat(arg))
      : op === 'insert' ? P.insert(text, +a, +b, unescape(arg))
      : op === 'remove' ? P.remove(text, +a, +b)
      : P.swap(text, [+a, +b], arg.split(',').map(Number));
    assert.equal(result.text, unescape(expected), `${op} ${raw}`);
    assert.equal(String(result.start), start, `${op} ${raw} start`); assert.equal(String(result.end), end, `${op} ${raw} end`);
  }
});
test('favorites merge cases shared with LibraryMerge.java', () => {
  for (const [server, local, last, expected] of rows('tests/merge-cases.tsv'))
    assert.deepEqual(P.merge(list(server), list(local), list(last)), list(expected), `${server} ${local} ${last}`);
  assert.equal(P.merge(['a','b','c'], [], [], 2).length, 2);
});
test('the web route only serves whitelisted files', () => {
  const source = fs.readFileSync('web/comfymobile_web/__init__.py', 'utf8');
  for (const name of ['index.html', 'app.js', 'prompt-text.js', 'editor-adapter.js', 'prompt-library.json'])
    assert.ok(source.includes(`"${name}"`), name);
  assert.ok(/hmac\.compare_digest/.test(source), 'token comparison must be constant time');
});
