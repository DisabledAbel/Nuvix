import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { ManagerRoom } from '../src/worker.js';
import { allowedCommand, normalizeCode, hash, readJson, MAX_REQUEST } from '../src/protocol.js';

const TV = 'a'.repeat(64), BROWSER = 'b'.repeat(64), OTHER = 'c'.repeat(64);
function room() {
  const data = new Map(); let chain = Promise.resolve(); const sent = [];
  const socket = { readyState: 1, send: text => sent.push(JSON.parse(text)),
    close() { this.readyState = 3; } };
  const storage = {
    async get(key) { return structuredClone(data.get(key)); },
    async put(key, value) { data.set(key, structuredClone(value)); },
    async setAlarm(time) { this.alarm = time; },
    async deleteAll() { data.clear(); },
    transaction(fn) { const work = chain.then(() => fn(storage)); chain = work.catch(() => {}); return work; },
  };
  const sockets = [];
  const ctx = { storage, getWebSockets: () => sockets };
  return { instance: new ManagerRoom(ctx), data, sent, socket, sockets, storage };
}
function request(path, body = {}, token) {
  return new Request('https://room' + path, { method: 'POST', headers: {
    'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}),
  }, body: JSON.stringify(body) });
}
async function created() {
  const r = room();
  assert.equal((await r.instance.fetch(request('/create', { tvToken: TV }))).status, 201);
  r.sockets.push(r.socket); return r;
}
async function paired() {
  const r = await created();
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: BROWSER }))).status, 200);
  r.sent.length = 0; return r;
}

test('only allow management operations, never arbitrary URLs or pages', () => {
  for (const [method, path] of [['GET', '/meta'], ['GET', '/channels?cat=a%20b&source=2'], ['POST', '/source/test']])
    assert.equal(allowedCommand(method, path), true);
  for (const path of ['https://evil.test', '//evil.test/meta', '/', '/meta?url=x', '/channels', '/channels?cat=a&t=secret', '/channels?cat=a?x', '/%6deta'])
    assert.equal(allowedCommand('GET', path), false, path);
  assert.equal(allowedCommand('DELETE', '/channel'), false);
  assert.equal(normalizeCode('abcd-efgh 2345'), 'ABCDEFGH2345');
});

test('streamed JSON is bounded even without Content-Length', async () => {
  const req = request('/create', { value: 'a'.repeat(MAX_REQUEST) });
  await assert.rejects(readJson(req));
  await assert.rejects(readJson(new Request('https://room', { method: 'POST', body: '{}' })));
});

test('stores hashes only and refuses collision/overwrite', async () => {
  const r = await created();
  assert.equal(r.data.get('session').tvHash, await hash(TV));
  assert.ok(!JSON.stringify([...r.data]).includes(TV));
  assert.equal((await r.instance.fetch(request('/create', { tvToken: OTHER }))).status, 409);
});

test('unknown code and expired code cannot pair', async () => {
  const empty = room();
  assert.equal((await empty.instance.fetch(request('/pair', { browserToken: BROWSER }))).status, 404);
  assert.equal(empty.data.size, 0);
  const r = await created(); r.data.get('session').pairUntil = 0;
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: BROWSER }))).status, 404);
});

test('pairing needs an online TV and one browser wins concurrent claims', async () => {
  const r = await created(); r.sockets.length = 0;
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: BROWSER }))).status, 503);
  r.sockets.push(r.socket);
  const responses = await Promise.all([BROWSER, OTHER].map(browserToken => r.instance.fetch(request('/pair', { browserToken }))));
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 404]);
});

test('lost pair response can be retried only by the same browser token', async () => {
  const r = await paired();
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: BROWSER }))).status, 200);
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: OTHER }))).status, 404);
  assert.equal(r.data.get('session').browserHash, await hash(BROWSER));
});

test('TV and browser tokens have separate roles and cannot cross sessions', async () => {
  const a = await paired(), b = await created();
  await b.instance.fetch(request('/pair', { browserToken: OTHER }));
  for (const token of [TV, OTHER, undefined]) {
    assert.equal((await a.instance.fetch(request('/request', { method: 'GET', path: '/meta', body: '' }, token))).status, 401);
  }
  assert.equal(a.sent.length, 0);
  assert.equal((await b.instance.fetch(request('/request', { method: 'GET', path: '/meta', body: '' }, BROWSER))).status, 401);
});

test('authenticated operation relays once and only its matching reply resolves', async () => {
  const r = await paired();
  const response = r.instance.fetch(request('/request', { method: 'POST', path: '/channel', body: '{"id":1,"hidden":true}' }, BROWSER));
  while (!r.sent.length) await new Promise(resolve => setImmediate(resolve));
  assert.equal(r.sent.length, 1); assert.equal(r.sent[0].path, '/channel');
  await r.instance.webSocketMessage(r.socket, JSON.stringify({ id: 'foreign', status: 200, body: '{}' }));
  assert.equal(r.instance.pending.size, 1);
  await r.instance.webSocketMessage(r.socket, JSON.stringify({ id: r.sent[0].id, status: 200, body: '{"ok":true}' }));
  assert.deepEqual(await (await response).json(), { status: 200, body: '{"ok":true}' });
  assert.equal(r.instance.pending.size, 0);
});

test('unsupported authenticated request never reaches the TV', async () => {
  const r = await paired();
  assert.equal((await r.instance.fetch(request('/request', { method: 'GET', path: 'http://192.168.1.1', body: '' }, BROWSER))).status, 400);
  assert.equal(r.sent.length, 0);
});

test('offline TV has a visible error and no queued mutation', async () => {
  const r = await paired(); r.sockets.length = 0;
  assert.equal((await r.instance.fetch(request('/request', { method: 'POST', path: '/channel', body: '{}' }, BROWSER))).status, 503);
  assert.equal(r.instance.pending.size, 0);
});

test('disconnect fails pending operations and never replays them', async () => {
  const r = await paired();
  const response = r.instance.fetch(request('/request', { method: 'POST', path: '/source', body: '{}' }, BROWSER));
  while (!r.sent.length) await new Promise(resolve => setImmediate(resolve));
  r.instance.webSocketClose(r.socket);
  assert.equal((await response).status, 503); assert.equal(r.instance.pending.size, 0);
  assert.equal(r.sent.length, 1);
});

test('browser revocation invalidates both roles, pairing and reconnect', async () => {
  const r = await paired();
  assert.equal((await r.instance.fetch(request('/end', {}, OTHER))).status, 401);
  assert.equal((await r.instance.fetch(request('/end', {}, BROWSER))).status, 200);
  assert.equal(r.socket.readyState, 3);
  for (const token of [BROWSER, TV]) {
    assert.equal((await r.instance.fetch(request('/request', { method: 'GET', path: '/meta', body: '' }, token))).status, 404);
  }
  assert.equal((await r.instance.fetch(request('/pair', { browserToken: OTHER }))).status, 404);
});

test('hard expiry closes sockets and deletes session hashes', async () => {
  const r = await paired(); r.data.get('session').expires = 0;
  await r.instance.alarm();
  assert.equal(r.data.size, 0); assert.equal(r.socket.readyState, 3);
});

test('public Worker rejects foreign origins, wrong methods and rate-limited pairing before room allocation', async () => {
  let allocations = 0;
  const env = { PAIR_LIMITER: { limit: async () => ({ success: false }) },
    ROOMS: { idFromName() { allocations++; } } };
  const foreign = new Request('https://manager.test/api/pair', { method: 'POST', headers: { Origin: 'https://evil.test' } });
  assert.equal((await worker.fetch(foreign, env)).status, 403);
  assert.equal((await worker.fetch(new Request('https://manager.test/api/pair'), env)).status, 404);
  const pair = new Request('https://manager.test/api/pair', { method: 'POST' });
  assert.equal((await worker.fetch(pair, env)).status, 429); assert.equal(allocations, 0);
});
