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
  if (typeof path !== 'string' || path.length > 4096) return false;
  const [route, query = ''] = path.split('?');
  if (path.split('?').length > 2) return false;
  if (method === 'GET' && route === '/channels') {
    const params = new URLSearchParams(query);
    return !!params.get('cat') && [...params.keys()].every(k => k === 'cat' || k === 'source');
  }
  if (query) return false;
  return (method === 'GET' && ['/meta', '/recording'].includes(route)) ||
    (method === 'POST' && ['/channel', '/reorder', '/recording', '/recording/test', '/source', '/source/test'].includes(route));
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
