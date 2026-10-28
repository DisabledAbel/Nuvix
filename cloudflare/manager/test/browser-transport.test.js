import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const transport = readFileSync(new URL('../browser-transport.js', import.meta.url), 'utf8');
function browser() {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { hidden: false, value: 'previous TV', textContent: 'previous TV',
      className: 'show', placeholder: 'saved password', selectedIndex: 1, open: true,
      children: ['previous TV'], replaceChildren() { this.children = []; } });
    return nodes.get(id);
  };
  const timers = new Map(); let timerId = 0;
  const context = {
    AbortSignal: {}, AbortController,
    setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); },
    document: { getElementById: node, querySelectorAll(selector) {
      return selector === '#manager input' ? [node('provUrl'), node('recHost'), node('recPass'), node('catSearch')] :
        selector === '#manager select' ? [node('provKind')] : [node('recCard')];
    } },
    syncProvFields() {}, statusTimer: null,
    meta: { sources: [{ name: 'Old provider' }], categories: [{ label: 'Old category' }] },
    channels: [{ name: 'Old channel' }], currentCat: { label: 'Old category' }, currentSource: 12,
  };
  vm.createContext(context); vm.runInContext(transport, context);
  return { context, node, timers };
}

test('disconnect removes the previous TV state, lists, filters and form values', () => {
  const { context: c, node } = browser();
  c.activeCode = 'ABCDEFGH2345'; c.browserToken = 'secret'; c.disconnect();
  assert.equal(c.browserToken, ''); assert.equal(c.activeCode, '');
  assert.equal(c.meta.sources.length, 0); assert.equal(c.meta.categories.length, 0);
  assert.equal(c.channels.length, 0); assert.equal(c.currentCat, null); assert.equal(c.currentSource, null);
  for (const id of ['catList', 'chanList', 'srcBar']) assert.equal(node(id).children.length, 0);
  for (const id of ['provUrl', 'recHost', 'recPass', 'catSearch']) assert.equal(node(id).value, '');
  assert.equal(node('chanView').hidden, true); assert.equal(node('catView').hidden, false);
  assert.equal(node('manager').hidden, true); assert.equal(node('pairPanel').hidden, false);
  assert.equal(node('chanHead').textContent, ''); assert.equal(node('recPass').placeholder, '');
  assert.equal(node('provKind').selectedIndex, 0); assert.equal(node('recCard').open, false);
});

test('timeout fallback aborts the request and releases its timer', async () => {
  const { context: c, timers } = browser(); let signal;
  c.fetch = async (url, options) => {
    signal = options.signal;
    for (const fn of timers.values()) fn();
    assert.equal(signal.aborted, true);
    return { ok: true, json: async () => ({ ok: true }) };
  };
  await c.api('pair', {});
  assert.equal(timers.size, 0);
});

test('non-JSON HTTP errors retain actionable handling and native timeouts still work', async () => {
  const { context: c, timers } = browser(); let native = 0;
  c.AbortSignal = { timeout(ms) { assert.equal(ms, 35000); native++; return new AbortController().signal; } };
  c.fetch = async () => ({ ok: false, status: 502, json: async () => { throw new SyntaxError('HTML'); } });
  await assert.rejects(c.api('request', {}), /The TV could not complete this action/);
  assert.equal(native, 1); assert.equal(timers.size, 0);
});

test('an old TV response cannot populate a newly paired session', async () => {
  const { context: c } = browser(); let finish;
  c.activeCode = 'ABCDEFGH2345'; c.browserToken = 'old-token';
  c.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = c.remote('GET', '/meta', '');
  c.disconnect(); c.browserToken = 'new-token';
  finish({ ok: true, json: async () => ({ status: 200, body: '{"sources":["old"]}' }) });
  await assert.rejects(pending, /Session ended/);
});

test('late expired-session response cannot disconnect a newly paired browser', async () => {
  const { context: c } = browser(); let finish;
  c.activeCode = 'ABCDEFGH2345'; c.browserToken = 'old-token';
  c.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = c.remote('GET', '/meta', '');
  c.disconnect(); c.activeCode = 'NEWCODE23456'; c.browserToken = 'new-token';
  finish({ ok: false, status: 404, json: async () => ({ error: 'Session expired' }) });
  await assert.rejects(pending, /Session expired/);
  assert.equal(c.browserToken, 'new-token'); assert.equal(c.activeCode, 'NEWCODE23456');
});

test('expired session is labeled clearly and drops in-memory authentication', async () => {
  const { context: c, node } = browser(); c.browserToken = 'active-token'; c.activeCode = 'ABCDEFGH2345';
  c.fetch = async () => ({ ok: false, status: 404, json: async () => ({ error: 'Session expired' }) });
  await assert.rejects(c.api('request', {}, 'active-token'), /Session expired/);
  assert.equal(c.browserToken, ''); assert.equal(c.activeCode, '');
  assert.match(node('pairMessage').textContent, /expired or revoked/);
});
