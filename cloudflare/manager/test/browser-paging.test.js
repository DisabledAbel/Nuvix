// Production browser code with a minimal DOM and controlled TV responses; no new dependencies.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
function browser() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, { hidden: false, value: '', checked: false, disabled: false, children: [],
      selectedOptions: [{ textContent: 'Hide' }], appendChild(n) { this.children.push(n); },
      replaceChildren() { this.children = []; }, setAttribute() {}, addEventListener(e, f) { this[e] = f; },
      focus() {}, select() {}, replaceWith() {} });
    return nodes.get(id);
  }
  const c = { document: { getElementById: node, createElement: () => node(Symbol()), querySelectorAll: () => [] },
    setTimeout() { return 1; }, clearTimeout() {}, Map, Set, URLSearchParams, AbortController,
    window: { scrollTo() {} }, location: { search: '' }, crypto: globalThis.crypto };
  vm.createContext(c);
  vm.runInContext(readFileSync(new URL('../public/app.js', import.meta.url), 'utf8').replace(/setupPairing\(\);\s*$/, ''), c);
  node('channelSource').value = '1'; node('bulkAction').value = 'hide';
  return { c, node };
}
const channel = id => ({ id, name: 'Duplicate', original: 'Original ' + id, favourite: false, hidden: false });
const page = (ids, revision = '1') => ({ rows: ids.map(channel), revision, total: 120, limit: 50, offset: 0 });
async function loaded(ids = [1, 2]) { const b = browser(); b.c.getJson = async () => page(ids); await b.c.loadPage(); return b; }

test('late searches are discarded and rapid input coalesces to one next read', async () => {
  const { c, node } = browser(), pending = [];
  c.getJson = path => new Promise(resolve => pending.push({ path, resolve }));
  const first = c.loadPage();
  node('channelSearch').value = 'custom'; c.loadPage();
  node('channelSearch').value = 'renamed'; c.loadPage();
  assert.equal(pending.length, 1); pending[0].resolve(page([1]));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(c.channels.length, 0); assert.equal(pending.length, 2); assert.match(pending[1].path, /q=renamed/);
  pending[1].resolve(page([2])); await first; assert.equal(c.channels[0].id, 2);
});

test('page selection is explicit and persists across pages and filters', async () => {
  const { c, node } = await loaded(); node('selectPage').onclick();
  assert.deepEqual([...c.selected.keys()], [1, 2]);
  c.pageOffset = 50; c.getJson = async () => page([51, 52]); await c.loadPage();
  assert.equal(c.selected.size, 2); assert.match(node('selectedCount').textContent, /0 on this page, 2 on other/);
  node('selectPage').onclick(); assert.deepEqual([...c.selected.keys()], [1, 2, 51, 52]);
  node('channelSearch').value = 'other'; c.getJson = async () => page([53]); await c.loadPage();
  assert.equal(c.selected.size, 4); node('clearSelection').onclick(); assert.equal(c.selected.size, 0);
});

test('review precedes exact-ID mutation and partial failures remain selected', async () => {
  const { c, node } = await loaded([1, 2, 3]); node('selectPage').onclick(); const sent = [];
  c.post = async (path, body) => { sent.push({ path, body }); return { results: [
    { id: 1, status: 'success' }, { id: 2, status: 'failed' }, { id: 3, status: 'skipped' }] }; };
  node('reviewBulk').onclick(); assert.match(node('bulkSummary').textContent, /exactly 3 selected/); assert.equal(sent.length, 0);
  await node('confirmBulk').onclick(); assert.equal(sent.length, 1);
  assert.deepEqual(Array.from(sent[0].body.ids), [1, 2, 3]); assert.deepEqual([...c.selected.keys()], [2]);
  assert.match(node('channelStatus').textContent, /1 successful, 1 failed, 1 skipped, 0 unknown, 0 not attempted/);
});

test('lost replies are never replayed and refresh reconciles off-page selections', async () => {
  const { c, node } = await loaded(); node('selectPage').onclick(); let mutations = 0;
  c.post = async () => { mutations++; throw new Error('Reply lost'); };
  node('reviewBulk').onclick(); await node('confirmBulk').onclick();
  assert.equal(mutations, 1); assert.equal(c.needsReconcile, true); assert.equal(c.selected.size, 2);
  assert.match(node('channelStatus').textContent, /2 unknown/);
  node('reviewBulk').onclick(); await node('confirmBulk').onclick(); assert.equal(mutations, 1);
  const reads = [];
  c.post = async (path, body) => { reads.push(path); return body.ids.map(id => ({ ...channel(id), hidden: true })); };
  c.getJson = async () => page([51]); await c.refreshState();
  assert.deepEqual(reads, ['/channels/state']); assert.equal(c.needsReconcile, false);
  assert.equal(c.selected.get(1).hidden, true); assert.equal(c.selected.get(2).hidden, true); assert.equal(mutations, 1);
});

test('catalog conflict or removed provider clears stale selections and requires browsing again', async () => {
  const { c, node } = await loaded(); node('selectPage').onclick();
  c.getJson = async () => { const e = new Error('Provider removed. Refresh providers.'); e.status = 409; throw e; };
  await c.loadPage(); assert.equal(c.selected.size, 0); assert.equal(c.catalogRevision, null); assert.equal(c.pageReady, false);
  assert.match(node('channelStatus').textContent, /Provider removed/);
});

test('session expiry discards a batch late reply without restoring selection', async () => {
  const { c, node } = await loaded(); node('selectPage').onclick(); let finish;
  c.post = () => new Promise(resolve => { finish = resolve; });
  node('reviewBulk').onclick(); const applying = node('confirmBulk').onclick(); c.resetChannelState();
  finish({ results: [{ id: 1, status: 'success' }, { id: 2, status: 'success' }] }); await applying;
  assert.equal(c.selected.size, 0); assert.equal(c.mutationBusy, false); assert.equal(c.catalogRevision, null);
});

test('batches stay at 100 IDs with one in flight and stop on an uncertain reply', async () => {
  const { c, node } = await loaded([1]);
  for (let id = 1; id <= 250; id++) c.selected.set(id, channel(id));
  let running = 0, maximum = 0; const sizes = [];
  c.post = async (path, body) => {
    running++; maximum = Math.max(maximum, running); sizes.push(body.ids.length); await Promise.resolve(); running--;
    if (sizes.length === 2) throw new Error('offline');
    return { results: body.ids.map(id => ({ id, status: 'success' })) };
  };
  node('reviewBulk').onclick(); await node('confirmBulk').onclick();
  assert.deepEqual(sizes, [100, 100]); assert.equal(maximum, 1); assert.equal(c.selected.size, 150);
  assert.match(node('channelStatus').textContent, /100 successful.*100 unknown, 50 not attempted/);
});

test('stop finishes only the accepted batch and preserves unattempted selection', async () => {
  const { c, node } = await loaded([1]);
  for (let id = 1; id <= 250; id++) c.selected.set(id, channel(id));
  let finish, calls = 0;
  c.post = (path, body) => { calls++; return new Promise(resolve => { finish = () => resolve({ results: body.ids.map(id => ({ id, status: 'success' })) }); }); };
  node('reviewBulk').onclick(); const applying = node('confirmBulk').onclick();
  node('stopBulk').onclick(); finish(); await applying;
  assert.equal(calls, 1); assert.equal(c.selected.size, 150); assert.equal(c.needsReconcile, false);
  assert.match(node('channelStatus').textContent, /100 successful.*150 not attempted/);
});

test('select page refuses a selection cap overflow without silently selecting part of the page', async () => {
  const { c, node } = await loaded([501, 502]);
  for (let id = 1; id <= 499; id++) c.selected.set(id, channel(id));
  node('selectPage').onclick(); assert.equal(c.selected.size, 499);
  assert.equal(c.selected.has(501), false); assert.equal(c.selected.has(502), false);
  assert.match(node('channelStatus').textContent, /exceed 500/);
});

test('pagination stops at the API offset bound and search can restart at the first page', async () => {
  const { c, node } = await loaded(); c.pageOffset = 1000000; c.pageTotal = 2000000; c.updateSelection();
  assert.equal(node('nextPage').disabled, true);
  c.changeSearch(); assert.equal(c.pageOffset, 0); assert.equal(c.pageReady, false);
});
