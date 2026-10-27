const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const libraryPath = path.join(root, 'app/src/main/assets/prompt-library.json');
const sourcesPath = path.join(root, 'docs/prompt-library-sources.md');
const readLibrary = () => {
  assert.ok(fs.existsSync(libraryPath), 'The bundled English prompt library must exist');
  return JSON.parse(fs.readFileSync(libraryPath, 'utf8'));
};
const nonempty = (value, location) => assert.ok(typeof value === 'string' && value.trim(), `${location} must not be empty`);
const english = (value, location) => {
  nonempty(value, location);
  assert.match(value, /^[\x20-\x7e]+$/, `${location} must contain readable ASCII only`);
  assert.match(value, /[a-zA-Z]/, `${location} must contain English words`);
};

test('bundled library provides at least 18 searchable Chinese-labelled categories and 350 English terms', () => {
  const library = readLibrary();
  assert.equal(library.version, 1);
  assert.ok(Array.isArray(library.categories) && library.categories.length >= 18);
  const ids = new Set();
  const terms = new Set();
  let count = 0;
  for (const category of library.categories) {
    assert.match(category.id, /^[a-z][a-z0-9_]*$/);
    assert.ok(!ids.has(category.id), `Duplicate category id: ${category.id}`);
    ids.add(category.id);
    nonempty(category.label, `${category.id}.label`);
    assert.match(category.label, /[\u3400-\u9fff]/);
    assert.ok(Array.isArray(category.items) && category.items.length > 0);
    for (const item of category.items) {
      nonempty(item.label, `${category.id}.item.label`);
      assert.match(item.label, /[\u3400-\u9fff]/);
      english(item.text, `${category.id}.${item.label}.text`);
      nonempty(item.keywords, `${category.id}.${item.label}.keywords`);
      assert.match(item.keywords, /[\u3400-\u9fff]/, 'Chinese search keywords must be available');
      const normalized = item.text.toLowerCase().trim().replace(/[_\s-]+/g, ' ');
      assert.ok(!terms.has(normalized), `Duplicate English term: ${item.text}`);
      terms.add(normalized);
      count += 1;
    }
  }
  assert.ok(count >= 350 && count <= 500, `Expected 350-500 terms, found ${count}`);
});

test('at least 24 distinct templates describe scenes in English with Chinese model guidance', () => {
  const library = readLibrary();
  const ids = new Set(library.categories.map(category => category.id));
  assert.ok(Array.isArray(library.templates) && library.templates.length >= 24);
  const texts = new Set();
  const labels = new Set();
  for (const template of library.templates) {
    nonempty(template.label, 'template.label');
    assert.match(template.label, /[\u3400-\u9fff]/);
    assert.ok(!labels.has(template.label), `Duplicate template label: ${template.label}`);
    labels.add(template.label);
    assert.ok(ids.has(template.category), `Unknown template category: ${template.category}`);
    english(template.text, `${template.label}.text`);
    assert.ok(template.text.split(/\s+/).length >= 25, `${template.label} needs a descriptive scene`);
    assert.ok((template.text.match(/[.!?](?:\s|$)/g) || []).length >= 2, `${template.label} needs at least two sentences`);
    assert.ok(!texts.has(template.text.toLowerCase()), `Duplicate template text: ${template.label}`);
    texts.add(template.text.toLowerCase());
    if (template.negative !== undefined) english(template.negative, `${template.label}.negative`);
    nonempty(template.note, `${template.label}.note`);
    assert.match(template.note, /[\u3400-\u9fff]/);
  }
});

test('reference notes provide first-party source links that readers can inspect', () => {
  assert.ok(fs.existsSync(sourcesPath), 'Source notes must ship with the library');
  const notes = fs.readFileSync(sourcesPath, 'utf8');
  const links = [...notes.matchAll(/https:\/\/[^\s)<>]+/g)].map(match => new URL(match[0]));
  const firstParty = links.filter(link => /^(docs\.novelai\.net|huggingface\.co|stability\.ai|danbooru\.donmai\.us)$/.test(link.hostname));
  assert.ok(firstParty.length >= 4, 'At least four original documentation/model-card links are required');
  assert.ok(firstParty.some(link => link.hostname === 'docs.novelai.net'));
  assert.ok(firstParty.some(link => link.hostname === 'huggingface.co'));
});

test('every term and scene template provides a separate Chinese translation', () => {
  const library = readLibrary();
  const items = library.categories.flatMap(category => category.items);
  assert.equal(items.length, 384);
  assert.equal(library.templates.length, 30);
  for (const item of items) {
    nonempty(item.translation, `${item.label}.translation`);
    assert.match(item.translation, /[\u3400-\u9fff]/);
    english(item.text, `${item.label}.text`);
    assert.notEqual(item.translation, item.text, 'Translations must stay separate from inserted English');
  }
  for (const template of library.templates) {
    nonempty(template.translation, `${template.label}.translation`);
    const chineseCharacters = template.translation.match(/[\u3400-\u9fff]/g) || [];
    assert.ok(chineseCharacters.length >= 35, `${template.label} requires a complete Chinese scene translation`);
    assert.notEqual(template.translation, template.label);
    assert.notEqual(template.translation, template.note);
    assert.notEqual(template.translation, template.text);
    english(template.text, `${template.label}.text`);
  }
});
