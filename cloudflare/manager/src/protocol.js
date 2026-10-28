// Nuvix Contributors. GPL-3.0-or-later.
export const MAX_REQUEST = 256 * 1024;
export const MAX_REPLY = 2 * 1024 * 1024;
export const PAIR_MS = 5 * 60 * 1000;
export const SESSION_MS = 30 * 60 * 1000;
export const CODE = /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$/;
export const TOKEN = /^[a-f0-9]{64}$/;
export const normalizeCode = value => typeof value === 'string'
  ? value.toUpperCase().replace(/[ -]/g, '') : '';
export async function hash(value) {
  const bytes = new TextEncoder().encode(value);
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    b => b.toString(16).padStart(2, '0')).join('');
}
export function allowedCommand(method, path) {
  if (typeof path !== 'string' || path.includes('#') || path.length > 4096) return false;
  const [route, query = ''] = path.split('?');
  if (path.split('?').length > 2) return false;
  if (method === 'GET' && route === '/channels/page') {
    const params = new URLSearchParams(query);
    const keys = [...params.keys()];
    const integer = (key, fallback, min, max) => {
      const value = params.has(key) ? params.get(key) : String(fallback);
      return /^[0-9]+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value) >= min && Number(value) <= max;
    };
    return new Set(keys).size === keys.length && keys.every(k => ['source', 'cat', 'q', 'limit', 'offset', 'revision'].includes(k)) &&
      integer('source', 0, 1, Number.MAX_SAFE_INTEGER) && integer('limit', 50, 1, 100) && integer('offset', 0, 0, 1000000) &&
      (params.get('q') || '').length <= 120 && (!params.has('cat') || params.get('cat').length >= 1 && params.get('cat').length <= 512) &&
      (!params.has('revision') || /^[0-9]{1,19}$/.test(params.get('revision')));
  }
  if (method === 'GET' && route === '/channels') {
    const params = new URLSearchParams(query);
    return !!params.get('cat') && [...params.keys()].every(k => k === 'cat' || k === 'source');
  }
  if (query) return false;
  return (method === 'GET' && ['/meta', '/recording'].includes(route)) ||
    (method === 'POST' && ['/channel', '/channels/bulk', '/channels/state', '/channels/move', '/reorder', '/recording', '/recording/test', '/source', '/source/test'].includes(route));
}
export function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: {
    'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  } });
}
export async function readJson(request) {
  if (!(request.headers.get('content-type') || '').startsWith('application/json')) throw new Error('json');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('body');
  const chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_REQUEST) throw new Error('size');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  const result = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('object');
  return result;
}

// Defense in depth before allocating work on the TV. Existing routes retain their byte cap.
export function validCommandBody(path, body) {
  if (!['/channel', '/channels/bulk', '/channels/state', '/channels/move'].includes(path)) return true;
  try {
    const value = JSON.parse(body);
    if (!value || !Number.isSafeInteger(value.source) || value.source <= 0 ||
        typeof value.revision !== 'string' || !/^[0-9]{1,19}$/.test(value.revision)) return false;
    if (path === '/channel') return Number.isSafeInteger(value.id) && value.id > 0 &&
      (value.hidden !== undefined || value.favourite !== undefined || value.name !== undefined) &&
      (value.hidden === undefined || typeof value.hidden === 'boolean') &&
      (value.favourite === undefined || typeof value.favourite === 'boolean') &&
      (value.name === undefined || typeof value.name === 'string' && value.name.length <= 512);
    if (path === '/channels/move') return Number.isSafeInteger(value.id) && value.id > 0 && [-1, 1].includes(value.direction);
    return (path === '/channels/state' || ['favorite', 'unfavorite', 'hide', 'unhide'].includes(value.action)) && Array.isArray(value.ids) &&
      value.ids.length >= 1 && value.ids.length <= 100 && new Set(value.ids).size === value.ids.length &&
      value.ids.every(id => Number.isSafeInteger(id) && id > 0);
  } catch { return false; }
}
