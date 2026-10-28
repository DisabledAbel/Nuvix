import test from 'node:test';
import assert from 'node:assert/strict';
import { Miniflare } from 'miniflare';

test('Cloudflare runtime: isolated TV WebSocket, browser command, reply and revocation', { timeout: 30_000 }, async () => {
  const mf = new Miniflare({
    name: 'manager',
    modules: true, scriptPath: new URL('../src/worker.js', import.meta.url).pathname,
    modulesRules: [{ type: 'ESModule', include: ['**/*.js'] }],
    // Stable simulator version; the deployment uses wrangler.toml's newer date.
    compatibilityDate: '2025-10-08',
    durableObjects: { ROOMS: { className: 'ManagerRoom', useSQLite: true } },
    ratelimits: {
      PAIR_LIMITER: { namespace_id: '61001', simple: { limit: 1000, period: 60 } },
      REQUEST_LIMITER: { namespace_id: '61002', simple: { limit: 1000, period: 60 } },
    },
    assets: { directory: new URL('../public', import.meta.url).pathname, binding: 'ASSETS', workerName: 'manager',
      routerConfig: { invoke_user_worker_ahead_of_assets: true, has_user_worker: true } },
  });
  try {
    const code = 'ABCDEFGH2345', tvToken = 'a'.repeat(64), browserToken = 'b'.repeat(64);
    const api = (action, body, token) => mf.dispatchFetch('https://manager.test/api/' + action, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Origin: 'https://manager.test',
        ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: JSON.stringify({ code, ...body }),
    });
    const page = await mf.dispatchFetch('https://manager.test/');
    assert.equal(page.status, 200, page.status === 200 ? '' : await page.text());
    assert.match(page.headers.get('Content-Security-Policy'), /script-src 'self'/);
    assert.match(await page.text(), /Code from your TV/);
    const created = await api('create', { tvToken });
    assert.equal(created.status, 201);
    const timing = await created.json();
    assert.equal(timing.ok, true);
    assert.ok(Number.isSafeInteger(timing.pairUntil));
    assert.ok(Number.isSafeInteger(timing.serverNow));
    assert.ok(timing.pairUntil > timing.serverNow);
    assert.ok(timing.pairUntil - timing.serverNow <= 300_000);
    const badSocket = await mf.dispatchFetch('https://manager.test/api/device/' + code, {
      headers: { Upgrade: 'websocket', Authorization: 'Bearer ' + browserToken },
    });
    assert.equal(badSocket.status, 401);
    const connected = await mf.dispatchFetch('https://manager.test/api/device/' + code, {
      headers: { Upgrade: 'websocket', Authorization: 'Bearer ' + tvToken },
    });
    assert.equal(connected.status, 101);
    const socket = connected.webSocket; socket.accept();
    socket.addEventListener('message', event => {
      const command = JSON.parse(event.data);
      if (command.type === 'paired') return;
      assert.equal(command.path, '/meta');
      socket.send(JSON.stringify({ id: command.id, status: 200, body: '{"sources":[],"categories":[]}' }));
    });
    assert.equal((await api('pair', { browserToken })).status, 200);
    assert.equal((await api('pair', { browserToken: 'c'.repeat(64) })).status, 404);
    const response = await api('request', { method: 'GET', path: '/meta', body: '' }, browserToken);
    assert.equal(response.status, 200);
    assert.deepEqual(JSON.parse((await response.json()).body), { sources: [], categories: [] });
    assert.equal((await api('end', {}, browserToken)).status, 200);
    assert.equal((await api('request', { method: 'GET', path: '/meta', body: '' }, browserToken)).status, 404);
    socket.close();
  } finally { await mf.dispose(); }
});
