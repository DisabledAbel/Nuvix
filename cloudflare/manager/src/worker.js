// Nuvix Contributors. GPL-3.0-or-later.
import { MAX_REQUEST, MAX_REPLY, PAIR_MS, SESSION_MS, CODE, TOKEN,
  normalizeCode, hash, allowedCommand, json, readJson } from './protocol.js';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.protocol !== 'https:' && url.hostname !== 'localhost') return json({ error: 'HTTPS required' }, 400);
    if (!url.pathname.startsWith('/api/')) {
      if (!['GET', 'HEAD'].includes(request.method)) return json({ error: 'Method not allowed' }, 405);
      return env.ASSETS.fetch(request);
    }
    // Browser writes must be same-origin. Native TV requests have no Origin.
    const origin = request.headers.get('Origin');
    if (origin && origin !== url.origin) return json({ error: 'Origin rejected' }, 403);
    const device = /^\/api\/device\/([23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12})$/.exec(url.pathname);
    const action = device ? 'device' : url.pathname.slice(5);
    if (!['create', 'pair', 'request', 'end', 'device'].includes(action) ||
        request.method !== (device ? 'GET' : 'POST')) return json({ error: 'Not found' }, 404);
    const limiter = ['create', 'pair', 'device'].includes(action) ? env.PAIR_LIMITER : env.REQUEST_LIMITER;
    // Anonymous pairing has no account ID; IP limits are deliberately only a coarse abuse bound.
    const key = request.headers.get('CF-Connecting-IP') || 'local';
    if (!(await limiter.limit({ key })).success) return json({ error: 'Too many attempts. Wait a minute.' }, 429);
    let payload = {};
    try { if (!device) payload = await readJson(request); }
    catch { return json({ error: 'Invalid or oversized request' }, 400); }
    const code = normalizeCode(device ? device[1] : payload.code);
    if (!CODE.test(code)) return json({ error: 'Code unavailable' }, 404);
    const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
    // Reconstruct internal routes so the public API cannot call arbitrary Durable Object paths.
    const headers = new Headers({ 'Content-Type': 'application/json' });
    const auth = request.headers.get('Authorization');
    if (auth) headers.set('Authorization', auth);
    if (device) headers.set('Upgrade', request.headers.get('Upgrade') || '');
    return stub.fetch(new Request(`https://room/${action}`, {
      method: device ? 'GET' : 'POST', headers,
      body: device ? undefined : JSON.stringify(payload),
    }));
  },
};

/** One isolated, short-lived session per TV code. Only credential hashes are stored. */
export class ManagerRoom {
  constructor(ctx) { this.ctx = ctx; this.pending = new Map(); }

  async fetch(request) {
    const action = new URL(request.url).pathname;
    let payload = {};
    try { if (request.method === 'POST') payload = await readJson(request); }
    catch { return json({ error: 'Invalid request' }, 400); }
    if (action === '/create') {
      if (!TOKEN.test(payload.tvToken)) return json({ error: 'Invalid token' }, 400);
      const tvHash = await hash(payload.tvToken);
      const created = await this.ctx.storage.transaction(async txn => {
        if (await txn.get('session')) return false;
        const now = Date.now();
        await txn.put('session', { tvHash, pairUntil: now + PAIR_MS, expires: now + SESSION_MS });
        await txn.setAlarm(now + PAIR_MS);
        return true;
      });
      return json({ ok: created }, created ? 201 : 409);
    }
    const token = (request.headers.get('Authorization') || '').replace(/^Bearer /, '');
    // Hash before the authoritative storage read: revocation may run while crypto awaits.
    const digest = TOKEN.test(token) ? await hash(token) : null;
    const session = await this.ctx.storage.get('session');
    if (!session || Date.now() >= session.expires || (!session.browserHash && Date.now() >= session.pairUntil)) {
      return json({ error: 'Code unavailable. Open a new session on the TV.' }, 404);
    }
    if (action === '/pair') {
      if (!TOKEN.test(payload.browserToken)) return json({ error: 'Invalid token' }, 400);
      if (!this.devices().length) return json({ error: 'TV offline. Keep the manager screen open.' }, 503);
      const browserHash = await hash(payload.browserToken);
      const paired = await this.ctx.storage.transaction(async txn => {
        const latest = await txn.get('session');
        if (!latest || Date.now() >= latest.expires) return false;
        if (latest.browserHash) return latest.browserHash === browserHash; // Safe retry after a lost response.
        if (Date.now() >= latest.pairUntil) return false;
        await txn.put('session', { ...latest, browserHash });
        await txn.setAlarm(latest.expires);
        return true;
      });
      if (paired) for (const socket of this.devices()) socket.send(JSON.stringify({ type: 'paired' }));
      return json(paired ? { ok: true, expires: session.expires } : { error: 'Code unavailable' }, paired ? 200 : 404);
    }
    if (!digest) return json({ error: 'Unauthorized' }, 401);
    if (action === '/device') {
      if (digest !== session.tvHash) return json({ error: 'Unauthorized' }, 401);
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({ error: 'WebSocket required' }, 426);
      if (this.devices().length) return json({ error: 'TV already connected' }, 409);
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1], ['tv']);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (action === '/end') {
      if (digest !== session.tvHash && digest !== session.browserHash) return json({ error: 'Unauthorized' }, 401);
      await this.expire();
      return json({ ok: true });
    }
    if (action !== '/request' || digest !== session.browserHash) return json({ error: 'Unauthorized' }, 401);
    if (!allowedCommand(payload.method, payload.path) ||
        typeof payload.body !== 'string' || new TextEncoder().encode(payload.body).length > MAX_REQUEST) {
      return json({ error: 'Unsupported command' }, 400);
    }
    const socket = this.devices()[0];
    if (!socket) return json({ error: 'TV offline. Reopen the manager on the TV.' }, 503);
    if (this.pending.size >= 4) return json({ error: 'TV busy. Wait for the current change.' }, 429);
    const id = crypto.randomUUID();
    // Outstanding HTTP requests keep the object active; idle sockets use hibernation.
    return new Promise(resolve => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(json({ error: 'TV timed out. A change may have applied; refresh before retrying.' }, 504));
      }, 30_000);
      this.pending.set(id, { resolve, timer });
      try { socket.send(JSON.stringify({ id, method: payload.method, path: payload.path, body: payload.body })); }
      catch { this.finish(id, json({ error: 'TV disconnected' }, 503)); }
    });
  }

  devices() { return this.ctx.getWebSockets('tv').filter(ws => ws.readyState === 1); }
  finish(id, response) {
    const pending = this.pending.get(id);
    if (pending) { clearTimeout(pending.timer); this.pending.delete(id); pending.resolve(response); }
  }
  async webSocketMessage(ws, message) {
    if (!this.devices().includes(ws)) return;
    if (typeof message !== 'string' || new TextEncoder().encode(message).length > MAX_REPLY) {
      ws.close(1009, 'Reply too large'); this.failPending(); return;
    }
    let reply;
    try { reply = JSON.parse(message); } catch { return; }
    if (!this.pending.has(reply.id)) return;
    if (!Number.isInteger(reply.status) || reply.status < 200 || reply.status > 599 || typeof reply.body !== 'string') {
      this.finish(reply.id, json({ error: 'Invalid TV reply' }, 502)); return;
    }
    this.finish(reply.id, json({ status: reply.status, body: reply.body }));
  }
  failPending() { for (const id of this.pending.keys()) this.finish(id, json({ error: 'TV disconnected' }, 503)); }
  webSocketClose(ws) { ws.close(1000, 'Session closed'); this.failPending(); }
  webSocketError(ws) { ws.close(1011, 'Connection failed'); this.failPending(); }
  async expire() {
    // Retain a tombstone until the original hard deadline: old tokens must never reconnect.
    const session = await this.ctx.storage.get('session');
    if (session) {
      await this.ctx.storage.put('session', { expires: Date.now(), pairUntil: 0 });
      await this.ctx.storage.setAlarm(Math.max(Date.now() + 60_000, session.expires));
    }
    for (const ws of this.devices()) ws.close(1000, 'Session ended');
    this.failPending();
  }
  async alarm() {
    const session = await this.ctx.storage.get('session');
    if (session && Date.now() >= session.expires) {
      for (const ws of this.devices()) ws.close(1000, 'Session expired');
      this.failPending();
      await this.ctx.storage.deleteAll();
    } else { await this.expire(); }
  }
}
